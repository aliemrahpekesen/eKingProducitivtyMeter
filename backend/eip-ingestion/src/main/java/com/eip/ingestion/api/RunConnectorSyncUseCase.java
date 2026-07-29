/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.api;

import java.util.UUID;

/**
 * Runs one real synchronization for a REGISTERED connector: resolves its stored config + revealed
 * secret, fetches from the source OUTSIDE any database transaction, and stages the emitted records
 * content-hash idempotently into the connector type's raw table. Honest by construction: types
 * whose sync has not shipped fail with a clear validation error, never a silent no-op.
 *
 * <p>Every sync resolves a connector-level checkpoint cursor ({@code core.connector_checkpoint})
 * and hands it to the connector via {@link com.eip.connectors.spi.SyncContext#cursor()}; on
 * successful staging the cursor advances, on failure it does not (TASK-0021 Wave 2C).
 */
public interface RunConnectorSyncUseCase {

  /**
   * Synchronizes one registered connector for the current tenant in {@link SyncMode#AUTO}:
   * incremental when a checkpoint cursor already exists for the connector, full otherwise.
   *
   * @param connectorId the registered connector
   * @return the staging outcome
   */
  default IngestionResult sync(UUID connectorId) {
    return sync(connectorId, SyncMode.AUTO);
  }

  /**
   * Synchronizes one registered connector for the current tenant in the given mode.
   *
   * @param connectorId the registered connector
   * @param mode full, incremental, or auto-detected
   * @return the staging outcome
   */
  IngestionResult sync(UUID connectorId, SyncMode mode);
}
