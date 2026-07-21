/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.application;

import com.eip.connectors.spi.Connector;
import com.eip.connectors.spi.ConnectorConfig;
import com.eip.connectors.spi.RawRecord;
import com.eip.connectors.spi.SyncContext;
import com.eip.core.error.ResourceNotFoundException;
import com.eip.core.error.ValidationException;
import com.eip.ingestion.api.IngestionResult;
import com.eip.ingestion.api.RunConnectorSyncUseCase;
import com.eip.ingestion.api.SourceSyncException;
import com.eip.ingestion.api.SyncMode;
import com.eip.ingestion.persistence.ConnectorAdminRepository;
import com.eip.ingestion.persistence.ConnectorCheckpointRepository;
import com.eip.ingestion.persistence.RawPayloadCodec;
import com.eip.ingestion.persistence.StagingRawRepository;
import com.eip.ingestion.persistence.StagingRawRepository.RawUpsert;
import com.eip.tenancy.secrets.SecretsService;
import com.eip.tenancy.tx.TenantTransactionRunner;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Real connector synchronization: config + secret + checkpoint cursor resolve in a short tenant
 * transaction, the source fetch runs OUTSIDE any transaction (BackendPlan §6 — no source call ever
 * holds a database transaction), and the batched, content-hash-idempotent staging write runs in a
 * second tenant transaction against the connector type's own raw table. On successful staging the
 * connector-level checkpoint ({@code core.connector_checkpoint}) advances to the instant the fetch
 * started; on any failure — before the second transaction is even reached — it is left untouched,
 * so a failed sync is always retried from the same starting point.
 */
@Service
public class RealConnectorSyncService implements RunConnectorSyncUseCase {

  private final TenantTransactionRunner tx;
  private final ConnectorAdminRepository connectors;
  private final ConnectorRegistry registry;
  private final SecretsService secrets;
  private final StagingRawRepository staging;
  private final RawPayloadCodec codec;
  private final ConnectorCheckpointRepository checkpoints;

  public RealConnectorSyncService(
      TenantTransactionRunner tx,
      ConnectorAdminRepository connectors,
      ConnectorRegistry registry,
      SecretsService secrets,
      StagingRawRepository staging,
      RawPayloadCodec codec,
      ConnectorCheckpointRepository checkpoints) {
    this.tx = tx;
    this.connectors = connectors;
    this.registry = registry;
    this.secrets = secrets;
    this.staging = staging;
    this.codec = codec;
    this.checkpoints = checkpoints;
  }

  /** A resolved registration: row + runtime config with the revealed secret + checkpoint cursor. */
  private record Resolved(
      UUID connectorId,
      String type,
      ConnectorConfig config,
      Optional<Map<String, String>> cursor) {}

  @Override
  public IngestionResult sync(UUID connectorId, SyncMode mode) {
    Resolved resolved =
        tx.readCurrent(
            () -> {
              var row =
                  connectors
                      .findWithSecret(connectorId)
                      .orElseThrow(() -> new ResourceNotFoundException("connector not found"));
              @Nullable String secret =
                  row.secretId() == null ? null : secrets.reveal(row.secretId());
              Optional<Map<String, String>> cursor =
                  checkpoints.find(connectorId, ConnectorCheckpointRepository.DEFAULT_STREAM);
              return new Resolved(
                  row.view().id(),
                  row.view().type(),
                  new ConnectorConfig(row.view().config(), secret),
                  cursor);
            });

    Connector connector =
        registry
            .byType(resolved.type())
            .orElseThrow(
                () ->
                    new ValidationException("no connector implementation for " + resolved.type()));
    if (!connector.syncAvailable()) {
      throw new ValidationException(
          resolved.type()
              + " sync is not available in this release (DEBT-018); use Test"
              + " Connection to verify credentials now.");
    }

    boolean hasCheckpoint = resolved.cursor().filter(c -> !c.isEmpty()).isPresent();
    boolean fullSync =
        switch (mode) {
          case FULL -> true;
          case INCREMENTAL -> false;
          // DEBT-018 item 3: AUTO with an existing checkpoint runs incremental ONLY when the
          // connector actually narrows its fetch from the cursor (Connector#incrementalSupported).
          // Otherwise it is forced FULL: never hand a cursor to a connector that never asked for
          // one, and never stamp the checkpoint's last_incremental_sync_at for a run that fetched
          // (and fetch-kind-tagged) everything as FULL anyway.
          case AUTO -> !hasCheckpoint || !connector.incrementalSupported();
        };
    Map<String, String> cursorForSync = fullSync ? Map.of() : resolved.cursor().orElse(Map.of());

    // Sanctioned bookkeeping now(): captured BEFORE the source fetch so the checkpoint never races
    // ahead of records the connector actually saw (a record changed mid-fetch is picked up again
    // next time, never missed). JiraConnector's overlap window absorbs the resulting re-fetch of a
    // small tail, made free by content-hash staging idempotency.
    Instant syncStart = Instant.now();

    // Source fetch: OUTSIDE any database transaction.
    List<RawRecord> emitted = new ArrayList<>();
    try {
      connector.sync(
          new SyncContext() {
            @Override
            public com.eip.connectors.spi.RawSink rawSink() {
              return emitted::add;
            }

            @Override
            public ConnectorConfig config() {
              return resolved.config();
            }

            @Override
            public Map<String, String> cursor() {
              return cursorForSync;
            }
          });
    } catch (RuntimeException e) {
      // Cursor is NOT advanced: we never reach the checkpoint upsert below.
      throw new SourceSyncException(
          resolved.type() + " sync failed against the source: " + String.valueOf(e.getMessage()),
          e);
    }

    String table = StagingRawRepository.rawTable(resolved.type());
    return tx.callCurrent(
        () -> {
          Map<String, byte[]> existing = staging.contentHashes(table, resolved.connectorId());
          List<RawUpsert> changes = new ArrayList<>();
          int inserted = 0;
          int updated = 0;
          int unchanged = 0;
          for (RawRecord record : emitted) {
            String json = codec.toCanonicalJson(record.payload());
            byte[] hash = codec.contentHash(json);
            byte @Nullable [] prior =
                existing.get(StagingRawRepository.streamKey(record.stream(), record.naturalKey()));
            if (prior == null) {
              inserted++;
              changes.add(new RawUpsert(record, json, hash));
            } else if (!Arrays.equals(prior, hash)) {
              updated++;
              changes.add(new RawUpsert(record, json, hash));
            } else {
              unchanged++;
            }
          }
          if (!changes.isEmpty()) {
            staging.upsertAll(table, resolved.connectorId(), changes);
          }
          checkpoints.upsert(
              resolved.connectorId(),
              ConnectorCheckpointRepository.DEFAULT_STREAM,
              Map.of("updatedSince", syncStart.toString()),
              fullSync);
          return new IngestionResult(emitted.size(), inserted, updated, unchanged);
        });
  }
}
