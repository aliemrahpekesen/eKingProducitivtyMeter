/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.application;

import com.eip.ingestion.api.IngestionException;
import com.eip.ingestion.api.NormalizeStagedDataUseCase;
import com.eip.ingestion.persistence.CanonicalWriteRepository;
import com.eip.ingestion.persistence.CanonicalWriteRepository.BuildRow;
import com.eip.ingestion.persistence.CanonicalWriteRepository.CodeReviewRow;
import com.eip.ingestion.persistence.CanonicalWriteRepository.ExternalRefCandidate;
import com.eip.ingestion.persistence.CanonicalWriteRepository.PullRequestRow;
import com.eip.ingestion.persistence.CanonicalWriteRepository.QualityGateRow;
import com.eip.ingestion.persistence.CanonicalWriteRepository.TransitionRow;
import com.eip.ingestion.persistence.CanonicalWriteRepository.WorkItemRow;
import com.eip.ingestion.persistence.StagingRawRepository;
import com.eip.ingestion.persistence.StagingRawRepository.StagedRow;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.tx.TenantTransactionRunner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Normalizes staged raw streams into the canonical model, set-based: each stream is read once,
 * mapped in memory, and written with one batch upsert; id stitching uses bulk key→id maps instead
 * of per-record lookups. Work-item identity is anchored through {@code core.external_ref} (AD-14),
 * so re-normalizing resolves the same stable ids and changes nothing.
 */
@Service
public class NormalizationService implements NormalizeStagedDataUseCase {

  private static final String STREAM_WORK_ITEM = "work_item";
  private static final String STREAM_TRANSITION = "work_item_transition";
  private static final String STREAM_PULL_REQUEST = "pull_request";
  private static final String STREAM_CODE_REVIEW = "code_review";
  private static final String STREAM_BUILD = "build";
  private static final String STREAM_QUALITY_GATE = "quality_gate";

  private final TenantTransactionRunner tx;
  private final StagingRawRepository staging;
  private final CanonicalWriteRepository canonical;
  private final ObjectMapper mapper;

  public NormalizationService(
      TenantTransactionRunner tx,
      StagingRawRepository staging,
      CanonicalWriteRepository canonical,
      ObjectMapper mapper) {
    this.tx = tx;
    this.staging = staging;
    this.canonical = canonical;
    this.mapper = mapper;
  }

  @Override
  public void normalize(TenantContext tenant) {
    tx.run(
        tenant,
        () -> {
          Map<String, UUID> teamsByName = canonical.teamIdsByName();

          // Work items: resolve stable ids through external_ref, then batch upsert. Teams named
          // by sources but missing in the control plane are auto-created under an "Imported"
          // structure so freshly connected sources chart immediately (team-level only, NFR-071).
          List<Parsed> items = parse(readAll(STREAM_WORK_ITEM));
          java.util.Set<String> unknownTeams = new java.util.LinkedHashSet<>();
          for (Parsed p : items) {
            String team = p.text("team");
            if (!team.isBlank() && !teamsByName.containsKey(team)) {
              unknownTeams.add(team);
            }
          }
          if (!unknownTeams.isEmpty()) {
            teamsByName = canonical.ensureImportedTeams(unknownTeams);
          }
          Map<String, UUID> idsByExternalId =
              canonical.resolveWorkItemIds(
                  items.stream()
                      .map(
                          p ->
                              new ExternalRefCandidate(
                                  p.row().sourceSystem(),
                                  p.row().sourceInstance(),
                                  p.row().externalId(),
                                  p.text("key")))
                      .toList());
          Map<String, UUID> idsByKey = new HashMap<>();
          List<WorkItemRow> workItems = new ArrayList<>();
          for (Parsed p : items) {
            UUID id = require(idsByExternalId.get(p.row().externalId()), p.row().externalId());
            idsByKey.put(p.text("key"), id);
            workItems.add(
                new WorkItemRow(
                    id,
                    workItemType(p.text("type")),
                    p.text("title"),
                    syntheticId("project", "simulation"),
                    syntheticId("state", p.text("status")),
                    p.text("status"),
                    teamsByName.get(p.text("team")),
                    p.timestamp("createdAt"),
                    p.timestampOrNull("resolvedAt")));
          }
          canonical.upsertWorkItems(workItems);

          // Transitions: stitch to items via the in-memory key map.
          List<TransitionRow> transitions = new ArrayList<>();
          for (Parsed p : parse(readAll(STREAM_TRANSITION))) {
            @Nullable UUID workItemId = idsByKey.get(p.text("workItemKey"));
            if (workItemId == null) {
              continue; // orphan transition; impossible for the simulation dataset
            }
            transitions.add(
                new TransitionRow(
                    workItemId,
                    Integer.parseInt(p.text("seq")),
                    p.textOrNull("fromState"),
                    p.text("toState"),
                    p.timestamp("at")));
          }
          canonical.upsertTransitions(transitions);

          // Pull requests, then their id map for reviews/builds.
          List<PullRequestRow> pullRequests = new ArrayList<>();
          for (Parsed p : parse(readAll(STREAM_PULL_REQUEST))) {
            pullRequests.add(
                new PullRequestRow(
                    teamsByName.get(p.text("team")),
                    idsByKey.get(p.text("workItemKey")),
                    p.text("key"),
                    p.text("title"),
                    p.text("sourceBranch"),
                    p.text("status"),
                    p.timestamp("createdAt"),
                    p.timestamp("mergedAt")));
          }
          canonical.upsertPullRequests(pullRequests);
          Map<String, UUID> prIds = canonical.pullRequestIdsBySourceKey();

          List<CodeReviewRow> reviews = new ArrayList<>();
          for (Parsed p : parse(readAll(STREAM_CODE_REVIEW))) {
            @Nullable UUID prId = prIds.get(p.text("pullRequestKey"));
            if (prId == null) {
              continue; // pull_request_id is NOT NULL; skip orphans as before
            }
            reviews.add(
                new CodeReviewRow(
                    prId,
                    p.text("key"),
                    p.text("outcome"),
                    p.timestamp("requestedAt"),
                    p.timestamp("completedAt")));
          }
          canonical.upsertCodeReviews(reviews);

          List<BuildRow> builds = new ArrayList<>();
          for (Parsed p : parse(readAll(STREAM_BUILD))) {
            builds.add(
                new BuildRow(
                    prIds.get(p.text("pullRequestKey")),
                    p.text("key"),
                    p.text("status"),
                    p.timestamp("startedAt"),
                    p.timestamp("finishedAt")));
          }
          canonical.upsertBuilds(builds);
          Map<String, UUID> buildIds = canonical.buildIdsBySourceKey();

          List<QualityGateRow> gates = new ArrayList<>();
          for (Parsed p : parse(readAll(STREAM_QUALITY_GATE))) {
            gates.add(
                new QualityGateRow(
                    buildIds.get(p.text("buildKey")),
                    prIds.get(p.text("pullRequestKey")),
                    p.text("key"),
                    p.text("status"),
                    p.timestamp("evaluatedAt")));
          }
          canonical.upsertQualityGates(gates);
        });
  }

  /** Reads one stream across every raw staging table (bounded by the connector-type count). */
  private List<StagedRow> readAll(String stream) {
    List<StagedRow> rows = new ArrayList<>();
    for (String table : StagingRawRepository.rawTables()) {
      rows.addAll(staging.readStream(table, stream));
    }
    return rows;
  }

  private List<Parsed> parse(List<StagedRow> rows) {
    List<Parsed> parsed = new ArrayList<>(rows.size());
    for (StagedRow row : rows) {
      try {
        parsed.add(new Parsed(row, mapper.readTree(row.payload())));
      } catch (JsonProcessingException e) {
        throw new IngestionException("failed to parse staged payload " + row.naturalKey(), e);
      }
    }
    return parsed;
  }

  private static UUID require(@Nullable UUID id, String externalId) {
    if (id == null) {
      throw new IngestionException("no external_ref resolved for " + externalId, null);
    }
    return id;
  }

  private static String workItemType(String rawType) {
    return switch (rawType.toLowerCase(Locale.ROOT)) {
      case "story" -> "STORY";
      case "bug" -> "BUG";
      case "epic" -> "EPIC";
      case "feature" -> "FEATURE";
      default -> "TASK";
    };
  }

  /** Deterministic synthetic id for the required-but-not-modelled work_item FKs (v0.1). */
  private static UUID syntheticId(String kind, String value) {
    return UUID.nameUUIDFromBytes(
        ("eip-sim:" + kind + ":" + value).getBytes(StandardCharsets.UTF_8));
  }

  /** One staged row with its parsed payload. */
  private record Parsed(StagedRow row, JsonNode payload) {

    String text(String field) {
      @Nullable JsonNode value = payload.get(field);
      return value == null ? "" : value.asText();
    }

    @Nullable String textOrNull(String field) {
      @Nullable JsonNode value = payload.get(field);
      return value == null ? null : value.asText();
    }

    Timestamp timestamp(String field) {
      return Timestamp.from(Instant.parse(text(field)));
    }

    @Nullable Timestamp timestampOrNull(String field) {
      @Nullable String value = textOrNull(field);
      return (value == null || value.isBlank()) ? null : Timestamp.from(Instant.parse(value));
    }
  }
}
