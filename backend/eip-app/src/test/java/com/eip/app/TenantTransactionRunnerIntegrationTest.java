/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.context.TenantContextHolder;
import com.eip.tenancy.tx.TenantTransactionRunner;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real-database proof of the tenant-aware transaction boundary as the NOBYPASSRLS {@code eip_app}
 * role: the GUC binds to the actual transaction-bound connection (visible to {@code JdbcClient}
 * work in the same transaction), RLS WITH CHECK admits the bound tenant's writes and hides them
 * from other tenants, failures roll the transaction back, the transaction-local GUC never leaks
 * back into the pool, and current-tenant reads fail closed without a bound context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Tag("integration")
class TenantTransactionRunnerIntegrationTest {

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
      st.execute("GRANT USAGE ON SCHEMA core TO eip_app");
      st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA core TO eip_app");
      st.execute("INSERT INTO core.tenant (id, name, slug) VALUES ('" + tenantA + "', 'A', 'a')");
      st.execute("INSERT INTO core.tenant (id, name, slug) VALUES ('" + tenantB + "', 'B', 'b')");
    } catch (SQLException e) {
      throw new IllegalStateException("failed to prepare test database", e);
    }
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Autowired private TenantTransactionRunner runner;
  @Autowired private JdbcClient jdbc;
  @Autowired private DataSource dataSource;

  @AfterEach
  void clearHolder() {
    TenantContextHolder.clear();
  }

  @Test
  void binds_the_guc_on_the_transaction_bound_connection() {
    String bound =
        runner.call(
            TenantContext.of(tenantA),
            () -> jdbc.sql("SELECT current_setting('app.tenant_id')").query(String.class).single());
    assertThat(bound).isEqualTo(tenantA.toString());
  }

  @Test
  void writes_commit_under_the_bound_tenant_and_stay_invisible_to_others() {
    UUID orgId = UUID.randomUUID();
    runner.run(TenantContext.of(tenantA), () -> insertOrg(orgId, tenantA, "committed-org"));

    assertThat(countOrg(tenantA, "committed-org")).isEqualTo(1L);
    // Tenant B must not see tenant A's row (RLS isolation through the runner).
    assertThat(countOrg(tenantB, "committed-org")).isZero();
  }

  @Test
  void a_failing_unit_of_work_rolls_back_completely() {
    UUID orgId = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                runner.run(
                    TenantContext.of(tenantA),
                    () -> {
                      insertOrg(orgId, tenantA, "rolled-back-org");
                      throw new IllegalStateException("boom");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(countOrg(tenantA, "rolled-back-org")).isZero();
  }

  @Test
  void the_transaction_local_guc_never_leaks_back_into_the_pool() throws SQLException {
    runner.run(
        TenantContext.of(tenantA), () -> insertOrg(UUID.randomUUID(), tenantA, "leak-probe"));

    // A fresh pooled connection outside the runner must carry no tenant GUC.
    try (Connection c = dataSource.getConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT current_setting('app.tenant_id', true)")) {
      rs.next();
      assertThat(rs.getString(1)).isIn(null, "");
    }
  }

  @Test
  void current_tenant_reads_fail_closed_without_a_bound_context() {
    assertThatThrownBy(() -> runner.readCurrent(() -> "x"))
        .isInstanceOf(TenantContextHolder.NoTenantBoundException.class);
  }

  private void insertOrg(UUID id, UUID tenantId, String slug) {
    jdbc.sql(
            """
            INSERT INTO core.organization (id, tenant_id, name, slug)
            VALUES (:id, :tenantId, :slug, :slug)
            """)
        .param("id", id)
        .param("tenantId", tenantId)
        .param("slug", slug)
        .update();
  }

  private long countOrg(UUID tenant, String slug) {
    return runner.read(
        TenantContext.of(tenant),
        () ->
            jdbc.sql("SELECT count(*) FROM core.organization WHERE slug = :slug")
                .param("slug", slug)
                .query(Long.class)
                .single());
  }
}
