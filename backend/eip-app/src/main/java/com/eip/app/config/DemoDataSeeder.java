/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.config;

import com.eip.app.friction.FrictionPipelineRunner;
import com.eip.tenancy.context.RlsTenantBinder;
import com.eip.tenancy.context.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Seeds deterministic <strong>simulation</strong> demo data — one demo tenant with an organisation,
 * a few simulation connectors, and a team hierarchy — then runs the real friction pipeline for it
 * ({@link FrictionPipelineRunner}: raw ingestion → normalization → correlation → computed
 * friction), so the visible slices ({@code /api/v1/session}, {@code /api/v1/connectors}, {@code
 * /api/v1/friction/summary}) demonstrate computed friction with no real source connectivity.
 * Idempotent, and gated to the {@code demo} profile so it never runs in production or in tests.
 *
 * <p>The demo tenant uses a <strong>fixed, well-known id</strong> ({@link #DEMO_TENANT_ID}) so the
 * frontend can load it via {@code VITE_EIP_TENANT_ID} without a DB lookup. Demo-only (production
 * resolves tenants via OIDC). The control-plane seed commits before the pipeline runs, because the
 * pipeline reads the teams back on its own transaction.
 */
@Component
@Profile("demo")
public class DemoDataSeeder implements ApplicationRunner {

  /**
   * The fixed demo tenant id. Stable across seeds so the one-command demo ({@code make demo-up})
   * and the frontend default work without a DB lookup. Demo-only — production never uses it.
   */
  public static final UUID DEMO_TENANT_ID = UUID.fromString("00000000-0000-4000-8000-0000000000de");

  private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

  private final DataSource dataSource;
  private final JdbcClient jdbc;
  private final FrictionPipelineRunner pipeline;

  public DemoDataSeeder(DataSource dataSource, FrictionPipelineRunner pipeline) {
    this.dataSource = dataSource;
    this.jdbc = JdbcClient.create(dataSource);
    this.pipeline = pipeline;
  }

  @Override
  public void run(ApplicationArguments args) {
    boolean exists =
        jdbc.sql("SELECT count(*) FROM core.tenant WHERE slug = 'demo'")
                .query(Integer.class)
                .single()
            > 0;
    if (exists) {
      log.info("demo data already present — tenant {} (slug 'demo')", DEMO_TENANT_ID);
      return;
    }
    seedControlPlane();
    FrictionPipelineRunner.PipelineResult result = pipeline.run(DEMO_TENANT_ID);
    log.info(
        "seeded demo tenant {} (slug 'demo') + ran friction pipeline: {} staged, {} teams computed,"
            + " {} items correlated",
        DEMO_TENANT_ID,
        result.ingestion().inserted(),
        result.teamsComputed(),
        result.itemsCorrelated());
  }

  /** Seeds the demo control plane (tenant, org, connectors, teams) in one committed transaction. */
  private void seedControlPlane() {
    UUID orgId = UUID.randomUUID();
    UUID buId = UUID.randomUUID();
    try (Connection c = dataSource.getConnection()) {
      boolean previousAutoCommit = c.getAutoCommit();
      c.setAutoCommit(false);
      try {
        RlsTenantBinder.bind(c, TenantContext.of(DEMO_TENANT_ID));
        update(
            c,
            "INSERT INTO core.tenant (id, name, slug) VALUES (?, 'Demo Tenant', 'demo')",
            DEMO_TENANT_ID);
        update(
            c,
            "INSERT INTO core.organization (id, tenant_id, name, slug)"
                + " VALUES (?, ?, 'Demo Org', 'demo-org')",
            orgId,
            DEMO_TENANT_ID);
        insertConnector(c, "jira", "Demo Jira (simulation)");
        insertConnector(c, "bitbucket", "Demo Bitbucket (simulation)");
        insertConnector(c, "sonarqube", "Demo SonarQube (simulation)");
        update(
            c,
            "INSERT INTO core.business_unit (id, tenant_id, organization_id, name)"
                + " VALUES (?, ?, ?, 'Demo Engineering')",
            buId,
            DEMO_TENANT_ID,
            orgId);
        // Team names match the simulation dataset so normalization resolves work items to them.
        insertTeam(c, buId, "Platform");
        insertTeam(c, buId, "Payments");
        insertTeam(c, buId, "Web");
        c.commit();
      } catch (RuntimeException | SQLException e) {
        c.rollback();
        throw new IllegalStateException("failed to seed demo control plane", e);
      } finally {
        c.setAutoCommit(previousAutoCommit);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("demo seed connection failure", e);
    }
  }

  private void insertConnector(Connection c, String type, String name) throws SQLException {
    update(
        c,
        "INSERT INTO core.connector (id, tenant_id, type, name, status, simulation)"
            + " VALUES (?, ?, ?, ?, 'REGISTERED', true)",
        UUID.randomUUID(),
        DEMO_TENANT_ID,
        type,
        name);
  }

  private void insertTeam(Connection c, UUID buId, String name) throws SQLException {
    update(
        c,
        "INSERT INTO core.team (id, tenant_id, business_unit_id, name, type)"
            + " VALUES (?, ?, ?, ?, 'STREAM_ALIGNED')",
        UUID.randomUUID(),
        DEMO_TENANT_ID,
        buId,
        name);
  }

  private static void update(Connection c, String sql, Object... params) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        ps.setObject(i + 1, params[i]);
      }
      ps.executeUpdate();
    }
  }
}
