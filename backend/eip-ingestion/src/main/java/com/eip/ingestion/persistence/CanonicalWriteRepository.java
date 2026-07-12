/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.persistence;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Canonical-model write adapter (ADR-019: eip-ingestion is the single canonical writer). All writes
 * are {@code JdbcTemplate} batch upserts on natural keys — set-based, so re-normalizing the same
 * staged data is idempotent and the statement count is independent of row count. External-ref
 * identity anchors resolve in three statements (select existing, batch-insert missing with {@code
 * ON CONFLICT DO NOTHING}, re-select), never per record.
 */
@Repository
public class CanonicalWriteRepository {

  private final JdbcClient jdbc;
  private final JdbcTemplate jdbcTemplate;

  public CanonicalWriteRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
    this.jdbc = jdbc;
    this.jdbcTemplate = jdbcTemplate;
  }

  /** An external-ref identity candidate for a work item. */
  public record ExternalRefCandidate(
      String sourceSystem, String sourceInstance, String externalId, String externalKey) {}

  /** One canonical work-item upsert row. */
  public record WorkItemRow(
      UUID id,
      String type,
      String title,
      UUID projectId,
      UUID stateId,
      String status,
      @Nullable UUID teamId,
      Timestamp createdInSource,
      Timestamp resolvedAt) {}

  /** One work-item state-transition upsert row. */
  public record TransitionRow(
      UUID workItemId, int seq, @Nullable String fromState, String toState, Timestamp occurredAt) {}

  /** One pull-request upsert row. */
  public record PullRequestRow(
      @Nullable UUID teamId,
      @Nullable UUID workItemId,
      String sourceKey,
      String title,
      String sourceBranch,
      String status,
      Timestamp createdInSource,
      Timestamp mergedAt) {}

  /** One code-review upsert row. */
  public record CodeReviewRow(
      UUID pullRequestId,
      String sourceKey,
      String outcome,
      Timestamp requestedAt,
      Timestamp completedAt) {}

  /** One build upsert row. */
  public record BuildRow(
      @Nullable UUID pullRequestId,
      String sourceKey,
      String status,
      Timestamp startedAt,
      Timestamp finishedAt) {}

  /** One quality-gate upsert row. */
  public record QualityGateRow(
      @Nullable UUID buildId,
      @Nullable UUID pullRequestId,
      String sourceKey,
      String status,
      Timestamp evaluatedAt) {}

  /**
   * Returns the tenant's active teams keyed by name.
   *
   * @return team ids by team name
   */
  public Map<String, UUID> teamIdsByName() {
    return keyedIds("SELECT name, id FROM core.team WHERE deleted_at IS NULL");
  }

  /**
   * Resolves stable work-item entity ids through {@code core.external_ref} (AD-14 identity
   * anchors), creating missing anchors in one batch.
   *
   * @param candidates the identities present in the staged data
   * @return entity ids keyed by immutable external id
   */
  public Map<String, UUID> resolveWorkItemIds(List<ExternalRefCandidate> candidates) {
    Map<String, UUID> existing = workItemRefIds();
    List<ExternalRefCandidate> missing =
        candidates.stream().filter(c -> !existing.containsKey(c.externalId())).toList();
    if (!missing.isEmpty()) {
      jdbcTemplate.batchUpdate(
          """
          INSERT INTO core.external_ref
            (tenant_id, entity_type, entity_id, source_system, source_instance, external_id,
             external_key, last_seen_at)
          VALUES (current_setting('app.tenant_id')::uuid, 'WORK_ITEM', ?, ?, ?, ?, ?, now())
          ON CONFLICT (tenant_id, source_system, source_instance, entity_type, external_id)
            DO NOTHING
          """,
          missing,
          missing.size(),
          (ps, c) -> {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, c.sourceSystem());
            ps.setString(3, c.sourceInstance());
            ps.setString(4, c.externalId());
            ps.setString(5, c.externalKey());
          });
    }
    return workItemRefIds();
  }

  private Map<String, UUID> workItemRefIds() {
    return keyedIds(
        "SELECT external_id, entity_id FROM core.external_ref WHERE entity_type = 'WORK_ITEM'");
  }

  /**
   * Batch-upserts canonical work items on their stable ids.
   *
   * @param rows the work-item rows
   */
  public void upsertWorkItems(List<WorkItemRow> rows) {
    jdbcTemplate.batchUpdate(
        """
        INSERT INTO work.work_item
          (id, tenant_id, type, title, project_id, current_state_id, status, team_id, blocked,
           created_in_source, resolved_at)
        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, false, ?, ?)
        ON CONFLICT (id) DO UPDATE SET
          type = EXCLUDED.type, title = EXCLUDED.title, status = EXCLUDED.status,
          team_id = EXCLUDED.team_id, resolved_at = EXCLUDED.resolved_at, updated_at = now()
        """,
        rows,
        rows.size(),
        (ps, r) -> {
          ps.setObject(1, r.id());
          ps.setString(2, r.type());
          ps.setString(3, r.title());
          ps.setObject(4, r.projectId());
          ps.setObject(5, r.stateId());
          ps.setString(6, r.status());
          setUuidOrNull(ps, 7, r.teamId());
          ps.setTimestamp(8, r.createdInSource());
          ps.setTimestamp(9, r.resolvedAt());
        });
  }

  /**
   * Batch-upserts work-item state transitions on {@code (tenant, work item, seq)}.
   *
   * @param rows the transition rows
   */
  public void upsertTransitions(List<TransitionRow> rows) {
    jdbcTemplate.batchUpdate(
        """
        INSERT INTO work.work_item_transition
          (tenant_id, work_item_id, seq, from_state, to_state, occurred_at)
        VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?)
        ON CONFLICT (tenant_id, work_item_id, seq) DO UPDATE SET
          from_state = EXCLUDED.from_state, to_state = EXCLUDED.to_state,
          occurred_at = EXCLUDED.occurred_at
        """,
        rows,
        rows.size(),
        (ps, r) -> {
          ps.setObject(1, r.workItemId());
          ps.setInt(2, r.seq());
          ps.setString(3, r.fromState());
          ps.setString(4, r.toState());
          ps.setTimestamp(5, r.occurredAt());
        });
  }

  /**
   * Batch-upserts pull requests on {@code (tenant, source_key)}.
   *
   * @param rows the pull-request rows
   */
  public void upsertPullRequests(List<PullRequestRow> rows) {
    jdbcTemplate.batchUpdate(
        """
        INSERT INTO scm.pull_request
          (tenant_id, team_id, work_item_id, source_key, title, source_branch, status,
           created_in_source, merged_at)
        VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (tenant_id, source_key) DO UPDATE SET
          team_id = EXCLUDED.team_id, work_item_id = EXCLUDED.work_item_id,
          title = EXCLUDED.title, source_branch = EXCLUDED.source_branch,
          status = EXCLUDED.status, created_in_source = EXCLUDED.created_in_source,
          merged_at = EXCLUDED.merged_at
        """,
        rows,
        rows.size(),
        (ps, r) -> {
          setUuidOrNull(ps, 1, r.teamId());
          setUuidOrNull(ps, 2, r.workItemId());
          ps.setString(3, r.sourceKey());
          ps.setString(4, r.title());
          ps.setString(5, r.sourceBranch());
          ps.setString(6, r.status());
          ps.setTimestamp(7, r.createdInSource());
          ps.setTimestamp(8, r.mergedAt());
        });
  }

  /**
   * Returns pull-request ids keyed by source key (for stitching reviews/builds).
   *
   * @return pull-request ids by source key
   */
  public Map<String, UUID> pullRequestIdsBySourceKey() {
    return keyedIds("SELECT source_key, id FROM scm.pull_request");
  }

  /**
   * Batch-upserts code reviews on {@code (tenant, source_key)}.
   *
   * @param rows the code-review rows
   */
  public void upsertCodeReviews(List<CodeReviewRow> rows) {
    jdbcTemplate.batchUpdate(
        """
        INSERT INTO scm.code_review
          (tenant_id, pull_request_id, source_key, outcome, requested_at, completed_at)
        VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?)
        ON CONFLICT (tenant_id, source_key) DO UPDATE SET
          pull_request_id = EXCLUDED.pull_request_id, outcome = EXCLUDED.outcome,
          requested_at = EXCLUDED.requested_at, completed_at = EXCLUDED.completed_at
        """,
        rows,
        rows.size(),
        (ps, r) -> {
          ps.setObject(1, r.pullRequestId());
          ps.setString(2, r.sourceKey());
          ps.setString(3, r.outcome());
          ps.setTimestamp(4, r.requestedAt());
          ps.setTimestamp(5, r.completedAt());
        });
  }

  /**
   * Batch-upserts builds on {@code (tenant, source_key)}.
   *
   * @param rows the build rows
   */
  public void upsertBuilds(List<BuildRow> rows) {
    jdbcTemplate.batchUpdate(
        """
        INSERT INTO cicd.build (tenant_id, pull_request_id, source_key, status, started_at,
                                finished_at)
        VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?)
        ON CONFLICT (tenant_id, source_key) DO UPDATE SET
          pull_request_id = EXCLUDED.pull_request_id, status = EXCLUDED.status,
          started_at = EXCLUDED.started_at, finished_at = EXCLUDED.finished_at
        """,
        rows,
        rows.size(),
        (ps, r) -> {
          setUuidOrNull(ps, 1, r.pullRequestId());
          ps.setString(2, r.sourceKey());
          ps.setString(3, r.status());
          ps.setTimestamp(4, r.startedAt());
          ps.setTimestamp(5, r.finishedAt());
        });
  }

  /**
   * Returns build ids keyed by source key (for stitching quality gates).
   *
   * @return build ids by source key
   */
  public Map<String, UUID> buildIdsBySourceKey() {
    return keyedIds("SELECT source_key, id FROM cicd.build");
  }

  /**
   * Batch-upserts quality gates on {@code (tenant, source_key)}.
   *
   * @param rows the quality-gate rows
   */
  public void upsertQualityGates(List<QualityGateRow> rows) {
    jdbcTemplate.batchUpdate(
        """
        INSERT INTO quality.quality_gate
          (tenant_id, build_id, pull_request_id, source_key, status, evaluated_at)
        VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?)
        ON CONFLICT (tenant_id, source_key) DO UPDATE SET
          build_id = EXCLUDED.build_id, pull_request_id = EXCLUDED.pull_request_id,
          status = EXCLUDED.status, evaluated_at = EXCLUDED.evaluated_at
        """,
        rows,
        rows.size(),
        (ps, r) -> {
          setUuidOrNull(ps, 1, r.buildId());
          setUuidOrNull(ps, 2, r.pullRequestId());
          ps.setString(3, r.sourceKey());
          ps.setString(4, r.status());
          ps.setTimestamp(5, r.evaluatedAt());
        });
  }

  private Map<String, UUID> keyedIds(String sql) {
    Map<String, UUID> ids = new HashMap<>();
    jdbc.sql(sql)
        .query(
            (rs, rowNum) -> {
              ids.put(rs.getString(1), rs.getObject(2, UUID.class));
              return Boolean.TRUE;
            })
        .list();
    return ids;
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
