/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.persistence;

import com.eip.analytics.friction.FlowMetrics;
import com.eip.analytics.friction.FrictionDefinition;
import com.eip.analytics.friction.TeamFriction;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Friction compute-side adapter: bounded set-based loads of the correlated canonical flow (one
 * query per entity kind, independent of team/item count — no N+1) and batch upserts of the
 * projection (correlation evidence, read model, {@code metric_fact}, versioned definition).
 * Canonical schemas are read-only here (ADR-019).
 */
@Repository
public class FrictionProjectionRepository {

  private final JdbcClient jdbc;
  private final JdbcTemplate jdbcTemplate;

  public FrictionProjectionRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
    this.jdbc = jdbc;
    this.jdbcTemplate = jdbcTemplate;
  }

  /** One resolved, team-assigned work item. */
  public record ResolvedItem(UUID id, UUID teamId, long createdSec, long resolvedSec) {}

  /** One state transition of a resolved work item, ordered by {@code (workItemId, seq)}. */
  public record TransitionRow(
      UUID workItemId, @Nullable String fromState, String toState, long atSec) {}

  /** One correlation-evidence upsert. */
  public record CorrelationUpsert(
      UUID teamId,
      UUID workItemId,
      @Nullable UUID pullRequestId,
      @Nullable UUID buildId,
      @Nullable UUID qualityGateId,
      FlowMetrics metrics) {}

  /**
   * Loads all resolved, team-assigned work items in deterministic order.
   *
   * @return the items ordered by team then id
   */
  public List<ResolvedItem> resolvedItems() {
    return jdbc.sql(
            """
            SELECT id, team_id, extract(epoch from created_in_source)::bigint,
                   extract(epoch from resolved_at)::bigint
            FROM work.work_item
            WHERE team_id IS NOT NULL AND resolved_at IS NOT NULL AND deleted_at IS NULL
            ORDER BY team_id, id
            """)
        .query(
            (rs, rowNum) ->
                new ResolvedItem(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getLong(3),
                    rs.getLong(4)))
        .list();
  }

  /**
   * Loads every transition of the resolved items in one query.
   *
   * @return the transitions ordered by work item then sequence
   */
  public List<TransitionRow> transitionsForResolvedItems() {
    return jdbc.sql(
            """
            SELECT t.work_item_id, t.from_state, t.to_state,
                   extract(epoch from t.occurred_at)::bigint
            FROM work.work_item_transition t
            JOIN work.work_item wi ON wi.id = t.work_item_id
            WHERE wi.team_id IS NOT NULL AND wi.resolved_at IS NOT NULL AND wi.deleted_at IS NULL
            ORDER BY t.work_item_id, t.seq
            """)
        .query(
            (rs, rowNum) ->
                new TransitionRow(
                    rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getLong(4)))
        .list();
  }

  /**
   * Maps each work item to its first pull request (deterministic {@code DISTINCT ON} pick).
   *
   * @return pull-request ids by work-item id
   */
  public Map<UUID, UUID> firstPullRequestByWorkItem() {
    return uuidMap(
        """
        SELECT DISTINCT ON (work_item_id) work_item_id, id FROM scm.pull_request
        WHERE work_item_id IS NOT NULL ORDER BY work_item_id, id
        """);
  }

  /**
   * Maps each pull request to its first build.
   *
   * @return build ids by pull-request id
   */
  public Map<UUID, UUID> firstBuildByPullRequest() {
    return uuidMap(
        """
        SELECT DISTINCT ON (pull_request_id) pull_request_id, id FROM cicd.build
        WHERE pull_request_id IS NOT NULL ORDER BY pull_request_id, id
        """);
  }

  /**
   * Maps each build to its first quality gate.
   *
   * @return quality-gate ids by build id
   */
  public Map<UUID, UUID> firstGateByBuild() {
    return uuidMap(
        """
        SELECT DISTINCT ON (build_id) build_id, id FROM quality.quality_gate
        WHERE build_id IS NOT NULL ORDER BY build_id, id
        """);
  }

  /**
   * Returns the deterministic metric bucket instant: the latest source resolution time.
   *
   * @return the bucket timestamp, or empty when nothing is resolved yet
   */
  public Optional<Timestamp> latestResolvedAt() {
    return jdbc.sql("SELECT max(resolved_at) FROM work.work_item WHERE resolved_at IS NOT NULL")
        .query(Timestamp.class)
        .optional();
  }

  /**
   * Upserts the versioned Engineering Friction definition and returns its id.
   *
   * @return the {@code analytics.metric_definition.id}
   */
  public UUID upsertDefinition() {
    Optional<UUID> existing =
        jdbc.sql("SELECT id FROM analytics.metric_definition WHERE metric_key = :key")
            .param("key", FrictionDefinition.METRIC_KEY)
            .query(UUID.class)
            .optional();
    if (existing.isPresent()) {
      jdbc.sql(
              """
              UPDATE analytics.metric_definition SET name = :name, purpose = :purpose,
                formula = :formula, inputs = :inputs::jsonb, grain = :grain, caveats = :caveats,
                gaming_risks = :gamingRisks, active_version = :version, updated_at = now()
              WHERE id = :id
              """)
          .param("name", FrictionDefinition.NAME)
          .param("purpose", FrictionDefinition.PURPOSE)
          .param("formula", FrictionDefinition.FORMULA)
          .param("inputs", FrictionDefinition.INPUTS_JSON)
          .param("grain", FrictionDefinition.GRAIN)
          .param("caveats", FrictionDefinition.CAVEATS)
          .param("gamingRisks", FrictionDefinition.GAMING_RISKS)
          .param("version", FrictionDefinition.DEFINITION_VERSION)
          .param("id", existing.get())
          .update();
      return existing.get();
    }
    return jdbc.sql(
            """
            INSERT INTO analytics.metric_definition (tenant_id, metric_key, name, purpose, formula,
              inputs, grain, caveats, gaming_risks, active_version)
            VALUES (current_setting('app.tenant_id')::uuid, :key, :name, :purpose, :formula,
              :inputs::jsonb, :grain, :caveats, :gamingRisks, :version)
            RETURNING id
            """)
        .param("key", FrictionDefinition.METRIC_KEY)
        .param("name", FrictionDefinition.NAME)
        .param("purpose", FrictionDefinition.PURPOSE)
        .param("formula", FrictionDefinition.FORMULA)
        .param("inputs", FrictionDefinition.INPUTS_JSON)
        .param("grain", FrictionDefinition.GRAIN)
        .param("caveats", FrictionDefinition.CAVEATS)
        .param("gamingRisks", FrictionDefinition.GAMING_RISKS)
        .param("version", FrictionDefinition.DEFINITION_VERSION)
        .query(UUID.class)
        .single();
  }

  /**
   * Batch-upserts correlation-evidence rows on {@code (tenant, work item)}.
   *
   * @param rows the correlation rows
   */
  public void upsertCorrelations(List<CorrelationUpsert> rows) {
    jdbcTemplate.batchUpdate(
        """
        INSERT INTO analytics.flow_correlation (tenant_id, team_id, work_item_id, pull_request_id,
          build_id, quality_gate_id, cycle_time_sec, active_sec, blocked_sec, review_wait_sec,
          waiting_sec, rework_count)
        VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (tenant_id, work_item_id) DO UPDATE SET team_id = EXCLUDED.team_id,
          pull_request_id = EXCLUDED.pull_request_id, build_id = EXCLUDED.build_id,
          quality_gate_id = EXCLUDED.quality_gate_id, cycle_time_sec = EXCLUDED.cycle_time_sec,
          active_sec = EXCLUDED.active_sec, blocked_sec = EXCLUDED.blocked_sec,
          review_wait_sec = EXCLUDED.review_wait_sec, waiting_sec = EXCLUDED.waiting_sec,
          rework_count = EXCLUDED.rework_count, computed_at = now()
        """,
        rows,
        rows.size(),
        (ps, r) -> {
          ps.setObject(1, r.teamId());
          ps.setObject(2, r.workItemId());
          setUuidOrNull(ps, 3, r.pullRequestId());
          setUuidOrNull(ps, 4, r.buildId());
          setUuidOrNull(ps, 5, r.qualityGateId());
          ps.setLong(6, r.metrics().cycleSec());
          ps.setLong(7, r.metrics().activeSec());
          ps.setLong(8, r.metrics().blockedSec());
          ps.setLong(9, r.metrics().reviewWaitSec());
          ps.setLong(10, r.metrics().waitingSec());
          ps.setInt(11, r.metrics().reworkCount());
        });
  }

  /**
   * Batch-upserts the per-team friction read model on {@code (tenant, team, version)}.
   *
   * @param teams the computed team rows
   */
  public void upsertReadModel(List<TeamFriction> teams) {
    jdbcTemplate.batchUpdate(
        """
        INSERT INTO analytics.rm_team_friction_current (tenant_id, team_id, metric_version,
          work_items, total_cycle_sec, active_sec, waiting_sec, blocked_sec, review_wait_sec,
          rework_count, flow_efficiency, blocked_ratio, review_wait_ratio, friction_score,
          dominant_cause, computed_at)
        VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                now())
        ON CONFLICT (tenant_id, team_id, metric_version) DO UPDATE SET
          work_items = EXCLUDED.work_items, total_cycle_sec = EXCLUDED.total_cycle_sec,
          active_sec = EXCLUDED.active_sec, waiting_sec = EXCLUDED.waiting_sec,
          blocked_sec = EXCLUDED.blocked_sec, review_wait_sec = EXCLUDED.review_wait_sec,
          rework_count = EXCLUDED.rework_count, flow_efficiency = EXCLUDED.flow_efficiency,
          blocked_ratio = EXCLUDED.blocked_ratio, review_wait_ratio = EXCLUDED.review_wait_ratio,
          friction_score = EXCLUDED.friction_score, dominant_cause = EXCLUDED.dominant_cause,
          computed_at = now()
        """,
        teams,
        teams.size(),
        (ps, t) -> {
          ps.setObject(1, t.teamId());
          ps.setString(2, FrictionDefinition.VERSION);
          ps.setInt(3, t.workItems());
          ps.setLong(4, t.totalCycleSec());
          ps.setLong(5, t.activeSec());
          ps.setLong(6, t.waitingSec());
          ps.setLong(7, t.blockedSec());
          ps.setLong(8, t.reviewWaitSec());
          ps.setInt(9, t.reworkCount());
          ps.setDouble(10, t.flowEfficiency());
          ps.setDouble(11, t.blockedRatio());
          ps.setDouble(12, t.reviewWaitRatio());
          ps.setInt(13, t.frictionScore());
          ps.setString(14, t.dominantCause());
        });
  }

  /**
   * Batch-upserts one {@code metric_fact} time point per team.
   *
   * @param metricId the metric definition id
   * @param bucketAt the deterministic bucket instant
   * @param teams the computed team rows
   */
  public void upsertFacts(UUID metricId, Timestamp bucketAt, List<TeamFriction> teams) {
    jdbcTemplate.batchUpdate(
        """
        INSERT INTO analytics.metric_fact (tenant_id, metric_id, definition_version, team_id,
          grain, bucket_at, value)
        VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?, 'team', ?, ?)
        ON CONFLICT (tenant_id, metric_id, definition_version, team_id, grain, bucket_at)
          DO UPDATE SET value = EXCLUDED.value, computed_at = now()
        """,
        teams,
        teams.size(),
        (ps, t) -> {
          ps.setObject(1, metricId);
          ps.setInt(2, FrictionDefinition.DEFINITION_VERSION);
          ps.setObject(3, t.teamId());
          ps.setTimestamp(4, bucketAt);
          ps.setBigDecimal(5, BigDecimal.valueOf(t.frictionScore()));
        });
  }

  private Map<UUID, UUID> uuidMap(String sql) {
    Map<UUID, UUID> map = new HashMap<>();
    jdbc.sql(sql)
        .query(
            (rs, rowNum) -> {
              map.put(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class));
              return Boolean.TRUE;
            })
        .list();
    return map;
  }

  private static void setUuidOrNull(PreparedStatement ps, int index, @Nullable UUID value)
      throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.OTHER);
    } else {
      ps.setObject(index, value);
    }
  }
}
