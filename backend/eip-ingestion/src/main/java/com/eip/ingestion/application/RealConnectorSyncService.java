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
import com.eip.ingestion.persistence.ConnectorAdminRepository;
import com.eip.ingestion.persistence.RawPayloadCodec;
import com.eip.ingestion.persistence.StagingRawRepository;
import com.eip.ingestion.persistence.StagingRawRepository.RawUpsert;
import com.eip.tenancy.secrets.SecretsService;
import com.eip.tenancy.tx.TenantTransactionRunner;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Real connector synchronization: config + secret resolve in a short tenant transaction, the source
 * fetch runs OUTSIDE any transaction (BackendPlan §6 — no source call ever holds a database
 * transaction), and the batched, content-hash-idempotent staging write runs in a second tenant
 * transaction against the connector type's own raw table.
 */
@Service
public class RealConnectorSyncService implements RunConnectorSyncUseCase {

  private final TenantTransactionRunner tx;
  private final ConnectorAdminRepository connectors;
  private final ConnectorRegistry registry;
  private final SecretsService secrets;
  private final StagingRawRepository staging;
  private final RawPayloadCodec codec;

  public RealConnectorSyncService(
      TenantTransactionRunner tx,
      ConnectorAdminRepository connectors,
      ConnectorRegistry registry,
      SecretsService secrets,
      StagingRawRepository staging,
      RawPayloadCodec codec) {
    this.tx = tx;
    this.connectors = connectors;
    this.registry = registry;
    this.secrets = secrets;
    this.staging = staging;
    this.codec = codec;
  }

  /** A resolved registration: row + runtime config with the revealed secret. */
  private record Resolved(UUID connectorId, String type, ConnectorConfig config) {}

  @Override
  public IngestionResult sync(UUID connectorId) {
    Resolved resolved =
        tx.readCurrent(
            () -> {
              var row =
                  connectors
                      .findWithSecret(connectorId)
                      .orElseThrow(() -> new ResourceNotFoundException("connector not found"));
              @Nullable String secret =
                  row.secretId() == null ? null : secrets.reveal(row.secretId());
              return new Resolved(
                  row.view().id(),
                  row.view().type(),
                  new ConnectorConfig(row.view().config(), secret));
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

    // Source fetch: OUTSIDE any database transaction.
    List<RawRecord> emitted = new ArrayList<>();
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
        });

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
          return new IngestionResult(emitted.size(), inserted, updated, unchanged);
        });
  }
}
