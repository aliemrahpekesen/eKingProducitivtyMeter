/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.application;

import com.eip.analytics.api.ComputeFrictionUseCase;
import com.eip.analytics.api.ComputeFrictionUseCase.FrictionComputation;
import com.eip.ingestion.api.IngestionResult;
import com.eip.ingestion.api.NormalizeStagedDataUseCase;
import com.eip.ingestion.api.RunConnectorSyncUseCase;
import com.eip.ingestion.api.SyncMode;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.context.TenantContextHolder;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Composition-root orchestrator for real connector syncs: source sync (fetch outside any
 * transaction) → normalization → friction recomputation, each stage its own tenant transaction.
 */
@Service
public class ConnectorSyncService implements SyncConnectorUseCase {

  private final RunConnectorSyncUseCase connectorSync;
  private final NormalizeStagedDataUseCase normalize;
  private final ComputeFrictionUseCase compute;

  public ConnectorSyncService(
      RunConnectorSyncUseCase connectorSync,
      NormalizeStagedDataUseCase normalize,
      ComputeFrictionUseCase compute) {
    this.connectorSync = connectorSync;
    this.normalize = normalize;
    this.compute = compute;
  }

  @Override
  public RunFrictionPipelineUseCase.PipelineResult sync(UUID connectorId, SyncMode mode) {
    TenantContext tenant = TenantContextHolder.require();
    IngestionResult staged = connectorSync.sync(connectorId, mode);
    normalize.normalize(tenant);
    FrictionComputation computation = compute.compute(tenant);
    return new RunFrictionPipelineUseCase.PipelineResult(
        staged, computation.teamsComputed(), computation.itemsCorrelated());
  }
}
