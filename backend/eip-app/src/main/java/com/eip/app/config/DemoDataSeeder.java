/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.config;

import com.eip.tenancy.context.RlsTenantBinder;
import com.eip.tenancy.context.TenantContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds deterministic <strong>simulation</strong> demo data — one demo tenant with an organisation,
 * a few simulation connectors, and a team hierarchy with Engineering Friction flow signals — so the
 * first visible slices ({@code /api/v1/session}, {@code /api/v1/connectors}, {@code
 * /api/v1/friction/summary}) demonstrate without any real source connectivity. Idempotent, and
 * gated to the {@code demo} profile so it never runs in production or in tests. Not real
 * Jira/Bitbucket/Sonar connectivity — just {@code core.connector} rows flagged {@code simulation =
 * true} and simulated team-level flow signals.
 */
@Component
@Profile("demo")
public class DemoDataSeeder implements ApplicationRunner {

  private final DataSource dataSource;
  private final JdbcClient jdbc;

  public DemoDataSeeder(DataSource dataSource) {
    this.dataSource = dataSource;
    this.jdbc = JdbcClient.create(dataSource);
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    boolean exists =
        jdbc.sql("SELECT count(*) FROM core.tenant WHERE slug = 'demo'")
                .query(Integer.class)
                .single()
            > 0;
    if (exists) {
      return;
    }
    UUID tenantId = UUID.randomUUID();
    jdbc.sql("INSERT INTO core.tenant (id, name, slug) VALUES (?, 'Demo Tenant', 'demo')")
        .param(tenantId)
        .update();

    // Bind the demo tenant so the RLS WITH CHECK admits the tenant-scoped inserts below.
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      RlsTenantBinder.bind(connection, TenantContext.of(tenantId));
    } catch (SQLException e) {
      throw new DataAccessResourceFailureException("failed to bind demo tenant", e);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }

    UUID orgId = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO core.organization (id, tenant_id, name, slug) VALUES (?, ?, 'Demo Org', 'demo-org')")
        .params(orgId, tenantId)
        .update();
    insertConnector(tenantId, "jira", "Demo Jira (simulation)");
    insertConnector(tenantId, "bitbucket", "Demo Bitbucket (simulation)");
    insertConnector(tenantId, "sonarqube", "Demo SonarQube (simulation)");

    // Engineering Friction demo: a team hierarchy + the metric definition + simulated flow signals.
    UUID buId = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO core.business_unit (id, tenant_id, organization_id, name) "
                + "VALUES (?, ?, ?, 'Demo Engineering')")
        .params(buId, tenantId, orgId)
        .update();
    insertFrictionDefinition(tenantId);
    // Deterministic simulated signals — worst-first order Platform > Payments > Web.
    insertTeamFlow(tenantId, buId, "Platform", 12, 3, 5 * SECONDS_PER_DAY, 4);
    insertTeamFlow(tenantId, buId, "Payments", 6, 1, 2 * SECONDS_PER_DAY, 2);
    insertTeamFlow(tenantId, buId, "Web", 3, 0, 1 * SECONDS_PER_DAY, 1);
  }

  private static final long SECONDS_PER_DAY = 86_400L;

  /** Human-readable formula mirrors {@code com.eip.app.api.FrictionScore}. */
  private static final String FRICTION_FORMULA =
      "friction = round(min(100, 10*wip_limit_breaches + 6*review_queue_depth "
          + "+ 4*(oldest_in_progress_age_sec/86400.0))); WIP is context, not scored";

  /** The metric's inputs, surfaced as-is (FEAT-031 / FR-056). */
  private static final String FRICTION_INPUTS =
      "{\"signals\":[\"wip_limit_breaches\",\"review_queue_depth\",\"oldest_in_progress_age_sec\"],"
          + "\"weights\":{\"wip_limit_breaches\":10,\"review_queue_depth\":6,\"age_day\":4},"
          + "\"cap\":100,\"note\":\"WIP shown as context, not scored\"}";

  private void insertConnector(UUID tenantId, String type, String name) {
    jdbc.sql(
            "INSERT INTO core.connector (id, tenant_id, type, name, status, simulation) "
                + "VALUES (?, ?, ?, ?, 'REGISTERED', true)")
        .params(UUID.randomUUID(), tenantId, type, name)
        .update();
  }

  private void insertFrictionDefinition(UUID tenantId) {
    jdbc.sql(
            "INSERT INTO analytics.metric_definition "
                + "(id, tenant_id, metric_key, name, purpose, formula, inputs, grain, caveats, gaming_risks) "
                + "VALUES (?, ?, 'engineering_friction', 'Engineering Friction', ?, ?, ?::jsonb, 'team', ?, ?)")
        .params(
            UUID.randomUUID(),
            tenantId,
            "Where engineering time is lost: a team-level index of flow friction.",
            FRICTION_FORMULA,
            FRICTION_INPUTS,
            "v1 placeholder over current flow signals; not yet wait-time decomposition across the "
                + "correlated flow graph. Team-level only — never individual.",
            "Splitting work items to lower WIP, or closing review threads without real review, can "
                + "understate friction; read alongside throughput and cycle time.")
        .update();
  }

  private void insertTeamFlow(
      UUID tenantId,
      UUID buId,
      String teamName,
      int wip,
      int wipLimitBreaches,
      long oldestAgeSec,
      int reviewQueueDepth) {
    UUID teamId = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO core.team (id, tenant_id, business_unit_id, name, type) "
                + "VALUES (?, ?, ?, ?, 'STREAM_ALIGNED')")
        .params(teamId, tenantId, buId, teamName)
        .update();
    jdbc.sql(
            "INSERT INTO analytics.rm_team_flow_current "
                + "(id, tenant_id, team_id, wip, wip_limit_breaches, oldest_in_progress_age_sec, "
                + "review_queue_depth) VALUES (?, ?, ?, ?, ?, ?, ?)")
        .params(
            UUID.randomUUID(),
            tenantId,
            teamId,
            wip,
            wipLimitBreaches,
            oldestAgeSec,
            reviewQueueDepth)
        .update();
  }
}
