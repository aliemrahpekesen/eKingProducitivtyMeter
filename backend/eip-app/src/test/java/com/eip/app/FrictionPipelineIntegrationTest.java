/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.eip.app.friction.FrictionPipelineRunner;
import com.eip.app.friction.FrictionPipelineRunner.PipelineResult;
import com.eip.tenancy.context.RlsTenantBinder;
import com.eip.tenancy.context.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.function.Function;
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
 * Proves the full Engineering Friction vertical slice end-to-end against a real PostgreSQL as the
 * NOBYPASSRLS {@code eip_app} role: raw ingestion → normalization into the canonical model →
 * cross-tool correlation → deterministic team-level friction computation → the {@code metric_fact}
 * / {@code rm_team_friction_current} read model. Asserts the canonical entities and correlation
 * evidence are created, friction is computed worst-first with reproducible scores, recomputation is
 * idempotent, and every tier (raw / canonical / metric / evidence) is tenant-isolated.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Tag("integration")
class FrictionPipelineIntegrationTest {

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
    registry.add("spring.flyway.enabled", () -> "false");
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
      for (String schema :
          new String[] {"core", "work", "scm", "cicd", "quality", "analytics", "staging"}) {
        st.execute("GRANT USAGE ON SCHEMA " + schema + " TO eip_app");
        st.execute(
            "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA "
                + schema
                + " TO eip_app");
      }
      // Tenants + tenant A's org/BU/teams (names match the simulation dataset).
      st.execute(insert("core.tenant (id, name, slug)", "'" + tenantA + "', 'A', 'tenant-a'"));
      st.execute(insert("core.tenant (id, name, slug)", "'" + tenantB + "', 'B', 'tenant-b'"));
      UUID org = UUID.randomUUID();
      UUID bu = UUID.randomUUID();
      st.execute(
          insert(
              "core.organization (id, tenant_id, name, slug)",
              "'" + org + "', '" + tenantA + "', 'Org A', 'org-a'"));
      st.execute(
          insert(
              "core.business_unit (id, tenant_id, organization_id, name)",
              "'" + bu + "', '" + tenantA + "', '" + org + "', 'Eng'"));
      for (String team : new String[] {"Platform", "Payments", "Web"}) {
        st.execute(
            insert(
                "core.team (id, tenant_id, business_unit_id, name, type)",
                "'"
                    + UUID.randomUUID()
                    + "', '"
                    + tenantA
                    + "', '"
                    + bu
                    + "', '"
                    + team
                    + "', 'STREAM_ALIGNED'"));
      }
    } catch (SQLException e) {
      throw new IllegalStateException("failed to prepare test database", e);
    }
  }

  private static String insert(String into, String values) {
    return "INSERT INTO " + into + " VALUES (" + values + ")";
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Autowired private FrictionPipelineRunner runner;
  @Autowired private DataSource dataSource;

  @Test
  void computes_friction_end_to_end_deterministically_and_tenant_isolated() throws SQLException {
    PipelineResult first = runner.run(tenantA);
    assertThat(first.ingestion().inserted()).isEqualTo(79);
    assertThat(first.teamsComputed()).isEqualTo(3);
    assertThat(first.itemsCorrelated()).isEqualTo(9);

    // Canonical entities created via normalization.
    assertThat(count(tenantA, "work.work_item")).isEqualTo(9L);
    assertThat(count(tenantA, "work.work_item_transition")).isEqualTo(33L);
    assertThat(count(tenantA, "scm.pull_request")).isEqualTo(9L);
    assertThat(count(tenantA, "scm.code_review")).isEqualTo(10L);
    assertThat(count(tenantA, "cicd.build")).isEqualTo(9L);
    assertThat(count(tenantA, "quality.quality_gate")).isEqualTo(9L);
    assertThat(count(tenantA, "core.external_ref")).isEqualTo(9L); // one per work item

    // Correlation evidence: every work item stitched to its PR + build + gate.
    assertThat(count(tenantA, "analytics.flow_correlation")).isEqualTo(9L);
    assertThat(
            countWhere(
                tenantA,
                "analytics.flow_correlation",
                "pull_request_id IS NOT NULL AND build_id IS NOT NULL"
                    + " AND quality_gate_id IS NOT NULL"))
        .isEqualTo(9L);

    // Computed friction, worst-first, reproducible: Platform 91 > Payments 56 > Web 50.
    assertThat(scoreOf(tenantA, "Platform")).isEqualTo(91L);
    assertThat(scoreOf(tenantA, "Payments")).isEqualTo(56L);
    assertThat(scoreOf(tenantA, "Web")).isEqualTo(50L);
    assertThat(dominantOf(tenantA, "Platform")).isEqualTo("REVIEW_WAIT");
    // metric_fact time series: one point per team.
    assertThat(count(tenantA, "analytics.metric_fact")).isEqualTo(3L);
    // The definition was upserted to v0.1.
    assertThat(
            text(
                tenantA,
                "SELECT name FROM analytics.metric_definition WHERE metric_key ="
                    + " 'engineering_friction'"))
        .contains("v0.1");

    // Re-run: idempotent + reproducible — no duplicate rows, identical score.
    PipelineResult second = runner.run(tenantA);
    assertThat(second.ingestion().inserted()).isZero();
    assertThat(second.ingestion().unchanged()).isEqualTo(79);
    assertThat(count(tenantA, "work.work_item")).isEqualTo(9L);
    assertThat(count(tenantA, "analytics.flow_correlation")).isEqualTo(9L);
    assertThat(count(tenantA, "analytics.metric_fact")).isEqualTo(3L);
    assertThat(scoreOf(tenantA, "Platform")).isEqualTo(91L);

    // Tenant isolation across every tier (raw / canonical / metric / evidence).
    assertThat(count(tenantB, "staging.raw_simulation")).isZero();
    assertThat(count(tenantB, "work.work_item")).isZero();
    assertThat(count(tenantB, "analytics.flow_correlation")).isZero();
    assertThat(count(tenantB, "analytics.rm_team_friction_current")).isZero();
  }

  // --- RLS-scoped query helpers ---------------------------------------------

  private long count(UUID tenant, String table) throws SQLException {
    return scalar(tenant, "SELECT count(*) FROM " + table, rs -> getLong(rs));
  }

  private long countWhere(UUID tenant, String table, String predicate) throws SQLException {
    return scalar(
        tenant, "SELECT count(*) FROM " + table + " WHERE " + predicate, rs -> getLong(rs));
  }

  private long scoreOf(UUID tenant, String team) throws SQLException {
    return scalar(
        tenant,
        "SELECT f.friction_score FROM analytics.rm_team_friction_current f"
            + " JOIN core.team t ON t.id = f.team_id WHERE t.name = '"
            + team
            + "'",
        rs -> getLong(rs));
  }

  private String dominantOf(UUID tenant, String team) throws SQLException {
    return scalar(
        tenant,
        "SELECT f.dominant_cause FROM analytics.rm_team_friction_current f"
            + " JOIN core.team t ON t.id = f.team_id WHERE t.name = '"
            + team
            + "'",
        rs -> getString(rs));
  }

  private String text(UUID tenant, String sql) throws SQLException {
    return scalar(tenant, sql, rs -> getString(rs));
  }

  private <T> T scalar(UUID tenant, String sql, Function<ResultSet, T> extractor)
      throws SQLException {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        RlsTenantBinder.bind(c, TenantContext.of(tenant));
        try (PreparedStatement ps = c.prepareStatement(sql);
            ResultSet rs = ps.executeQuery()) {
          rs.next();
          return extractor.apply(rs);
        }
      } finally {
        c.rollback();
        c.setAutoCommit(true);
      }
    }
  }

  private static long getLong(ResultSet rs) {
    try {
      return rs.getLong(1);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String getString(ResultSet rs) {
    try {
      return rs.getString(1);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
