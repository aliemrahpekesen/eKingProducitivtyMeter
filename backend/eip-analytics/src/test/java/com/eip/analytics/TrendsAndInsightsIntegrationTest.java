/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.eip.analytics.api.InFlightItemView;
import com.eip.analytics.api.RecommendationView;
import com.eip.analytics.api.TeamInFlightView;
import com.eip.analytics.api.TeamRecommendationsView;
import com.eip.analytics.api.TeamTrendView;
import com.eip.analytics.api.TrendPointView;
import com.eip.analytics.api.TrendsView;
import com.eip.analytics.application.FrictionComputationService;
import com.eip.analytics.application.InFlightService;
import com.eip.analytics.application.RecommendationService;
import com.eip.analytics.application.TrendService;
import com.eip.analytics.persistence.FrictionProjectionRepository;
import com.eip.analytics.persistence.FrictionReadRepository;
import com.eip.analytics.persistence.InFlightRepository;
import com.eip.analytics.persistence.TrendRepository;
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
import java.util.List;
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
 * Module-level proof of metric trends, in-flight work, and rule-based recommendations against real
 * PostgreSQL as the NOBYPASSRLS {@code eip_app} role. Reuses the exact golden canonical dataset
 * from {@link FrictionAnalyticsIntegrationTest} (one team, two resolved items T-1/T-2) plus one
 * additional unresolved item T-3 (created 2026-01-06, status {@code IN_PROGRESS}) so the same
 * fixture exercises trends, in-flight, and recommendations together. No Spring context: services
 * are constructed directly.
 *
 * <p>Golden arithmetic (derived, not guessed): team cycle 11h (8h + 3h), active 3h, blocked 2h,
 * review-wait 4h, rework 0 (see {@link FrictionAnalyticsIntegrationTest} for the per-item
 * timeline). Trends: itemsResolved=2, avgCycleSec=11h*3600/2=19800, p85CycleSec=8h*3600=28800
 * (sorted cycles [3h,8h], index ceil(0.85*2)-1=1), flowEfficiencyPct=round(100*3/11)=27,
 * blockedPct=round(100*2/11) =18, reviewWaitPct=round(100*4/11)=36,
 * frictionScore=round(100*6/11)=55. Recommendations from these numbers: reviewWaitPct=36&lt;40 and
 * blockedPct=18&lt;25 and reworkCount=0 do NOT fire R-REVIEW-WAIT/R-BLOCKED/R-REWORK;
 * flowEfficiencyPct=27&lt;30 fires R-FLOW-EFFICIENCY (WARN); the unresolved T-3's age (created
 * 2026-01-06, computed against real {@code now()}) vastly exceeds 2*avgCycleSec (39600s), so
 * R-AGING-WIP fires (WARN) — ordered R-AGING-WIP before R-FLOW-EFFICIENCY (code ascending, same
 * severity).
 */
@Tag("integration")
class TrendsAndInsightsIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  private static final UUID TENANT_A = UUID.randomUUID();
  private static final UUID TEAM = UUID.randomUUID();
  private static final UUID ITEM_1 = UUID.randomUUID();
  private static final UUID ITEM_2 = UUID.randomUUID();
  private static final UUID ITEM_3 = UUID.randomUUID();
  private static final UUID PR_1 = UUID.randomUUID();
  private static final UUID BUILD_1 = UUID.randomUUID();
  private static final UUID GATE_1 = UUID.randomUUID();
  private static final Instant BASE = Instant.parse("2026-01-05T09:00:00Z");

  private static FrictionComputationService compute;
  private static TrendService trends;
  private static InFlightService inFlight;
  private static RecommendationService recommendations;

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

      seedResolvedItem(st, ITEM_1, "T-1", "Blocked and reviewed", 0, 8);
      transition(st, ITEM_1, 1, "TODO", "IN_PROGRESS", 1);
      transition(st, ITEM_1, 2, "IN_PROGRESS", "BLOCKED", 2);
      transition(st, ITEM_1, 3, "BLOCKED", "IN_PROGRESS", 4);
      transition(st, ITEM_1, 4, "IN_PROGRESS", "IN_REVIEW", 5);
      transition(st, ITEM_1, 5, "IN_REVIEW", "DONE", 8);
      seedResolvedItem(st, ITEM_2, "T-2", "Clean flow", 0, 3);
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

      // T-3: unresolved, created 2026-01-06 (BASE + 24h), still IN_PROGRESS — the in-flight
      // fixture.
      seedUnresolvedItem(st, ITEM_3, "T-3", "Investigate flaky test", 24, "IN_PROGRESS");
      transition(st, ITEM_3, 1, "TODO", "IN_PROGRESS", 25);
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
    trends = new TrendService(runner, new TrendRepository(jdbc));
    inFlight = new InFlightService(runner, new InFlightRepository(jdbc));
    recommendations =
        new RecommendationService(
            runner,
            new FrictionReadRepository(jdbc, new ObjectMapper()),
            new InFlightRepository(jdbc));
  }

  private static void seedResolvedItem(
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
    externalRef(st, id, key);
  }

  private static void seedUnresolvedItem(
      Statement st, UUID id, String key, String title, int createdH, String status)
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
            + "', '"
            + status
            + "', '"
            + TEAM
            + "', '"
            + at(createdH)
            + "', NULL)");
    externalRef(st, id, key);
  }

  private static void externalRef(Statement st, UUID id, String key) throws SQLException {
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
  void computes_trends_in_flight_work_and_recommendations_deterministically() {
    TenantContext tenantA = TenantContext.of(TENANT_A);
    compute.compute(tenantA); // populates flow_correlation + the friction read model

    TenantContextHolder.set(tenantA);

    // Trends: one team, one week bucket, exact golden arithmetic (see class javadoc).
    TrendsView trendsView = trends.trends(12);
    assertThat(trendsView.rangeWeeks()).isEqualTo(12);
    assertThat(trendsView.teams()).hasSize(1);
    TeamTrendView team = trendsView.teams().get(0);
    assertThat(team.teamName()).isEqualTo("Platform");
    assertThat(team.points()).hasSize(1);
    TrendPointView point = team.points().get(0);
    assertThat(point.weekStart()).isEqualTo("2026-01-05");
    assertThat(point.itemsResolved()).isEqualTo(2);
    assertThat(point.avgCycleSec()).isEqualTo(19_800L);
    assertThat(point.p85CycleSec()).isEqualTo(8 * 3600L);
    assertThat(point.flowEfficiencyPct()).isEqualTo(27);
    assertThat(point.blockedPct()).isEqualTo(18);
    assertThat(point.reviewWaitPct()).isEqualTo(36);
    assertThat(point.frictionScore()).isEqualTo(55);

    // In-flight: only T-3, unresolved, not blocked.
    List<TeamInFlightView> inFlightViews = inFlight.inFlight();
    assertThat(inFlightViews).hasSize(1);
    TeamInFlightView teamInFlight = inFlightViews.get(0);
    assertThat(teamInFlight.teamName()).isEqualTo("Platform");
    assertThat(teamInFlight.items()).hasSize(1);
    InFlightItemView inFlightItem = teamInFlight.items().get(0);
    assertThat(inFlightItem.workItemKey()).isEqualTo("T-3");
    assertThat(inFlightItem.state()).isEqualTo("IN_PROGRESS");
    assertThat(inFlightItem.blocked()).isFalse();
    assertThat(inFlightItem.ageSec()).isGreaterThan(0L);

    // Recommendations: reviewWaitPct=36<40, blockedPct=18<25, reworkCount=0 -> those 3 rules don't
    // fire. flowEfficiencyPct=27<30 fires R-FLOW-EFFICIENCY. T-3's age (created 2026-01-06,
    // computed
    // against the real clock) vastly exceeds 2*avgCycleSec (39_600s) -> R-AGING-WIP fires. Both
    // WARN,
    // ordered by code ascending.
    List<TeamRecommendationsView> recs = recommendations.recommendations();
    assertThat(recs).hasSize(1);
    TeamRecommendationsView teamRecs = recs.get(0);
    assertThat(teamRecs.teamName()).isEqualTo("Platform");
    assertThat(teamRecs.frictionScore()).isEqualTo(55);
    assertThat(teamRecs.recommendations())
        .extracting(RecommendationView::code)
        .containsExactly("R-AGING-WIP", "R-FLOW-EFFICIENCY");
    assertThat(teamRecs.recommendations())
        .extracting(RecommendationView::severity)
        .containsOnly("WARN");
  }

  /**
   * DEBT-020 item 2 regression: {@code core.external_ref} is unique per {@code (source_system,
   * source_instance, external_id)}, not per {@code entity_id} — a second identity source anchored
   * to the same in-flight work item must not duplicate its row.
   */
  @Test
  void in_flight_items_are_not_duplicated_by_a_second_identity_source_for_the_same_entity()
      throws SQLException {
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute(
          "INSERT INTO core.external_ref (tenant_id, entity_type, entity_id, source_system,"
              + " source_instance, external_id, external_key, last_seen_at) VALUES ('"
              + TENANT_A
              + "', 'WORK_ITEM', '"
              + ITEM_3
              + "', 'github', 'sim', 'github:T-3-dup', 'T-3', now())");
    }

    TenantContextHolder.set(TenantContext.of(TENANT_A));
    List<TeamInFlightView> inFlightViews = inFlight.inFlight();

    assertThat(inFlightViews).hasSize(1);
    assertThat(inFlightViews.get(0).items()).hasSize(1); // still 1, not 2 — no fan-out
    assertThat(inFlightViews.get(0).items().get(0).workItemKey()).isEqualTo("T-3");
  }
}
