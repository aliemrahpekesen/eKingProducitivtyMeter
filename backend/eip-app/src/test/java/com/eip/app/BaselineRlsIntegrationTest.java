/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eip.tenancy.context.RlsTenantBinder;
import com.eip.tenancy.context.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves the deployable {@code V1__baseline.sql} + {@code R__rls_policies.sql} apply cleanly on a
 * real PostgreSQL 16 + pgvector and that Row-Level Security structurally isolates tenants
 * (DatabasePlan §5/§14). This is the #1 de-risk of the persistence spine: RLS correctness under the
 * transaction-scoped GUC set by {@link RlsTenantBinder}. Runs as the non-superuser {@code eip_app}
 * role ({@code NOBYPASSRLS}) so the policies actually bite.
 */
@Tag("integration")
class BaselineRlsIntegrationTest {

  private static final String APP_ROLE = "eip_app";
  private static final String APP_PW = "eip_app_pw";

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  private static UUID tenantA;
  private static UUID tenantB;

  @BeforeAll
  static void setUp() throws SQLException {
    POSTGRES.start();

    // The deployable migrations apply exactly as in production (as the migrator/owner).
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();

    try (Connection admin = superuser()) {
      try (Statement st = admin.createStatement()) {
        st.execute("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_PW + "' NOBYPASSRLS");
        st.execute("GRANT USAGE ON SCHEMA core TO " + APP_ROLE);
        st.execute(
            "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA core TO " + APP_ROLE);
      }
      // Seed two tenants + one org + one simulation connector each (superuser bypasses RLS —
      // setup).
      tenantA = insertTenant(admin, "Tenant A", "tenant-a");
      tenantB = insertTenant(admin, "Tenant B", "tenant-b");
      insertOrg(admin, tenantA, "Org A", "org-a");
      insertOrg(admin, tenantB, "Org B", "org-b");
      insertConnector(admin, tenantA, "jira", "Demo Jira (simulation)");
      insertConnector(admin, tenantB, "bitbucket", "Demo Bitbucket (simulation)");
    }
  }

  @AfterAll
  static void tearDown() {
    POSTGRES.stop();
  }

  @Test
  void tenant_sees_only_its_own_rows() throws SQLException {
    try (Connection c = appConnection()) {
      c.setAutoCommit(false);

      RlsTenantBinder.bind(c, TenantContext.of(tenantA));
      assertThat(orgSlugs(c)).containsExactly("org-a"); // tenant B's row is invisible
      c.rollback();

      RlsTenantBinder.bind(c, TenantContext.of(tenantB));
      assertThat(orgSlugs(c)).containsExactly("org-b"); // tenant A's row is invisible
      c.rollback();
    }
  }

  @Test
  void write_for_another_tenant_is_rejected_by_with_check() throws SQLException {
    try (Connection c = appConnection()) {
      c.setAutoCommit(false);
      RlsTenantBinder.bind(c, TenantContext.of(tenantA));

      assertThatThrownBy(() -> insertOrg(c, tenantB, "Sneaky", "sneaky"))
          .isInstanceOf(SQLException.class); // WITH CHECK: cannot write another tenant's row
      c.rollback();
    }
  }

  @Test
  void unset_tenant_guc_errors_rather_than_returning_zero_rows() throws SQLException {
    try (Connection c = appConnection()) {
      c.setAutoCommit(false);
      // No bind: app.tenant_id is unset, and current_setting has no default (DatabasePlan §5).
      assertThatThrownBy(() -> orgSlugs(c)).isInstanceOf(SQLException.class);
      c.rollback();
    }
  }

  @Test
  void connector_registry_is_tenant_isolated() throws SQLException {
    try (Connection c = appConnection()) {
      c.setAutoCommit(false);

      RlsTenantBinder.bind(c, TenantContext.of(tenantA));
      assertThat(connectorNames(c)).containsExactly("Demo Jira (simulation)"); // not tenant B's
      c.rollback();

      RlsTenantBinder.bind(c, TenantContext.of(tenantB));
      assertThat(connectorNames(c)).containsExactly("Demo Bitbucket (simulation)");
      c.rollback();
    }
  }

  @Test
  void every_tenant_owned_unique_constraint_includes_tenant_id() throws SQLException {
    // Scale-out invariant (DatabasePlan §15 / §14): every non-primary UNIQUE index on a
    // tenant-owned table must include tenant_id, so a tenant's rows stay portable as a unit.
    String sql =
        "SELECT n.nspname || '.' || t.relname AS tbl, i.relname AS idx, "
            + "       array_agg(a.attname ORDER BY k.ord) AS cols "
            + "FROM pg_index ix "
            + "JOIN pg_class i ON i.oid = ix.indexrelid "
            + "JOIN pg_class t ON t.oid = ix.indrelid "
            + "JOIN pg_namespace n ON n.oid = t.relnamespace "
            + "JOIN unnest(ix.indkey) WITH ORDINALITY AS k(attnum, ord) ON true "
            + "JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum "
            + "WHERE ix.indisunique AND NOT ix.indisprimary "
            + "  AND n.nspname IN ('core','work','analytics','audit') "
            + "  AND t.relname NOT IN ('tenant','worker_heartbeat','analytics_watermark') "
            + "GROUP BY 1, 2";
    try (Connection admin = superuser();
        PreparedStatement ps = admin.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      int checked = 0;
      while (rs.next()) {
        String cols = rs.getString("cols");
        assertThat(cols)
            .as(
                "unique index %s on %s must include tenant_id",
                rs.getString("idx"), rs.getString("tbl"))
            .contains("tenant_id");
        checked++;
      }
      assertThat(checked)
          .as("at least the tenancy-core natural-key uniques are checked")
          .isGreaterThan(5);
    }
  }

  @Test
  void tenant_scoped_tables_have_rls_enabled_and_forced() throws SQLException {
    try (Connection admin = superuser();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT relrowsecurity, relforcerowsecurity FROM pg_class c "
                    + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = 'core' AND c.relname = 'organization'");
        ResultSet rs = ps.executeQuery()) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getBoolean(1)).as("RLS enabled").isTrue();
      assertThat(rs.getBoolean(2)).as("RLS forced").isTrue();
    }
  }

  // --- helpers -------------------------------------------------------------

  private static Connection superuser() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static Connection appConnection() throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_PW);
  }

  private static UUID insertTenant(Connection c, String name, String slug) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement ps =
        c.prepareStatement("INSERT INTO core.tenant (id, name, slug) VALUES (?, ?, ?)")) {
      ps.setObject(1, id);
      ps.setString(2, name);
      ps.setString(3, slug);
      ps.executeUpdate();
    }
    return id;
  }

  private static void insertOrg(Connection c, UUID tenantId, String name, String slug)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO core.organization (id, tenant_id, name, slug) VALUES (?, ?, ?, ?)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setObject(2, tenantId);
      ps.setString(3, name);
      ps.setString(4, slug);
      ps.executeUpdate();
    }
  }

  private static void insertConnector(Connection c, UUID tenantId, String type, String name)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO core.connector (id, tenant_id, type, name, status, simulation) "
                + "VALUES (?, ?, ?, ?, 'REGISTERED', true)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setObject(2, tenantId);
      ps.setString(3, type);
      ps.setString(4, name);
      ps.executeUpdate();
    }
  }

  private static List<String> connectorNames(Connection c) throws SQLException {
    try (PreparedStatement ps =
            c.prepareStatement("SELECT name FROM core.connector ORDER BY name");
        ResultSet rs = ps.executeQuery()) {
      List<String> names = new ArrayList<>();
      while (rs.next()) {
        names.add(rs.getString(1));
      }
      return names;
    }
  }

  private static List<String> orgSlugs(Connection c) throws SQLException {
    try (PreparedStatement ps =
            c.prepareStatement("SELECT slug FROM core.organization ORDER BY slug");
        ResultSet rs = ps.executeQuery()) {
      List<String> slugs = new ArrayList<>();
      while (rs.next()) {
        slugs.add(rs.getString(1));
      }
      return slugs;
    }
  }
}
