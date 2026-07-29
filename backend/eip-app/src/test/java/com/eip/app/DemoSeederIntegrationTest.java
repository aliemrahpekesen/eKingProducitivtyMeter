/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.eip.app.config.DemoDataSeeder;
import com.eip.tenancy.context.RlsTenantBinder;
import com.eip.tenancy.context.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves the one-command demo path is green headlessly: booting with the {@code demo} profile runs
 * {@link DemoDataSeeder}, which seeds the fixed demo tenant and drives the real friction pipeline
 * as the NOBYPASSRLS {@code eip_app} role — so {@code /api/v1/friction/summary} shows computed
 * friction (Platform 91 > Payments 56 > Web 50), not seed rows. Also pays down DEBT-011 (seeder had
 * no test).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("demo")
@Tag("integration")
class DemoSeederIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

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
    } catch (SQLException e) {
      throw new IllegalStateException("failed to prepare test database", e);
    }
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Autowired private DataSource dataSource;

  @Test
  void demo_seeder_boots_and_computes_friction_for_the_fixed_tenant() throws SQLException {
    // The seeder ran on context startup (ApplicationRunner). The fixed demo tenant now has computed
    // friction for its three teams — proving the demo boots green with the real pipeline under RLS.
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      RlsTenantBinder.bind(c, TenantContext.of(DemoDataSeeder.DEMO_TENANT_ID));
      assertThat(scalar(c, "SELECT count(*) FROM analytics.rm_team_friction_current"))
          .isEqualTo(3L);
      assertThat(scalar(c, "SELECT count(*) FROM analytics.flow_correlation")).isEqualTo(9L);
      assertThat(
              scalar(
                  c,
                  "SELECT f.friction_score FROM analytics.rm_team_friction_current f"
                      + " JOIN core.team t ON t.id = f.team_id WHERE t.name = 'Platform'"))
          .isEqualTo(91L);
      c.rollback();
    }
  }

  private static long scalar(Connection c, String sql) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
