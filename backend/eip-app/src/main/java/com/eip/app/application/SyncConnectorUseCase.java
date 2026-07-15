/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.application;

import com.eip.app.application.RunFrictionPipelineUseCase.PipelineResult;
import com.eip.ingestion.api.SyncMode;
import java.util.UUID;

/**
 * Admin-panel "Sync now", webhook triggers, and scheduled auto-syncs: runs one real connector
 * synchronization for the current tenant, then normalizes and recomputes friction, so freshly
 * synced source data charts immediately.
 */
public interface SyncConnectorUseCase {

  /**
   * Syncs one registered connector in {@link SyncMode#AUTO} and recomputes the tenant's metrics.
   *
   * @param connectorId the registered connector
   * @return staging + computation outcome
   */
  default PipelineResult sync(UUID connectorId) {
    return sync(connectorId, SyncMode.AUTO);
  }

  /**
   * Syncs one registered connector in the given mode and recomputes the tenant's metrics.
   *
   * @param connectorId the registered connector
   * @param mode full, incremental, or auto-detected
   * @return staging + computation outcome
   */
  PipelineResult sync(UUID connectorId, SyncMode mode);
}
