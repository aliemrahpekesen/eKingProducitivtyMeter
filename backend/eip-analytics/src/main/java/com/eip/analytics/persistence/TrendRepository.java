/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.persistence;

import com.eip.analytics.friction.FlowMetrics;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Metric-trends read-side adapter. Two bounded queries: the tenant's data-anchor (a scalar), and
 * the resolved-item rows within the trends window (one join, aggregated per team/week in the
 * application layer) — never per-row loops. Week boundaries are computed at UTC explicitly ({@code
 * AT TIME ZONE 'UTC'}) so bucketing never depends on the database session's timezone.
 */
@Repository
public class TrendRepository {

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc the tenant-bound JDBC client
   */
  public TrendRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** One resolved item's team, UTC week bucket, and cycle-time decomposition. */
  public record TrendRow(UUID teamId, LocalDate weekStart, FlowMetrics metrics) {}

  /**
   * Reads the tenant's latest resolved-item timestamp — the deterministic anchor the trends window
   * is built from (data, never wall-clock).
   *
   * @return the anchor instant, or empty when the tenant has no resolved, team-assigned work item
   */
  public Optional<Instant> anchor() {
    return jdbc.sql(
            """
            SELECT max(resolved_at) FROM work.work_item
            WHERE resolved_at IS NOT NULL AND deleted_at IS NULL AND team_id IS NOT NULL
            """)
        .query(Timestamp.class)
        .optional()
        .map(Timestamp::toInstant);
  }

  /**
   * Reads every resolved item's team, UTC week bucket, and cycle-time decomposition on or after the
   * window start, in one bounded join.
   *
   * @param windowStart the inclusive window start
   * @return the rows, ordered by team then week
   */
  public List<TrendRow> rows(Instant windowStart) {
    return jdbc.sql(
            """
            SELECT wi.team_id,
                   date_trunc('week', wi.resolved_at AT TIME ZONE 'UTC')::date AS week_start,
                   fc.cycle_time_sec, fc.active_sec, fc.blocked_sec, fc.review_wait_sec,
                   fc.waiting_sec, fc.rework_count
            FROM analytics.flow_correlation fc
            JOIN work.work_item wi ON wi.id = fc.work_item_id
            WHERE wi.resolved_at IS NOT NULL AND wi.deleted_at IS NULL AND wi.team_id IS NOT NULL
              AND wi.resolved_at >= :windowStart
            ORDER BY wi.team_id, week_start
            """)
        .param("windowStart", Timestamp.from(windowStart))
        .query(
            (rs, rowNum) ->
                new TrendRow(
                    rs.getObject("team_id", UUID.class),
                    rs.getObject("week_start", LocalDate.class),
                    new FlowMetrics(
                        rs.getLong("cycle_time_sec"),
                        rs.getLong("active_sec"),
                        rs.getLong("blocked_sec"),
                        rs.getLong("review_wait_sec"),
                        rs.getLong("waiting_sec"),
                        rs.getInt("rework_count"))))
        .list();
  }

  /**
   * Reads every team's display name, for enriching the trend rows after Java-side aggregation.
   *
   * @return team names by id
   */
  public Map<UUID, String> teamNames() {
    Map<UUID, String> names = new HashMap<>();
    jdbc.sql("SELECT id, name FROM core.team WHERE deleted_at IS NULL")
        .query(
            (rs, rowNum) -> {
              names.put(rs.getObject("id", UUID.class), rs.getString("name"));
              return Boolean.TRUE;
            })
        .list();
    return names;
  }
}
