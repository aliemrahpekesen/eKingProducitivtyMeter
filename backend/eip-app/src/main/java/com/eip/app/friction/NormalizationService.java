/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.friction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Normalizes raw staging rows into the canonical model on a tenant-bound {@link Connection}
 * (TASK-0016 INC-2). Reads {@code staging.raw_simulation} per stream and upserts {@code
 * work.work_item} (+ transitions), {@code scm.pull_request} (+ reviews), {@code cicd.build}, and
 * {@code quality.quality_gate}, populating {@code core.external_ref} for the work-item identity
 * anchor. Idempotent: work items resolve to a stable id through the external ref, and every other
 * table upserts on its {@code (tenant, source_key)} natural key, so re-normalizing changes nothing.
 */
final class NormalizationService {

  private final ObjectMapper mapper;

  NormalizationService(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /** Normalizes all staged streams for the bound tenant. */
  void normalize(Connection c) throws SQLException {
    Map<String, UUID> teamByName = loadTeams(c);
    normalizeWorkItems(c, teamByName);
    normalizeTransitions(c);
    normalizePullRequests(c, teamByName);
    normalizeCodeReviews(c);
    normalizeBuilds(c);
    normalizeQualityGates(c);
  }

  private Map<String, UUID> loadTeams(Connection c) throws SQLException {
    Map<String, UUID> teams = new HashMap<>();
    try (PreparedStatement ps =
            c.prepareStatement("SELECT id, name FROM core.team WHERE deleted_at IS NULL");
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        teams.put(rs.getString("name"), rs.getObject("id", UUID.class));
      }
    }
    return teams;
  }

  private void normalizeWorkItems(Connection c, Map<String, UUID> teamByName) throws SQLException {
    for (RawRow row : rawRows(c, "work_item")) {
      JsonNode p = parse(row.payload());
      UUID workItemId =
          resolveExternalRef(
              c,
              "WORK_ITEM",
              row.sourceSystem(),
              row.sourceInstance(),
              row.externalId(),
              text(p, "key"));
      UUID teamId = teamByName.get(text(p, "team"));
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO work.work_item (id, tenant_id, type, title, project_id,"
                  + " current_state_id, status, team_id, blocked, created_in_source, resolved_at)"
                  + " VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, false, ?, ?)"
                  + " ON CONFLICT (id) DO UPDATE SET type = EXCLUDED.type, title = EXCLUDED.title,"
                  + " status = EXCLUDED.status, team_id = EXCLUDED.team_id,"
                  + " resolved_at = EXCLUDED.resolved_at, updated_at = now()")) {
        ps.setObject(1, workItemId);
        ps.setString(2, workItemType(text(p, "type")));
        ps.setString(3, text(p, "title"));
        ps.setObject(4, syntheticId("project", "simulation"));
        ps.setObject(5, syntheticId("state", text(p, "status")));
        ps.setString(6, text(p, "status"));
        setUuidOrNull(ps, 7, teamId);
        ps.setTimestamp(8, ts(p, "createdAt"));
        ps.setTimestamp(9, ts(p, "resolvedAt"));
        ps.executeUpdate();
      }
    }
  }

  private void normalizeTransitions(Connection c) throws SQLException {
    for (RawRow row : rawRows(c, "work_item_transition")) {
      JsonNode p = parse(row.payload());
      UUID workItemId = workItemIdByKey(c, text(p, "workItemKey"));
      if (workItemId == null) {
        continue; // orphan transition (should not happen for the simulation dataset)
      }
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO work.work_item_transition (tenant_id, work_item_id, seq, from_state,"
                  + " to_state, occurred_at) VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?,"
                  + " ?, ?) ON CONFLICT (tenant_id, work_item_id, seq) DO UPDATE SET"
                  + " from_state = EXCLUDED.from_state, to_state = EXCLUDED.to_state,"
                  + " occurred_at = EXCLUDED.occurred_at")) {
        ps.setObject(1, workItemId);
        ps.setInt(2, p.get("seq").asInt());
        ps.setString(3, text(p, "fromState"));
        ps.setString(4, text(p, "toState"));
        ps.setTimestamp(5, ts(p, "at"));
        ps.executeUpdate();
      }
    }
  }

  private void normalizePullRequests(Connection c, Map<String, UUID> teamByName)
      throws SQLException {
    for (RawRow row : rawRows(c, "pull_request")) {
      JsonNode p = parse(row.payload());
      UUID workItemId = workItemIdByKey(c, text(p, "workItemKey"));
      UUID teamId = teamByName.get(text(p, "team"));
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO scm.pull_request (tenant_id, team_id, work_item_id, source_key, title,"
                  + " source_branch, status, created_in_source, merged_at)"
                  + " VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?)"
                  + " ON CONFLICT (tenant_id, source_key) DO UPDATE SET team_id = EXCLUDED.team_id,"
                  + " work_item_id = EXCLUDED.work_item_id, title = EXCLUDED.title,"
                  + " source_branch = EXCLUDED.source_branch, status = EXCLUDED.status,"
                  + " created_in_source = EXCLUDED.created_in_source, merged_at = EXCLUDED.merged_at")) {
        setUuidOrNull(ps, 1, teamId);
        setUuidOrNull(ps, 2, workItemId);
        ps.setString(3, text(p, "key"));
        ps.setString(4, text(p, "title"));
        ps.setString(5, text(p, "sourceBranch"));
        ps.setString(6, text(p, "status"));
        ps.setTimestamp(7, ts(p, "createdAt"));
        ps.setTimestamp(8, ts(p, "mergedAt"));
        ps.executeUpdate();
      }
    }
  }

  private void normalizeCodeReviews(Connection c) throws SQLException {
    for (RawRow row : rawRows(c, "code_review")) {
      JsonNode p = parse(row.payload());
      UUID prId = pullRequestIdByKey(c, text(p, "pullRequestKey"));
      if (prId == null) {
        continue;
      }
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO scm.code_review (tenant_id, pull_request_id, source_key, outcome,"
                  + " requested_at, completed_at) VALUES (current_setting('app.tenant_id')::uuid, ?,"
                  + " ?, ?, ?, ?) ON CONFLICT (tenant_id, source_key) DO UPDATE SET"
                  + " pull_request_id = EXCLUDED.pull_request_id, outcome = EXCLUDED.outcome,"
                  + " requested_at = EXCLUDED.requested_at, completed_at = EXCLUDED.completed_at")) {
        ps.setObject(1, prId);
        ps.setString(2, text(p, "key"));
        ps.setString(3, text(p, "outcome"));
        ps.setTimestamp(4, ts(p, "requestedAt"));
        ps.setTimestamp(5, ts(p, "completedAt"));
        ps.executeUpdate();
      }
    }
  }

  private void normalizeBuilds(Connection c) throws SQLException {
    for (RawRow row : rawRows(c, "build")) {
      JsonNode p = parse(row.payload());
      UUID prId = pullRequestIdByKey(c, text(p, "pullRequestKey"));
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO cicd.build (tenant_id, pull_request_id, source_key, status, started_at,"
                  + " finished_at) VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?)"
                  + " ON CONFLICT (tenant_id, source_key) DO UPDATE SET"
                  + " pull_request_id = EXCLUDED.pull_request_id, status = EXCLUDED.status,"
                  + " started_at = EXCLUDED.started_at, finished_at = EXCLUDED.finished_at")) {
        setUuidOrNull(ps, 1, prId);
        ps.setString(2, text(p, "key"));
        ps.setString(3, text(p, "status"));
        ps.setTimestamp(4, ts(p, "startedAt"));
        ps.setTimestamp(5, ts(p, "finishedAt"));
        ps.executeUpdate();
      }
    }
  }

  private void normalizeQualityGates(Connection c) throws SQLException {
    for (RawRow row : rawRows(c, "quality_gate")) {
      JsonNode p = parse(row.payload());
      UUID prId = pullRequestIdByKey(c, text(p, "pullRequestKey"));
      UUID buildId = buildIdByKey(c, text(p, "buildKey"));
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO quality.quality_gate (tenant_id, build_id, pull_request_id, source_key,"
                  + " status, evaluated_at) VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?,"
                  + " ?, ?) ON CONFLICT (tenant_id, source_key) DO UPDATE SET"
                  + " build_id = EXCLUDED.build_id, pull_request_id = EXCLUDED.pull_request_id,"
                  + " status = EXCLUDED.status, evaluated_at = EXCLUDED.evaluated_at")) {
        setUuidOrNull(ps, 1, buildId);
        setUuidOrNull(ps, 2, prId);
        ps.setString(3, text(p, "key"));
        ps.setString(4, text(p, "status"));
        ps.setTimestamp(5, ts(p, "evaluatedAt"));
        ps.executeUpdate();
      }
    }
  }

  // --- id resolution --------------------------------------------------------

  private UUID resolveExternalRef(
      Connection c,
      String entityType,
      String sourceSystem,
      String sourceInstance,
      String externalId,
      String externalKey)
      throws SQLException {
    try (PreparedStatement select =
        c.prepareStatement(
            "SELECT entity_id FROM core.external_ref WHERE entity_type = ? AND source_system = ?"
                + " AND external_id = ?")) {
      select.setString(1, entityType);
      select.setString(2, sourceSystem);
      select.setString(3, externalId);
      try (ResultSet rs = select.executeQuery()) {
        if (rs.next()) {
          return rs.getObject(1, UUID.class);
        }
      }
    }
    UUID entityId = UUID.randomUUID();
    try (PreparedStatement insert =
        c.prepareStatement(
            "INSERT INTO core.external_ref (tenant_id, entity_type, entity_id, source_system,"
                + " source_instance, external_id, external_key, last_seen_at) VALUES"
                + " (current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, now())")) {
      insert.setString(1, entityType);
      insert.setObject(2, entityId);
      insert.setString(3, sourceSystem);
      insert.setString(4, sourceInstance);
      insert.setString(5, externalId);
      insert.setString(6, externalKey);
      insert.executeUpdate();
    }
    return entityId;
  }

  private @Nullable UUID workItemIdByKey(Connection c, String naturalKey) throws SQLException {
    return entityIdByExternalId(c, "WORK_ITEM", "jira:" + naturalKey);
  }

  private @Nullable UUID entityIdByExternalId(Connection c, String entityType, String externalId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT entity_id FROM core.external_ref WHERE entity_type = ? AND external_id = ?")) {
      ps.setString(1, entityType);
      ps.setString(2, externalId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getObject(1, UUID.class) : null;
      }
    }
  }

  private @Nullable UUID pullRequestIdByKey(Connection c, String sourceKey) throws SQLException {
    return idBySourceKey(c, "SELECT id FROM scm.pull_request WHERE source_key = ?", sourceKey);
  }

  private @Nullable UUID buildIdByKey(Connection c, String sourceKey) throws SQLException {
    return idBySourceKey(c, "SELECT id FROM cicd.build WHERE source_key = ?", sourceKey);
  }

  private @Nullable UUID idBySourceKey(Connection c, String sql, String sourceKey)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, sourceKey);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getObject(1, UUID.class) : null;
      }
    }
  }

  // --- helpers --------------------------------------------------------------

  private java.util.List<RawRow> rawRows(Connection c, String stream) throws SQLException {
    java.util.List<RawRow> rows = new java.util.ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT natural_key, source_system, source_instance, external_id, payload::text"
                + " FROM staging.raw_simulation WHERE stream = ? AND op = 'upsert'"
                + " ORDER BY natural_key")) {
      ps.setString(1, stream);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(
              new RawRow(
                  rs.getString(1),
                  rs.getString(2),
                  rs.getString(3),
                  rs.getString(4),
                  rs.getString(5)));
        }
      }
    }
    return rows;
  }

  private JsonNode parse(String json) {
    try {
      return mapper.readTree(json);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("failed to parse staged payload", e);
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null ? "" : value.asText();
  }

  private static Timestamp ts(JsonNode node, String field) {
    return Timestamp.from(Instant.parse(text(node, field)));
  }

  private static void setUuidOrNull(PreparedStatement ps, int index, @Nullable UUID value)
      throws SQLException {
    if (value == null) {
      ps.setNull(index, java.sql.Types.OTHER);
    } else {
      ps.setObject(index, value);
    }
  }

  private static String workItemType(String rawType) {
    return switch (rawType.toLowerCase(Locale.ROOT)) {
      case "story" -> "STORY";
      case "bug" -> "BUG";
      case "task" -> "TASK";
      case "epic" -> "EPIC";
      case "feature" -> "FEATURE";
      default -> "TASK";
    };
  }

  /** Deterministic synthetic id for the required-but-not-modelled work_item FKs (v0.1). */
  private static UUID syntheticId(String kind, String value) {
    return UUID.nameUUIDFromBytes(
        ("eip-sim:" + kind + ":" + value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private record RawRow(
      String naturalKey,
      String sourceSystem,
      String sourceInstance,
      String externalId,
      String payload) {}
}
