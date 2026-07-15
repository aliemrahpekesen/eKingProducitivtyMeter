/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.application;

import com.eip.app.application.RunFrictionPipelineUseCase.PipelineResult;
import java.util.UUID;

/**
 * Admin-panel "Sync now": runs one real connector synchronization for the current tenant, then
 * normalizes and recomputes friction, so freshly synced source data charts immediately.
 */
public interface SyncConnectorUseCase {

  /**
   * Syncs one registered connector and recomputes the tenant's metrics.
   *
   * @param connectorId the registered connector
   * @return staging + computation outcome
   */
  PipelineResult sync(UUID connectorId);
}
