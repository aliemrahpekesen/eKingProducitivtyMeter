/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.spi;

/**
 * The per-sync collaborators handed to a {@link Connector#sync(SyncContext)} call
 * (ConnectorFramework §3). v0.1 exposes only the {@link RawSink}; checkpoint/cursor state and
 * secrets access are added with the first real connector (DEBT-018).
 */
public interface SyncContext {

  /**
   * Returns the sink this sync emits raw records into.
   *
   * @return the raw sink
   */
  RawSink rawSink();

  /**
   * Returns the resolved connector configuration for this sync (empty for config-free sources).
   *
   * @return settings + revealed secret
   */
  default ConnectorConfig config() {
    return new ConnectorConfig(java.util.Map.of(), null);
  }
}
