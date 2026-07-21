/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.application;

import com.eip.core.domain.EntityType;
import com.eip.core.domain.UuidV7Generator;
import com.eip.core.events.EventEnvelope;
import com.eip.core.events.SchemaVersion;
import com.eip.core.events.WorkItemUpserted;
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
import com.eip.ingestion.persistence.OutboxRepository;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Normalizes staged raw streams into the canonical model, set-based: each stream is read once,
 * mapped in memory, and written with one batch upsert; id stitching uses bulk key→id maps instead
 * of per-record lookups. Work-item identity is anchored through {@code core.external_ref} (AD-14),
 * so re-normalizing resolves the same stable ids and changes nothing.
 *
 * <p><strong>Delete lifecycle (DEBT-020 item 3 for work items; extended to {@code pull_request} /
 * {@code code_review} / {@code build} / {@code quality_gate} by DEBT-018 item 4):</strong> a staged
 * {@code op='delete'} row for the work-item stream resolves its canonical id through the
 * external-ref identity path and sets {@code work.work_item.deleted_at} (a bookkeeping "now", since
 * no source reports a deletion instant); a later {@code upsert} for that same identity is a REVIVAL
 * — {@link CanonicalWriteRepository#upsertWorkItems} clears {@code deleted_at} and reports the row
 * as changed, so it emits an {@code upserted} outbox event same as any other change. The other four
 * streams follow the identical shape but resolve identity by natural key ({@code source_key})
 * instead of {@code core.external_ref} (see {@link #markDeletedByNaturalKey}) — no outbox event
 * exists for those streams at all today (unchanged), so their deletion is bookkeeping-only,
 * observable via each stream's own {@code deleted_at} filter in {@code eip-analytics} reads. No
 * outbox event is emitted for ANY deletion itself (event-type vocabulary stays {@code upserted}
 * only; a dedicated {@code deleted} event type is v0.2 follow-up). No real connector emits {@code
 * op='delete'} for these four entity kinds yet (same honest caveat as the work-item lifecycle).
 *
 * <p><strong>Outbox-emission semantics (chosen trade-off, BackendPlan §6):</strong> exactly one
 * {@code core.event_outbox} row is written per canonical work item whose row {@linkplain
 * CanonicalWriteRepository#upsertWorkItems actually changed} in this normalization run — never one
 * per staged record. A byte-identical replay (the idempotent re-run this class already guarantees
 * for the canonical model) therefore emits zero new outbox rows, so downstream consumers never see
 * duplicate "upserted" events for unchanged data. The outbox write lands in the same transaction as
 * the canonical upsert (true transactional-outbox semantics: never emitted without the write, never
 * committed without the event). The envelope's {@code occurredAt} is deterministic, taken from the
 * item's own data ({@code resolvedAt}, falling back to {@code createdInSource}) — never wall clock;
 * {@code ingestedAt} is the one wall-clock exception, transport metadata only (not metric data).
 */
@Service
public class NormalizationService implements NormalizeStagedDataUseCase {

  /** Kafka topic for canonical work-item domain events (EventModel). */
  static final String TOPIC_WORK_ITEM = "eip.domain.workitem";

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
  private final OutboxRepository outbox;
  private final UuidV7Generator uuidGenerator;

  public NormalizationService(
      TenantTransactionRunner tx,
      StagingRawRepository staging,
      CanonicalWriteRepository canonical,
      ObjectMapper mapper,
      OutboxRepository outbox,
      UuidV7Generator uuidGenerator) {
    this.tx = tx;
    this.staging = staging;
    this.canonical = canonical;
    this.mapper = mapper;
    this.outbox = outbox;
    this.uuidGenerator = uuidGenerator;
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
          // Work items resolve their delete lifecycle through external_ref identity (DEBT-020 item
          // 3); the transition stream alone below reads readAll/readStream (op='upsert' only) since
          // transitions have no delete lifecycle of their own — every other stream reads
          // readAllWithDeletes for its own natural-key delete lifecycle (DEBT-018 item 4).
          List<StagedRow> workItemRows = readAllWithDeletes(STREAM_WORK_ITEM);
          List<Parsed> items =
              parse(workItemRows.stream().filter(r -> "upsert".equals(r.op())).toList());
          List<StagedRow> deleteRows =
              workItemRows.stream().filter(r -> "delete".equals(r.op())).toList();
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
          List<UUID> changedWorkItemIds = canonical.upsertWorkItems(workItems);
          emitWorkItemUpsertedEvents(tenant, items, workItems, changedWorkItemIds);

          // Deletes (DEBT-020 item 3, v0.1 — work items only): resolve each delete row's canonical
          // id through the SAME external_ref identity path, but never CREATE a missing anchor — an
          // identity never seen as an upsert has nothing to delete (documented, silently skipped).
          // No outbox event: the vocabulary stays 'upserted' only in v0.1 (follow-up, see class
          // javadoc). A later re-upsert of the same identity is the revival path, handled entirely
          // by upsertWorkItems above (it clears deleted_at and reports the row as changed).
          if (!deleteRows.isEmpty()) {
            Map<String, UUID> existingIds = canonical.existingWorkItemIdsByExternalId();
            List<UUID> toDelete = new ArrayList<>();
            for (StagedRow row : deleteRows) {
              @Nullable UUID id = existingIds.get(row.externalId());
              if (id != null) {
                toDelete.add(id);
              } // else: delete for an identity never ingested as an upsert; nothing to delete
            }
            canonical.markWorkItemsDeleted(toDelete);
          }

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

          // Pull requests, then their id map for reviews/builds. Delete lifecycle (DEBT-018 item
          // 4, mirrors work items — DEBT-020 item 3): readAllWithDeletes so op='delete' rows are
          // seen too; the natural key (StagedRow#naturalKey, identical to the "key" field every
          // stream's own upsert path already writes as source_key) resolves straight to the id map
          // refreshed immediately after the upsert, so it sees both pre-existing AND just-written
          // rows — never creating a missing anchor for an identity never upserted.
          List<StagedRow> pullRequestRows = readAllWithDeletes(STREAM_PULL_REQUEST);
          List<PullRequestRow> pullRequests = new ArrayList<>();
          for (Parsed p : parse(upsertsOnly(pullRequestRows))) {
            pullRequests.add(
                new PullRequestRow(
                    teamsByName.get(p.text("team")),
                    idsByKey.get(p.text("workItemKey")),
                    p.text("key"),
                    p.text("title"),
                    p.text("sourceBranch"),
                    p.text("status"),
                    p.timestamp("createdAt"),
                    p.timestampOrNull("mergedAt"))); // absent for a still-open pull request
          }
          canonical.upsertPullRequests(pullRequests);
          Map<String, UUID> prIds = canonical.pullRequestIdsBySourceKey();
          markDeletedByNaturalKey(pullRequestRows, prIds, canonical::markPullRequestsDeleted);

          List<StagedRow> codeReviewRows = readAllWithDeletes(STREAM_CODE_REVIEW);
          List<CodeReviewRow> reviews = new ArrayList<>();
          for (Parsed p : parse(upsertsOnly(codeReviewRows))) {
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
          markDeletedByNaturalKey(
              codeReviewRows,
              canonical.codeReviewIdsBySourceKey(),
              canonical::markCodeReviewsDeleted);

          List<StagedRow> buildRows = readAllWithDeletes(STREAM_BUILD);
          List<BuildRow> builds = new ArrayList<>();
          for (Parsed p : parse(upsertsOnly(buildRows))) {
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
          markDeletedByNaturalKey(buildRows, buildIds, canonical::markBuildsDeleted);

          List<StagedRow> qualityGateRows = readAllWithDeletes(STREAM_QUALITY_GATE);
          List<QualityGateRow> gates = new ArrayList<>();
          for (Parsed p : parse(upsertsOnly(qualityGateRows))) {
            gates.add(
                new QualityGateRow(
                    buildIds.get(p.text("buildKey")),
                    prIds.get(p.text("pullRequestKey")),
                    p.text("key"),
                    p.text("status"),
                    p.timestamp("evaluatedAt")));
          }
          canonical.upsertQualityGates(gates);
          markDeletedByNaturalKey(
              qualityGateRows,
              canonical.qualityGateIdsBySourceKey(),
              canonical::markQualityGatesDeleted);
        });
  }

  /**
   * Writes one {@code core.event_outbox} row per changed work item (see the class javadoc for the
   * chosen emission semantics). {@code items} and {@code workItems} are index-aligned — both are
   * built from the same {@code STREAM_WORK_ITEM} iteration with no filtering — so {@code
   * items.get(i)} is the staged source of {@code workItems.get(i)}.
   */
  private void emitWorkItemUpsertedEvents(
      TenantContext tenant,
      List<Parsed> items,
      List<WorkItemRow> workItems,
      List<UUID> changedIds) {
    if (changedIds.isEmpty()) {
      return;
    }
    Set<UUID> changed = new HashSet<>(changedIds);
    for (int i = 0; i < workItems.size(); i++) {
      WorkItemRow row = workItems.get(i);
      if (!changed.contains(row.id())) {
        continue;
      }
      Timestamp occurred = row.resolvedAt() != null ? row.resolvedAt() : row.createdInSource();
      String rawSource = items.get(i).row().sourceSystem();
      String source = (rawSource == null || rawSource.isBlank()) ? "ingestion" : rawSource;
      EventEnvelope envelope =
          new EventEnvelope(
              uuidGenerator.generate(),
              tenant.tenantId(),
              source,
              EntityType.WORK_ITEM,
              row.id(),
              "upserted",
              occurred.toInstant(),
              Instant.now(), // transport metadata only — see class javadoc
              SchemaVersion.of(1, 0),
              new WorkItemUpserted(row.id(), row.status()),
              null);
      outbox.insert(envelope, TOPIC_WORK_ITEM, tenant.tenantId() + ":" + row.id());
    }
  }

  /** Reads one stream's upsert rows across every raw staging table (bounded by connector count). */
  private List<StagedRow> readAll(String stream) {
    List<StagedRow> rows = new ArrayList<>();
    for (String table : StagingRawRepository.rawTables()) {
      rows.addAll(staging.readStream(table, stream));
    }
    return rows;
  }

  /**
   * Reads one stream's upsert AND delete rows across every raw staging table — the work-item phase
   * (DEBT-020 item 3) and the pull-request/code-review/build/quality-gate phases (DEBT-018 item 4)
   * call this; the transition phase alone reads {@link #readAll} (transitions have no delete
   * lifecycle of their own — they simply stop being emitted once their work item is deleted).
   */
  private List<StagedRow> readAllWithDeletes(String stream) {
    List<StagedRow> rows = new ArrayList<>();
    for (String table : StagingRawRepository.rawTables()) {
      rows.addAll(staging.readStreamWithDeletes(table, stream));
    }
    return rows;
  }

  /** Filters a {@link #readAllWithDeletes} result down to its {@code upsert} rows. */
  private static List<StagedRow> upsertsOnly(List<StagedRow> rows) {
    return rows.stream().filter(r -> "upsert".equals(r.op())).toList();
  }

  /**
   * Resolves {@code op='delete'} rows to an already-known canonical id by natural key — the same
   * identity every non-work-item stream upserts on as {@code source_key} ({@link
   * StagedRow#naturalKey()} and the payload's {@code key} field are the same value, by every
   * connector's own construction; natural key is used here because a delete's payload may be empty)
   * — and marks them deleted (DEBT-018 item 4, mirrors the work-item delete path). NEVER creates a
   * missing anchor: a delete for a natural key never seen as an upsert has nothing to delete and is
   * silently skipped, exactly like {@code existingWorkItemIdsByExternalId} guards the work-item
   * path.
   *
   * @param rows the stream's upsert+delete rows ({@link #readAllWithDeletes})
   * @param idsBySourceKey the canonical ids by {@code source_key}, refreshed AFTER this run's
   *     upsert so it reflects both pre-existing and just-written rows
   * @param markDeleted the repository call that soft-deletes the resolved ids
   */
  private static void markDeletedByNaturalKey(
      List<StagedRow> rows, Map<String, UUID> idsBySourceKey, Consumer<List<UUID>> markDeleted) {
    List<UUID> toDelete = new ArrayList<>();
    for (StagedRow row : rows) {
      if (!"delete".equals(row.op())) {
        continue;
      }
      @Nullable UUID id = idsBySourceKey.get(row.naturalKey());
      if (id != null) {
        toDelete.add(id);
      } // else: delete for an identity never ingested as an upsert; nothing to delete
    }
    if (!toDelete.isEmpty()) {
      markDeleted.accept(toDelete);
    }
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
