/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.spi;

/**
 * A source connector (ConnectorFramework §3). Implementations pull from one class of source system
 * (Jira, a Git host, a CI server, SonarQube, …) and emit {@link RawRecord}s via {@link
 * SyncContext#rawSink()}. They hold no persistence or tenant concerns — the caller runs each {@code
 * sync} inside a tenant-bound transaction and supplies the sink.
 */
public interface Connector {

  /**
   * Returns the connector type discriminator (matches {@code core.connector.type}).
   *
   * @return the connector type, e.g. {@code simulation}
   */
  String type();

  /**
   * Reports whether this connector produces simulated (non-real-source) data, so the UI can label
   * it and reports can exclude it — FR/NFR transparency (never present simulation as production).
   *
   * @return {@code true} if the data is simulated
   */
  boolean simulation();

  /**
   * Runs one synchronization, emitting raw records into {@code context.rawSink()}. Must be
   * deterministic for a given source state so replays are idempotent downstream.
   *
   * @param context the per-sync collaborators (the raw sink)
   */
  void sync(SyncContext context);
}
