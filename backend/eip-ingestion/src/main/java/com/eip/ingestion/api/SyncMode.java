/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.api;

/**
 * How {@link RunConnectorSyncUseCase#sync(java.util.UUID, SyncMode)} resolves the checkpoint cursor
 * it hands the connector.
 */
public enum SyncMode {

  /** Ignores any existing checkpoint; the connector emits its full source snapshot. */
  FULL,

  /**
   * Requires an existing checkpoint cursor; the connector narrows its fetch to changes since it.
   */
  INCREMENTAL,

  /** Incremental when a checkpoint cursor exists for the connector, full otherwise. */
  AUTO
}
