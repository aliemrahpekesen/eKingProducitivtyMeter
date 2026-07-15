/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.application;

import com.eip.connectors.spi.Connector;
import com.eip.connectors.spi.RawRecord;
import com.eip.ingestion.api.IngestSimulationDataUseCase;
import com.eip.ingestion.api.IngestionResult;
import com.eip.ingestion.persistence.ConnectorRegistryRepository;
import com.eip.ingestion.persistence.RawPayloadCodec;
import com.eip.ingestion.persistence.StagingRawRepository;
import com.eip.ingestion.persistence.StagingRawRepository.RawUpsert;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.tx.TenantTransactionRunner;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Ingests the simulation connector's emission into raw staging. The connector sync runs OUTSIDE the
 * database transaction (fetch/persist separation, BackendPlan §6); classification against the
 * existing content hashes and the single batched upsert run inside one tenant-bound transaction.
 * Replaying the same dataset classifies every record unchanged and writes nothing.
 */
@Service
public class SimulationIngestionService implements IngestSimulationDataUseCase {

  private final TenantTransactionRunner tx;
  private final Connector connector;
  private final ConnectorRegistryRepository registry;
  private final StagingRawRepository staging;
  private final RawPayloadCodec codec;

  public SimulationIngestionService(
      TenantTransactionRunner tx,
      ConnectorRegistry connectors,
      ConnectorRegistryRepository registry,
      StagingRawRepository staging,
      RawPayloadCodec codec) {
    this.tx = tx;
    this.connector =
        connectors
            .byType("simulation")
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "simulation connector disabled (eip.simulation.enabled=false)"));
    this.registry = registry;
    this.staging = staging;
    this.codec = codec;
  }

  @Override
  public IngestionResult ingest(TenantContext tenant) {
    List<RawRecord> emitted = new ArrayList<>();
    connector.sync(() -> emitted::add); // source fetch: outside any database transaction

    return tx.call(
        tenant,
        () -> {
          UUID connectorId = registry.ensureConnector(connector.type(), connector.simulation());
          Map<String, byte[]> existing =
              staging.contentHashes(StagingRawRepository.rawTable("simulation"), connectorId);

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
            staging.upsertAll(StagingRawRepository.rawTable("simulation"), connectorId, changes);
          }
          return new IngestionResult(emitted.size(), inserted, updated, unchanged);
        });
  }
}
