/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.eip.app.ingestion.SimulationIngestionRunner;
import com.eip.ingestion.staging.IngestionResult;
import com.eip.tenancy.context.RlsTenantBinder;
import com.eip.tenancy.context.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves INC-1 end-to-end against a real PostgreSQL as the NOBYPASSRLS {@code eip_app} role: the
 * simulation connector's records travel the real ingestion path into {@code staging.raw_simulation}
 * (not seeded dashboard rows), replaying the same dataset is idempotent (no duplicates, no {@code
 * ingested_at} churn), provenance is retained, and staged rows are RLS-isolated — tenant B cannot
 * see tenant A's raw data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Tag("integration")
class SimulationIngestionIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  private static final UUID tenantA = UUID.randomUUID();
  private static final UUID tenantB = UUID.randomUUID();

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    POSTGRES.start();
    prepareDatabase();
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "eip_app");
    registry.add("spring.datasource.password", () -> "eip_app_pw");
    registry.add("spring.flyway.enabled", () -> "false"); // migrated below as the superuser
  }

  private static void prepareDatabase() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute("CREATE ROLE eip_app LOGIN PASSWORD 'eip_app_pw' NOBYPASSRLS");
      // The ingestion pipeline creates a connector row and writes raw staging under RLS.
      st.execute("GRANT USAGE ON SCHEMA core TO eip_app");
      st.execute("GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA core TO eip_app");
      st.execute("GRANT USAGE ON SCHEMA staging TO eip_app");
      st.execute("GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA staging TO eip_app");
      st.execute(
          "INSERT INTO core.tenant (id, name, slug) VALUES ('"
              + tenantA
              + "', 'Tenant A', 'tenant-a')");
      st.execute(
          "INSERT INTO core.tenant (id, name, slug) VALUES ('"
              + tenantB
              + "', 'Tenant B', 'tenant-b')");
    } catch (SQLException e) {
      throw new IllegalStateException("failed to prepare test database", e);
    }
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Autowired private SimulationIngestionRunner runner;
  @Autowired private DataSource dataSource;

  @Test
  void ingests_via_the_real_path_idempotently_and_tenant_isolated() throws SQLException {
    // First ingest: the full dataset is staged (79 records, all new) — proves data enters via the
    // connector/ingestion path, not by seeding dashboard rows.
    IngestionResult first = runner.ingest(tenantA);
    assertThat(first.emitted()).isEqualTo(79);
    assertThat(first.inserted()).isEqualTo(79);
    assertThat(first.updated()).isZero();
    assertThat(first.unchanged()).isZero();
    assertThat(countRaw(tenantA)).isEqualTo(79L);

    // Provenance is retained on the staged rows (jira work items + transitions, scm PRs + reviews,
    // ci builds, sonar gates).
    assertThat(countRawWhere(tenantA, "source_system = 'jira'")).isEqualTo(42L); // 9 items + 33 tx
    assertThat(countRawWhere(tenantA, "source_system = 'bitbucket'"))
        .isEqualTo(19L); // 9 PR + 10 rev
    assertThat(countRawWhere(tenantA, "source_system = 'ci'")).isEqualTo(9L);
    assertThat(countRawWhere(tenantA, "source_system = 'sonarqube'")).isEqualTo(9L);
    assertThat(countRawWhere(tenantA, "external_id = 'jira:PLAT-101'")).isEqualTo(1L);

    long stampBefore = maxIngestedAtEpoch(tenantA);

    // Replay: same dataset, so nothing changes — no duplicates, no ingested_at churn.
    IngestionResult second = runner.ingest(tenantA);
    assertThat(second.emitted()).isEqualTo(79);
    assertThat(second.inserted()).isZero();
    assertThat(second.updated()).isZero();
    assertThat(second.unchanged()).isEqualTo(79);
    assertThat(countRaw(tenantA)).isEqualTo(79L);
    assertThat(maxIngestedAtEpoch(tenantA)).isEqualTo(stampBefore);

    // Tenant isolation: tenant B never sees tenant A's staged raw data (RLS, eip_app NOBYPASSRLS).
    assertThat(countRaw(tenantB)).isZero();
  }

  private long countRaw(UUID tenant) throws SQLException {
    return queryLong(tenant, "SELECT count(*) FROM staging.raw_simulation");
  }

  private long countRawWhere(UUID tenant, String predicate) throws SQLException {
    return queryLong(tenant, "SELECT count(*) FROM staging.raw_simulation WHERE " + predicate);
  }

  private long maxIngestedAtEpoch(UUID tenant) throws SQLException {
    return queryLong(
        tenant,
        "SELECT coalesce(extract(epoch from max(ingested_at))::bigint, 0)"
            + " FROM staging.raw_simulation");
  }

  /**
   * Runs a scalar-long query under {@code tenant}'s RLS binding on an {@code eip_app} connection.
   */
  private long queryLong(UUID tenant, String sql) throws SQLException {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        RlsTenantBinder.bind(c, TenantContext.of(tenant));
        try (Statement st = c.createStatement();
            ResultSet rs = st.executeQuery(sql)) {
          rs.next();
          return rs.getLong(1);
        }
      } finally {
        c.rollback();
        c.setAutoCommit(true);
      }
    }
  }
}
