/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.eip.analytics.api.ComputeFrictionUseCase.FrictionComputation;
import com.eip.analytics.api.FrictionEvidenceView;
import com.eip.analytics.api.FrictionSummaryView;
import com.eip.analytics.application.FrictionComputationService;
import com.eip.analytics.application.FrictionEvidenceService;
import com.eip.analytics.application.FrictionSummaryService;
import com.eip.analytics.persistence.FrictionProjectionRepository;
import com.eip.analytics.persistence.FrictionReadRepository;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.context.TenantContextHolder;
import com.eip.tenancy.tx.TenantTransactionRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Module-level proof of the friction compute + read slice against real PostgreSQL as the
 * NOBYPASSRLS {@code eip_app} role, over a small hand-seeded canonical dataset: set-based
 * correlation + deterministic scoring into evidence/read-model/facts, idempotent recomputation,
 * summary/evidence query assembly, and tenant isolation. No Spring context: the services are
 * constructed directly, proving they are plain constructor-injected components.
 *
 * <p>Golden dataset: one team, two items. Item T-1: TODO@0h → IN_PROGRESS@1h → BLOCKED@2h →
 * IN_PROGRESS@4h → IN_REVIEW@5h → DONE@8h (active 2h, blocked 2h, review 3h, cycle 8h). Item T-2:
 * TODO@0h → IN_PROGRESS@1h → IN_REVIEW@2h → DONE@3h (active 1h, review 1h, cycle 3h). Team: cycle
 * 11h, waiting 6h → friction = round(100·6/11) = 55, dominant REVIEW_WAIT (4h > 2h).
 */
@Tag("integration")
class FrictionAnalyticsIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  private static final UUID TENANT_A = UUID.randomUUID();
  private static final UUID TENANT_B = UUID.randomUUID();
  private static final UUID TEAM = UUID.randomUUID();
  private static final UUID ITEM_1 = UUID.randomUUID();
  private static final UUID ITEM_2 = UUID.randomUUID();
  private static final UUID PR_1 = UUID.randomUUID();
  private static final UUID BUILD_1 = UUID.randomUUID();
  private static final UUID GATE_1 = UUID.randomUUID();
  private static final Instant BASE = Instant.parse("2026-01-05T09:00:00Z");

  private static FrictionComputationService compute;
  private static FrictionSummaryService summary;
  private static FrictionEvidenceService evidence;

  @BeforeAll
  static void setUp() throws SQLException {
    POSTGRES.start();
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(
            "filesystem:"
                + Paths.get("../eip-app/src/main/resources/db/migration")
                    .toAbsolutePath()
                    .normalize())
        .load()
        .migrate();
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute("CREATE ROLE eip_app LOGIN PASSWORD 'eip_app_pw' NOBYPASSRLS");
      for (String schema : new String[] {"core", "work", "scm", "cicd", "quality", "analytics"}) {
        st.execute("GRANT USAGE ON SCHEMA " + schema + " TO eip_app");
        st.execute(
            "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA "
                + schema
                + " TO eip_app");
      }
      st.execute("INSERT INTO core.tenant (id, name, slug) VALUES ('" + TENANT_A + "', 'A', 'a')");
      st.execute("INSERT INTO core.tenant (id, name, slug) VALUES ('" + TENANT_B + "', 'B', 'b')");
      UUID org = UUID.randomUUID();
      UUID bu = UUID.randomUUID();
      st.execute(
          "INSERT INTO core.organization (id, tenant_id, name, slug) VALUES ('"
              + org
              + "', '"
              + TENANT_A
              + "', 'Org', 'org')");
      st.execute(
          "INSERT INTO core.business_unit (id, tenant_id, organization_id, name) VALUES ('"
              + bu
              + "', '"
              + TENANT_A
              + "', '"
              + org
              + "', 'Eng')");
      st.execute(
          "INSERT INTO core.team (id, tenant_id, business_unit_id, name, type) VALUES ('"
              + TEAM
              + "', '"
              + TENANT_A
              + "', '"
              + bu
              + "', 'Platform', 'STREAM_ALIGNED')");

      seedItem(st, ITEM_1, "T-1", "Blocked and reviewed", 0, 8);
      transition(st, ITEM_1, 1, "TODO", "IN_PROGRESS", 1);
      transition(st, ITEM_1, 2, "IN_PROGRESS", "BLOCKED", 2);
      transition(st, ITEM_1, 3, "BLOCKED", "IN_PROGRESS", 4);
      transition(st, ITEM_1, 4, "IN_PROGRESS", "IN_REVIEW", 5);
      transition(st, ITEM_1, 5, "IN_REVIEW", "DONE", 8);
      seedItem(st, ITEM_2, "T-2", "Clean flow", 0, 3);
      transition(st, ITEM_2, 1, "TODO", "IN_PROGRESS", 1);
      transition(st, ITEM_2, 2, "IN_PROGRESS", "IN_REVIEW", 2);
      transition(st, ITEM_2, 3, "IN_REVIEW", "DONE", 3);

      st.execute(
          "INSERT INTO scm.pull_request (id, tenant_id, team_id, work_item_id, source_key) VALUES"
              + " ('"
              + PR_1
              + "', '"
              + TENANT_A
              + "', '"
              + TEAM
              + "', '"
              + ITEM_1
              + "', 'PR-1')");
      st.execute(
          "INSERT INTO cicd.build (id, tenant_id, pull_request_id, source_key, status) VALUES ('"
              + BUILD_1
              + "', '"
              + TENANT_A
              + "', '"
              + PR_1
              + "', 'BUILD-1', 'SUCCESS')");
      st.execute(
          "INSERT INTO quality.quality_gate (id, tenant_id, build_id, pull_request_id, source_key,"
              + " status) VALUES ('"
              + GATE_1
              + "', '"
              + TENANT_A
              + "', '"
              + BUILD_1
              + "', '"
              + PR_1
              + "', 'QG-1', 'PASSED')");
    }

    PGSimpleDataSource app = new PGSimpleDataSource();
    app.setUrl(POSTGRES.getJdbcUrl());
    app.setUser("eip_app");
    app.setPassword("eip_app_pw");
    DataSource dataSource = app;

    TenantTransactionRunner runner =
        new TenantTransactionRunner(new JdbcTransactionManager(dataSource), dataSource);
    JdbcClient jdbc = JdbcClient.create(dataSource);
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    compute =
        new FrictionComputationService(
            runner, new FrictionProjectionRepository(jdbc, jdbcTemplate));
    FrictionReadRepository readRepository = new FrictionReadRepository(jdbc, new ObjectMapper());
    summary = new FrictionSummaryService(runner, readRepository);
    evidence = new FrictionEvidenceService(runner, readRepository);
  }

  private static void seedItem(
      Statement st, UUID id, String key, String title, int createdH, int resolvedH)
      throws SQLException {
    st.execute(
        "INSERT INTO work.work_item (id, tenant_id, type, title, project_id, current_state_id,"
            + " status, team_id, created_in_source, resolved_at) VALUES ('"
            + id
            + "', '"
            + TENANT_A
            + "', 'STORY', '"
            + title
            + "', '"
            + UUID.randomUUID()
            + "', '"
            + UUID.randomUUID()
            + "', 'DONE', '"
            + TEAM
            + "', '"
            + at(createdH)
            + "', '"
            + at(resolvedH)
            + "')");
    st.execute(
        "INSERT INTO core.external_ref (tenant_id, entity_type, entity_id, source_system,"
            + " source_instance, external_id, external_key, last_seen_at) VALUES ('"
            + TENANT_A
            + "', 'WORK_ITEM', '"
            + id
            + "', 'jira', 'sim', 'jira:"
            + key
            + "', '"
            + key
            + "', now())");
  }

  private static void transition(
      Statement st, UUID itemId, int seq, String from, String to, int hour) throws SQLException {
    st.execute(
        "INSERT INTO work.work_item_transition (tenant_id, work_item_id, seq, from_state,"
            + " to_state, occurred_at) VALUES ('"
            + TENANT_A
            + "', '"
            + itemId
            + "', "
            + seq
            + ", '"
            + from
            + "', '"
            + to
            + "', '"
            + at(hour)
            + "')");
  }

  private static String at(int hour) {
    return BASE.plusSeconds(hour * 3600L).toString();
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @AfterEach
  void clearHolder() {
    TenantContextHolder.clear();
  }

  @Test
  void computes_projects_and_reads_friction_deterministically_under_rls() {
    TenantContext tenantA = TenantContext.of(TENANT_A);

    FrictionComputation first = compute.compute(tenantA);
    assertThat(first).isEqualTo(new FrictionComputation(1, 2));

    // Recompute: idempotent + reproducible.
    FrictionComputation second = compute.compute(tenantA);
    assertThat(second).isEqualTo(first);

    // Summary: golden score 55, dominant REVIEW_WAIT, component percentages from the dataset.
    TenantContextHolder.set(tenantA);
    FrictionSummaryView view = summary.summary();
    assertThat(view.teamsReporting()).isEqualTo(1);
    assertThat(view.metricVersion()).isEqualTo("engineering_friction_v0.1");
    assertThat(view.simulation()).isTrue();
    assertThat(view.metric()).isNotNull();
    assertThat(view.teams().get(0).frictionScore()).isEqualTo(55);
    assertThat(view.teams().get(0).dominantCause()).isEqualTo("REVIEW_WAIT");
    assertThat(view.teams().get(0).workItems()).isEqualTo(2);
    assertThat(view.teams().get(0).flowEfficiencyPct()).isEqualTo(27); // 3h / 11h

    // Evidence: correlated artifacts + full timelines, ordered by work-item key.
    FrictionEvidenceView teamEvidence = evidence.evidence(TEAM);
    assertThat(teamEvidence.teamName()).isEqualTo("Platform");
    assertThat(teamEvidence.items()).hasSize(2);
    assertThat(teamEvidence.items().get(0).workItemKey()).isEqualTo("T-1");
    assertThat(teamEvidence.items().get(0).pullRequestKey()).isEqualTo("PR-1");
    assertThat(teamEvidence.items().get(0).buildKey()).isEqualTo("BUILD-1");
    assertThat(teamEvidence.items().get(0).qualityGateKey()).isEqualTo("QG-1");
    assertThat(teamEvidence.items().get(0).transitions()).hasSize(5);
    assertThat(teamEvidence.items().get(0).blockedSec()).isEqualTo(2 * 3600L);
    assertThat(teamEvidence.items().get(1).workItemKey()).isEqualTo("T-2");
    assertThat(teamEvidence.items().get(1).pullRequestKey()).isNull();

    // Tenant isolation: tenant B sees neither summary rows nor the team's evidence.
    TenantContextHolder.set(TenantContext.of(TENANT_B));
    assertThat(summary.summary().teamsReporting()).isZero();
    FrictionEvidenceView crossTenant = evidence.evidence(TEAM);
    assertThat(crossTenant.teamName()).isNull();
    assertThat(crossTenant.items()).isEmpty();
  }
}
