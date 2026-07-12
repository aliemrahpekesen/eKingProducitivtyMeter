/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.application;

import com.eip.analytics.api.ComputeFrictionUseCase;
import com.eip.analytics.api.ComputeFrictionUseCase.FrictionComputation;
import com.eip.ingestion.api.IngestSimulationDataUseCase;
import com.eip.ingestion.api.IngestionResult;
import com.eip.ingestion.api.NormalizeStagedDataUseCase;
import com.eip.tenancy.context.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Composition-root pipeline orchestrator: invokes the module-owned use cases in order. Owns no
 * business logic and no transaction — each module use case runs its own tenant-bound transaction,
 * so a connector fetch never holds a database transaction and each stage commits independently
 * (idempotent replay converges after a mid-pipeline failure).
 */
@Service
public class FrictionPipelineService implements RunFrictionPipelineUseCase {

  private final IngestSimulationDataUseCase ingest;
  private final NormalizeStagedDataUseCase normalize;
  private final ComputeFrictionUseCase compute;

  public FrictionPipelineService(
      IngestSimulationDataUseCase ingest,
      NormalizeStagedDataUseCase normalize,
      ComputeFrictionUseCase compute) {
    this.ingest = ingest;
    this.normalize = normalize;
    this.compute = compute;
  }

  @Override
  public PipelineResult run(UUID tenantId) {
    TenantContext tenant = TenantContext.of(tenantId);
    IngestionResult staged = ingest.ingest(tenant);
    normalize.normalize(tenant);
    FrictionComputation computation = compute.compute(tenant);
    return new PipelineResult(staged, computation.teamsComputed(), computation.itemsCorrelated());
  }
}
