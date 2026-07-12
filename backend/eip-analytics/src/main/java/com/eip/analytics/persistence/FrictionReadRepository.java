/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.persistence;

import com.eip.analytics.api.FrictionEvidenceView.TransitionEvidenceView;
import com.eip.analytics.api.FrictionMetricView;
import com.eip.analytics.api.TeamFrictionView;
import com.eip.analytics.friction.FrictionDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Friction read-side adapter over the computed read model and correlation evidence. Bounded queries
 * only: the summary is two statements, the evidence is three (team, items+artifacts, all
 * transitions in one joined query) — never per-row loops.
 */
@Repository
public class FrictionReadRepository {

  private final JdbcClient jdbc;
  private final ObjectMapper mapper;

  public FrictionReadRepository(JdbcClient jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  /** One computed summary row with its metric-version/computed-at metadata. */
  public record SummaryRow(TeamFrictionView view, String version, @Nullable Instant computedAt) {}

  /** One evidence item with its correlated artifact keys (transitions attached by the service). */
  public record EvidenceItemRow(
      UUID workItemId,
      @Nullable String workItemKey,
      String title,
      String type,
      String status,
      long cycleTimeSec,
      long activeSec,
      long blockedSec,
      long reviewWaitSec,
      long waitingSec,
      int reworkCount,
      @Nullable String pullRequestKey,
      @Nullable String buildKey,
      @Nullable String buildStatus,
      @Nullable String qualityGateKey,
      @Nullable String qualityGateStatus) {}

  /** One evidence transition tagged with its work item. */
  public record EvidenceTransitionRow(UUID workItemId, TransitionEvidenceView transition) {}

  /**
   * Reads the friction metric definition, if registered.
   *
   * @return the definition view
   */
  public Optional<FrictionMetricView> definition() {
    return jdbc.sql(
            """
            SELECT metric_key, name, purpose, formula, inputs, grain, caveats, gaming_risks
            FROM analytics.metric_definition WHERE metric_key = :key
            ORDER BY active_version DESC LIMIT 1
            """)
        .param("key", FrictionDefinition.METRIC_KEY)
        .query(
            (rs, rowNum) ->
                new FrictionMetricView(
                    rs.getString("metric_key"),
                    rs.getString("name"),
                    rs.getString("purpose"),
                    rs.getString("formula"),
                    parseInputs(rs.getString("inputs")),
                    rs.getString("grain"),
                    rs.getString("caveats"),
                    rs.getString("gaming_risks")))
        .optional();
  }

  /**
   * Reads every team's computed friction row.
   *
   * @return the unsorted summary rows
   */
  public List<SummaryRow> summaryRows() {
    return jdbc.sql(
            """
            SELECT t.id AS team_id, t.name AS team_name, f.metric_version, f.friction_score,
                   f.dominant_cause, f.work_items, f.total_cycle_sec, f.active_sec, f.waiting_sec,
                   f.blocked_sec, f.review_wait_sec, f.rework_count, f.flow_efficiency,
                   f.blocked_ratio, f.review_wait_ratio, f.computed_at
            FROM analytics.rm_team_friction_current f
            JOIN core.team t ON t.id = f.team_id AND t.deleted_at IS NULL
            """)
        .query(
            (rs, rowNum) ->
                new SummaryRow(
                    new TeamFrictionView(
                        rs.getObject("team_id", UUID.class),
                        rs.getString("team_name"),
                        rs.getInt("friction_score"),
                        rs.getString("dominant_cause"),
                        rs.getInt("work_items"),
                        rs.getLong("total_cycle_sec"),
                        rs.getLong("active_sec"),
                        rs.getLong("waiting_sec"),
                        rs.getLong("blocked_sec"),
                        rs.getLong("review_wait_sec"),
                        rs.getInt("rework_count"),
                        pct(rs.getDouble("flow_efficiency")),
                        pct(rs.getDouble("blocked_ratio")),
                        pct(rs.getDouble("review_wait_ratio"))),
                    rs.getString("metric_version"),
                    toInstant(rs.getTimestamp("computed_at"))))
        .list();
  }

  /**
   * Reads a team's display name.
   *
   * @param teamId the team
   * @return the name, or empty if not visible under the current tenant
   */
  public Optional<String> teamName(UUID teamId) {
    return jdbc.sql("SELECT name FROM core.team WHERE id = :id AND deleted_at IS NULL")
        .param("id", teamId)
        .query(String.class)
        .optional();
  }

  /**
   * Reads a team's evidence items with their correlated artifacts in one query.
   *
   * @param teamId the team
   * @return the items ordered by work-item key
   */
  public List<EvidenceItemRow> evidenceItems(UUID teamId) {
    return jdbc.sql(
            """
            SELECT fc.work_item_id, er.external_key AS work_item_key, wi.title, wi.type, wi.status,
                   fc.cycle_time_sec, fc.active_sec, fc.blocked_sec, fc.review_wait_sec,
                   fc.waiting_sec, fc.rework_count, pr.source_key AS pr_key,
                   b.source_key AS build_key, b.status AS build_status, qg.source_key AS gate_key,
                   qg.status AS gate_status
            FROM analytics.flow_correlation fc
            JOIN work.work_item wi ON wi.id = fc.work_item_id
            LEFT JOIN core.external_ref er
              ON er.entity_type = 'WORK_ITEM' AND er.entity_id = fc.work_item_id
            LEFT JOIN scm.pull_request pr ON pr.id = fc.pull_request_id
            LEFT JOIN cicd.build b ON b.id = fc.build_id
            LEFT JOIN quality.quality_gate qg ON qg.id = fc.quality_gate_id
            WHERE fc.team_id = :teamId
            ORDER BY er.external_key
            """)
        .param("teamId", teamId)
        .query(
            (rs, rowNum) ->
                new EvidenceItemRow(
                    rs.getObject("work_item_id", UUID.class),
                    rs.getString("work_item_key"),
                    rs.getString("title"),
                    rs.getString("type"),
                    rs.getString("status"),
                    rs.getLong("cycle_time_sec"),
                    rs.getLong("active_sec"),
                    rs.getLong("blocked_sec"),
                    rs.getLong("review_wait_sec"),
                    rs.getLong("waiting_sec"),
                    rs.getInt("rework_count"),
                    rs.getString("pr_key"),
                    rs.getString("build_key"),
                    rs.getString("build_status"),
                    rs.getString("gate_key"),
                    rs.getString("gate_status")))
        .list();
  }

  /**
   * Reads every transition of a team's correlated items in one joined query.
   *
   * @param teamId the team
   * @return the transitions ordered by work item then sequence
   */
  public List<EvidenceTransitionRow> evidenceTransitions(UUID teamId) {
    return jdbc.sql(
            """
            SELECT t.work_item_id, t.seq, t.from_state, t.to_state,
                   extract(epoch from t.occurred_at)::bigint AS at_sec
            FROM work.work_item_transition t
            JOIN analytics.flow_correlation fc ON fc.work_item_id = t.work_item_id
            WHERE fc.team_id = :teamId
            ORDER BY t.work_item_id, t.seq
            """)
        .param("teamId", teamId)
        .query(
            (rs, rowNum) ->
                new EvidenceTransitionRow(
                    rs.getObject("work_item_id", UUID.class),
                    new TransitionEvidenceView(
                        rs.getInt("seq"),
                        rs.getString("from_state"),
                        rs.getString("to_state"),
                        rs.getLong("at_sec"))))
        .list();
  }

  /** Parses the {@code inputs} jsonb column into a JSON node (jsonb is always valid JSON). */
  private JsonNode parseInputs(@Nullable String inputsJson) {
    try {
      return mapper.readTree(inputsJson == null ? "{}" : inputsJson);
    } catch (JsonProcessingException e) {
      return mapper.createObjectNode();
    }
  }

  private static int pct(double ratio) {
    return (int) Math.round(ratio * 100.0);
  }

  private static @Nullable Instant toInstant(@Nullable Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
