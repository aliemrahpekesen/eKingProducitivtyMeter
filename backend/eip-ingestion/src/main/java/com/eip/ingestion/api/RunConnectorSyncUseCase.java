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
 */
public interface RunConnectorSyncUseCase {

  /**
   * Synchronizes one registered connector for the current tenant.
   *
   * @param connectorId the registered connector
   * @return the staging outcome
   */
  IngestionResult sync(UUID connectorId);
}
