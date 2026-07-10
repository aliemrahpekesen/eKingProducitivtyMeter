/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.eip.app.tenant.TenantScopedJdbc;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Assembles the Engineering Friction summary from the RLS-protected <em>computed</em> read model
 * ({@code analytics.rm_team_friction_current}), which the friction pipeline populates from
 * ingested, normalized, and correlated flow data — not from seed-only tables. Reads the metric
 * definition and the per-team computed friction in one tenant-scoped transaction and orders teams
 * worst-first. Team-level only — no query touches member/individual data (Law 6 / NFR-071).
 */
@Component
public class FrictionSummaryService {

  static final String METRIC_KEY = "engineering_friction";

  private static final ObjectMapper JSON = new ObjectMapper();

  /** Worst-first, then a stable tie-break by name then id — a deterministic total order. */
  private static final Comparator<TeamFrictionView> WORST_FIRST =
      Comparator.comparingInt(TeamFrictionView::frictionScore)
          .reversed()
          .thenComparing(TeamFrictionView::teamName)
          .thenComparing(t -> t.teamId().toString());

  private final TenantScopedJdbc jdbc;

  public FrictionSummaryService(TenantScopedJdbc jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Builds the friction summary for the current tenant.
   *
   * @return the metric definition (if registered), version/timestamp, and the worst-first per-team
   *     computed breakdown
   */
  public FrictionSummaryView summary() {
    return jdbc.read(
        client -> {
          Optional<FrictionMetricView> metric = readDefinition(client);
          List<Row> rows = readTeamFriction(client);
          List<TeamFrictionView> teams = rows.stream().map(Row::view).sorted(WORST_FIRST).toList();
          String version = rows.stream().map(Row::version).findFirst().orElse(null);
          String computedAt =
              rows.stream()
                  .map(Row::computedAt)
                  .filter(java.util.Objects::nonNull)
                  .max(Comparator.naturalOrder())
                  .map(Instant::toString)
                  .orElse(null);
          return new FrictionSummaryView(
              metric.orElse(null), version, computedAt, true, teams, teams.size());
        });
  }

  private static Optional<FrictionMetricView> readDefinition(JdbcClient client) {
    return client
        .sql(
            "SELECT metric_key, name, purpose, formula, inputs, grain, caveats, gaming_risks "
                + "FROM analytics.metric_definition WHERE metric_key = :key "
                + "ORDER BY active_version DESC LIMIT 1")
        .param("key", METRIC_KEY)
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

  /** Parses the {@code inputs} jsonb column (NOT NULL DEFAULT {@code '{}'}) into a JSON node. */
  private static JsonNode parseInputs(@Nullable String inputsJson) {
    try {
      return JSON.readTree(inputsJson == null ? "{}" : inputsJson);
    } catch (JsonProcessingException e) {
      // The column is jsonb, so stored values are always valid JSON; treat a parse failure as
      // empty.
      return JSON.createObjectNode();
    }
  }

  private static List<Row> readTeamFriction(JdbcClient client) {
    return client
        .sql(
            "SELECT t.id AS team_id, t.name AS team_name, f.metric_version, f.friction_score, "
                + "f.dominant_cause, f.work_items, f.total_cycle_sec, f.active_sec, f.waiting_sec, "
                + "f.blocked_sec, f.review_wait_sec, f.rework_count, f.flow_efficiency, "
                + "f.blocked_ratio, f.review_wait_ratio, f.computed_at "
                + "FROM analytics.rm_team_friction_current f "
                + "JOIN core.team t ON t.id = f.team_id AND t.deleted_at IS NULL")
        .query(
            (rs, rowNum) ->
                new Row(
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

  private static int pct(double ratio) {
    return (int) Math.round(ratio * 100.0);
  }

  private static @Nullable Instant toInstant(@Nullable Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }

  /** Internal carrier so the version/timestamp travel alongside each team view. */
  private record Row(TeamFrictionView view, String version, @Nullable Instant computedAt) {}
}
