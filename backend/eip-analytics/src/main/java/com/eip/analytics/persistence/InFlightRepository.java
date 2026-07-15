/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * In-flight (unresolved) work read-side adapter. Two bounded queries: the per-item view (one join,
 * grouped by team in the application layer) and the per-team aggregate (count + oldest age) used by
 * the recommendation engine — never per-row loops. {@code ageSec} is the one sanctioned wall-clock
 * read in this API surface (an operational, not a historical, view).
 */
@Repository
public class InFlightRepository {

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc the tenant-bound JDBC client
   */
  public InFlightRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** One unresolved item's team, display fields, and live age. */
  public record ItemRow(
      UUID teamId,
      String teamName,
      String workItemKey,
      String title,
      String state,
      long ageSec,
      boolean blocked) {}

  /** One team's in-flight aggregate: how many items are open, and the oldest one's age. */
  public record TeamAggregateRow(UUID teamId, int count, long maxAgeSec) {}

  /**
   * Reads every unresolved, team-assigned item with its live age, in one bounded join.
   *
   * <p>See {@link com.eip.analytics.persistence.FrictionReadRepository#evidenceItems} for why the
   * {@code core.external_ref} join target is pre-deduplicated with {@code DISTINCT ON (entity_id)}
   * (DEBT-020 item 2): the same fan-out risk applies to any join on {@code entity_id} alone.
   *
   * @return the rows, ordered by team then age descending
   */
  public List<ItemRow> items() {
    return jdbc.sql(
            """
            SELECT wi.team_id, t.name AS team_name,
                   coalesce(nullif(er.external_key, ''), nullif(wi.title, ''), '—') AS work_item_key,
                   wi.title, wi.status,
                   extract(epoch from (now() - wi.created_in_source))::bigint AS age_sec,
                   (wi.status = 'BLOCKED') AS blocked
            FROM work.work_item wi
            JOIN core.team t ON t.id = wi.team_id AND t.deleted_at IS NULL
            LEFT JOIN (
              SELECT DISTINCT ON (entity_id) entity_id, external_key
              FROM core.external_ref
              WHERE entity_type = 'WORK_ITEM'
              ORDER BY entity_id, source_system, source_instance, external_id
            ) er ON er.entity_id = wi.id
            WHERE wi.resolved_at IS NULL AND wi.deleted_at IS NULL AND wi.team_id IS NOT NULL
            ORDER BY wi.team_id, age_sec DESC, wi.id
            """)
        .query(
            (rs, rowNum) ->
                new ItemRow(
                    rs.getObject("team_id", UUID.class),
                    rs.getString("team_name"),
                    rs.getString("work_item_key"),
                    rs.getString("title"),
                    rs.getString("status"),
                    rs.getLong("age_sec"),
                    rs.getBoolean("blocked")))
        .list();
  }

  /**
   * Reads each team's in-flight count and oldest age, in one bounded aggregate.
   *
   * @return the per-team aggregates
   */
  public List<TeamAggregateRow> aggregates() {
    return jdbc.sql(
            """
            SELECT wi.team_id, count(*) AS cnt,
                   max(extract(epoch from (now() - wi.created_in_source)))::bigint AS max_age_sec
            FROM work.work_item wi
            WHERE wi.resolved_at IS NULL AND wi.deleted_at IS NULL AND wi.team_id IS NOT NULL
            GROUP BY wi.team_id
            """)
        .query(
            (rs, rowNum) ->
                new TeamAggregateRow(
                    rs.getObject("team_id", UUID.class),
                    rs.getInt("cnt"),
                    rs.getLong("max_age_sec")))
        .list();
  }
}
