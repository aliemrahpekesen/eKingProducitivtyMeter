/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.application;

import com.eip.ingestion.api.IngestionResult;
import java.util.UUID;

/**
 * Runs the full Engineering Friction slice for a tenant by composing the module use cases: raw
 * ingestion → normalization (eip-ingestion) → correlation + computation (eip-analytics). Each stage
 * is its own tenant-bound local transaction (outbox-ready boundaries, BackendPlan §6); every stage
 * is idempotent, so a rerun after a mid-pipeline failure converges.
 */
public interface RunFrictionPipelineUseCase {

  /**
   * Runs ingest → normalize → compute for the given tenant.
   *
   * @param tenantId the tenant to compute friction for
   * @return the pipeline outcome
   */
  PipelineResult run(UUID tenantId);

  /**
   * The outcome of one full pipeline run.
   *
   * @param ingestion the raw-staging outcome
   * @param teamsComputed teams with computed friction
   * @param itemsCorrelated work items correlated into evidence
   */
  record PipelineResult(IngestionResult ingestion, int teamsComputed, int itemsCorrelated) {}
}
