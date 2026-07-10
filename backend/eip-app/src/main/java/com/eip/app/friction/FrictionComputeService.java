/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.friction;

import com.eip.analytics.friction.FlowMetrics;
import com.eip.analytics.friction.FlowTimeline;
import com.eip.analytics.friction.FrictionCalculator;
import com.eip.analytics.friction.FrictionDefinition;
import com.eip.analytics.friction.TeamFriction;
import com.eip.analytics.friction.TimelineStage;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Correlates the canonical flow and computes Engineering Friction v0.1 on a tenant-bound {@link
 * Connection} (TASK-0016 INC-2). For each team it decomposes every work item's transition timeline
 * ({@link FlowTimeline}), writes one correlation-evidence row (work → PR → build → gate + the
 * decomposition) to {@code analytics.flow_correlation}, aggregates to the team composite ({@link
 * FrictionCalculator}), and persists the versioned definition, a {@code metric_fact} time point,
 * and the {@code rm_team_friction_current} read model. Deterministic and idempotent — recomputing
 * over the same canonical data overwrites in place with identical values.
 */
final class FrictionComputeService {

  /** The outcome of a compute run. */
  record Result(int teamsComputed, int itemsCorrelated) {}

  Result compute(Connection c) throws SQLException {
    UUID metricId = upsertDefinition(c);
    Timestamp bucketAt = latestResolvedAt(c);
    if (bucketAt == null) {
      return new Result(0, 0); // no resolved work items yet
    }
    int teams = 0;
    int items = 0;
    for (UUID teamId : teamsWithWork(c)) {
      List<FlowMetrics> perItem = new ArrayList<>();
      for (WorkItem item : workItems(c, teamId)) {
        FlowMetrics metrics = FlowTimeline.of(stagesOf(c, item));
        UUID prId = firstId(c, "SELECT id FROM scm.pull_request WHERE work_item_id = ?", item.id());
        UUID buildId =
            prId == null
                ? null
                : firstId(c, "SELECT id FROM cicd.build WHERE pull_request_id = ?", prId);
        UUID gateId =
            buildId == null
                ? null
                : firstId(c, "SELECT id FROM quality.quality_gate WHERE build_id = ?", buildId);
        upsertCorrelation(c, teamId, item.id(), prId, buildId, gateId, metrics);
        perItem.add(metrics);
        items++;
      }
      TeamFriction friction = FrictionCalculator.of(teamId, perItem);
      upsertReadModel(c, friction);
      upsertFact(c, metricId, teamId, bucketAt, friction.frictionScore());
      teams++;
    }
    return new Result(teams, items);
  }

  private List<TimelineStage> stagesOf(Connection c, WorkItem item) throws SQLException {
    List<TimelineStage> stages = new ArrayList<>();
    boolean first = true;
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT from_state, to_state, extract(epoch from occurred_at)::bigint"
                + " FROM work.work_item_transition WHERE work_item_id = ? ORDER BY seq")) {
      ps.setObject(1, item.id());
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String fromState = rs.getString(1);
          String toState = rs.getString(2);
          long atSec = rs.getLong(3);
          if (first) {
            stages.add(
                new TimelineStage(fromState == null ? "TODO" : fromState, item.createdSec()));
            first = false;
          }
          stages.add(new TimelineStage(toState, atSec));
        }
      }
    }
    if (stages.isEmpty()) {
      stages.add(new TimelineStage("TODO", item.createdSec()));
      stages.add(new TimelineStage("DONE", item.resolvedSec()));
    }
    return stages;
  }

  private UUID upsertDefinition(Connection c) throws SQLException {
    try (PreparedStatement select =
        c.prepareStatement("SELECT id FROM analytics.metric_definition WHERE metric_key = ?")) {
      select.setString(1, FrictionDefinition.METRIC_KEY);
      try (ResultSet rs = select.executeQuery()) {
        if (rs.next()) {
          UUID id = rs.getObject(1, UUID.class);
          try (PreparedStatement update =
              c.prepareStatement(
                  "UPDATE analytics.metric_definition SET name = ?, purpose = ?, formula = ?,"
                      + " inputs = ?::jsonb, grain = ?, caveats = ?, gaming_risks = ?,"
                      + " active_version = ?, updated_at = now() WHERE id = ?")) {
            bindDefinition(update);
            update.setObject(9, id);
            update.executeUpdate();
          }
          return id;
        }
      }
    }
    UUID id = UUID.randomUUID();
    try (PreparedStatement insert =
        c.prepareStatement(
            "INSERT INTO analytics.metric_definition (id, tenant_id, metric_key, name, purpose,"
                + " formula, inputs, grain, caveats, gaming_risks, active_version) VALUES (?,"
                + " current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)")) {
      insert.setObject(1, id);
      insert.setString(2, FrictionDefinition.METRIC_KEY);
      bindDefinition2(insert);
      insert.executeUpdate();
    }
    return id;
  }

  private void bindDefinition(PreparedStatement ps) throws SQLException {
    ps.setString(1, FrictionDefinition.NAME);
    ps.setString(2, FrictionDefinition.PURPOSE);
    ps.setString(3, FrictionDefinition.FORMULA);
    ps.setString(4, FrictionDefinition.INPUTS_JSON);
    ps.setString(5, FrictionDefinition.GRAIN);
    ps.setString(6, FrictionDefinition.CAVEATS);
    ps.setString(7, FrictionDefinition.GAMING_RISKS);
    ps.setInt(8, FrictionDefinition.DEFINITION_VERSION);
  }

  private void bindDefinition2(PreparedStatement ps) throws SQLException {
    ps.setString(3, FrictionDefinition.NAME);
    ps.setString(4, FrictionDefinition.PURPOSE);
    ps.setString(5, FrictionDefinition.FORMULA);
    ps.setString(6, FrictionDefinition.INPUTS_JSON);
    ps.setString(7, FrictionDefinition.GRAIN);
    ps.setString(8, FrictionDefinition.CAVEATS);
    ps.setString(9, FrictionDefinition.GAMING_RISKS);
    ps.setInt(10, FrictionDefinition.DEFINITION_VERSION);
  }

  private void upsertCorrelation(
      Connection c,
      UUID teamId,
      UUID workItemId,
      @Nullable UUID prId,
      @Nullable UUID buildId,
      @Nullable UUID gateId,
      FlowMetrics m)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO analytics.flow_correlation (tenant_id, team_id, work_item_id,"
                + " pull_request_id, build_id, quality_gate_id, cycle_time_sec, active_sec,"
                + " blocked_sec, review_wait_sec, waiting_sec, rework_count) VALUES"
                + " (current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT (tenant_id, work_item_id) DO UPDATE SET team_id = EXCLUDED.team_id,"
                + " pull_request_id = EXCLUDED.pull_request_id, build_id = EXCLUDED.build_id,"
                + " quality_gate_id = EXCLUDED.quality_gate_id, cycle_time_sec = EXCLUDED.cycle_time_sec,"
                + " active_sec = EXCLUDED.active_sec, blocked_sec = EXCLUDED.blocked_sec,"
                + " review_wait_sec = EXCLUDED.review_wait_sec, waiting_sec = EXCLUDED.waiting_sec,"
                + " rework_count = EXCLUDED.rework_count, computed_at = now()")) {
      ps.setObject(1, teamId);
      ps.setObject(2, workItemId);
      setUuidOrNull(ps, 3, prId);
      setUuidOrNull(ps, 4, buildId);
      setUuidOrNull(ps, 5, gateId);
      ps.setLong(6, m.cycleSec());
      ps.setLong(7, m.activeSec());
      ps.setLong(8, m.blockedSec());
      ps.setLong(9, m.reviewWaitSec());
      ps.setLong(10, m.waitingSec());
      ps.setInt(11, m.reworkCount());
      ps.executeUpdate();
    }
  }

  private void upsertReadModel(Connection c, TeamFriction f) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO analytics.rm_team_friction_current (tenant_id, team_id, metric_version,"
                + " work_items, total_cycle_sec, active_sec, waiting_sec, blocked_sec,"
                + " review_wait_sec, rework_count, flow_efficiency, blocked_ratio, review_wait_ratio,"
                + " friction_score, dominant_cause, computed_at) VALUES"
                + " (current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " now()) ON CONFLICT (tenant_id, team_id, metric_version) DO UPDATE SET"
                + " work_items = EXCLUDED.work_items, total_cycle_sec = EXCLUDED.total_cycle_sec,"
                + " active_sec = EXCLUDED.active_sec, waiting_sec = EXCLUDED.waiting_sec,"
                + " blocked_sec = EXCLUDED.blocked_sec, review_wait_sec = EXCLUDED.review_wait_sec,"
                + " rework_count = EXCLUDED.rework_count, flow_efficiency = EXCLUDED.flow_efficiency,"
                + " blocked_ratio = EXCLUDED.blocked_ratio, review_wait_ratio = EXCLUDED.review_wait_ratio,"
                + " friction_score = EXCLUDED.friction_score, dominant_cause = EXCLUDED.dominant_cause,"
                + " computed_at = now()")) {
      ps.setObject(1, f.teamId());
      ps.setString(2, FrictionDefinition.VERSION);
      ps.setInt(3, f.workItems());
      ps.setLong(4, f.totalCycleSec());
      ps.setLong(5, f.activeSec());
      ps.setLong(6, f.waitingSec());
      ps.setLong(7, f.blockedSec());
      ps.setLong(8, f.reviewWaitSec());
      ps.setInt(9, f.reworkCount());
      ps.setDouble(10, f.flowEfficiency());
      ps.setDouble(11, f.blockedRatio());
      ps.setDouble(12, f.reviewWaitRatio());
      ps.setInt(13, f.frictionScore());
      ps.setString(14, f.dominantCause());
      ps.executeUpdate();
    }
  }

  private void upsertFact(Connection c, UUID metricId, UUID teamId, Timestamp bucketAt, int score)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO analytics.metric_fact (tenant_id, metric_id, definition_version, team_id,"
                + " grain, bucket_at, value) VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?,"
                + " 'team', ?, ?) ON CONFLICT (tenant_id, metric_id, definition_version, team_id,"
                + " grain, bucket_at) DO UPDATE SET value = EXCLUDED.value, computed_at = now()")) {
      ps.setObject(1, metricId);
      ps.setInt(2, FrictionDefinition.DEFINITION_VERSION);
      ps.setObject(3, teamId);
      ps.setTimestamp(4, bucketAt);
      ps.setBigDecimal(5, java.math.BigDecimal.valueOf(score));
      ps.executeUpdate();
    }
  }

  // --- reads ----------------------------------------------------------------

  private @Nullable Timestamp latestResolvedAt(Connection c) throws SQLException {
    try (PreparedStatement ps =
            c.prepareStatement(
                "SELECT max(resolved_at) FROM work.work_item WHERE resolved_at IS NOT NULL");
        ResultSet rs = ps.executeQuery()) {
      return rs.next() ? rs.getTimestamp(1) : null;
    }
  }

  private List<UUID> teamsWithWork(Connection c) throws SQLException {
    List<UUID> teams = new ArrayList<>();
    try (PreparedStatement ps =
            c.prepareStatement(
                "SELECT DISTINCT team_id FROM work.work_item WHERE team_id IS NOT NULL"
                    + " AND deleted_at IS NULL AND resolved_at IS NOT NULL");
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        teams.add(rs.getObject(1, UUID.class));
      }
    }
    return teams;
  }

  private List<WorkItem> workItems(Connection c, UUID teamId) throws SQLException {
    List<WorkItem> items = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, extract(epoch from created_in_source)::bigint,"
                + " extract(epoch from resolved_at)::bigint FROM work.work_item"
                + " WHERE team_id = ? AND resolved_at IS NOT NULL AND deleted_at IS NULL"
                + " ORDER BY id")) {
      ps.setObject(1, teamId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          items.add(new WorkItem(rs.getObject(1, UUID.class), rs.getLong(2), rs.getLong(3)));
        }
      }
    }
    return items;
  }

  private @Nullable UUID firstId(Connection c, String sql, UUID param) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql + " ORDER BY id LIMIT 1")) {
      ps.setObject(1, param);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getObject(1, UUID.class) : null;
      }
    }
  }

  private static void setUuidOrNull(PreparedStatement ps, int index, @Nullable UUID value)
      throws SQLException {
    if (value == null) {
      ps.setNull(index, java.sql.Types.OTHER);
    } else {
      ps.setObject(index, value);
    }
  }

  private record WorkItem(UUID id, long createdSec, long resolvedSec) {}
}
