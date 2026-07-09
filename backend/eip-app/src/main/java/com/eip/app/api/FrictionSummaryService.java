/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.eip.app.tenant.TenantScopedJdbc;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Assembles the Engineering Friction summary from the RLS-protected read model. Reads the metric
 * definition and the per-team flow signals in a single tenant-scoped transaction, derives each
 * team's deterministic {@link FrictionScore}, and orders the teams worst-first. Team-level only —
 * no query touches member/individual data (Law 6 / NFR-071).
 */
@Component
public class FrictionSummaryService {

  static final String METRIC_KEY = "engineering_friction";

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
   * @return the metric definition (if registered) plus the worst-first per-team breakdown
   */
  public FrictionSummaryView summary() {
    return jdbc.read(
        client -> {
          Optional<FrictionMetricView> metric = readDefinition(client);
          List<TeamFrictionView> teams =
              readTeamFriction(client).stream().sorted(WORST_FIRST).toList();
          return new FrictionSummaryView(metric.orElse(null), teams, teams.size());
        });
  }

  private static Optional<FrictionMetricView> readDefinition(JdbcClient client) {
    return client
        .sql(
            "SELECT metric_key, name, purpose, formula, grain, caveats, gaming_risks "
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
                    rs.getString("grain"),
                    rs.getString("caveats"),
                    rs.getString("gaming_risks")))
        .optional();
  }

  private static List<TeamFrictionView> readTeamFriction(JdbcClient client) {
    return client
        .sql(
            "SELECT t.id AS team_id, t.name AS team_name, f.wip, f.wip_limit_breaches, "
                + "f.oldest_in_progress_age_sec, f.review_queue_depth "
                + "FROM analytics.rm_team_flow_current f "
                + "JOIN core.team t ON t.id = f.team_id AND t.deleted_at IS NULL")
        .query(
            (rs, rowNum) -> {
              int wipLimitBreaches = rs.getInt("wip_limit_breaches");
              long oldestAgeSec = rs.getLong("oldest_in_progress_age_sec");
              int reviewQueueDepth = rs.getInt("review_queue_depth");
              return new TeamFrictionView(
                  rs.getObject("team_id", UUID.class),
                  rs.getString("team_name"),
                  rs.getInt("wip"),
                  wipLimitBreaches,
                  oldestAgeSec,
                  reviewQueueDepth,
                  FrictionScore.of(wipLimitBreaches, oldestAgeSec, reviewQueueDepth));
            })
        .list();
  }
}
