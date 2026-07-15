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
 *
 * <p><b>SPI 0.2:</b> {@link #descriptor()} added (DEBT-018) — a sanctioned pre-1.0 SPI break so the
 * admin connector-type catalog is descriptor-driven from the installed registry instead of a
 * hand-maintained static list.
 */
public interface Connector {

  /**
   * Returns the connector type discriminator (matches {@code core.connector.type}).
   *
   * @return the connector type, e.g. {@code simulation}
   */
  String type();

  /**
   * Returns this connector's admin-panel descriptor (display name, description, config-form JSON
   * Schema, secret label). {@link ConnectorDescriptor#type()} must equal {@link #type()}.
   *
   * @return the descriptor
   */
  ConnectorDescriptor descriptor();

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
   * @param context the per-sync collaborators (raw sink + resolved config)
   */
  void sync(SyncContext context);

  /**
   * Probes source connectivity with the given configuration — honestly: {@code OK} only after a
   * real authenticated round-trip. Default: the capability is not shipped for this type yet.
   *
   * @param config the resolved configuration (settings + revealed secret)
   * @return the probe outcome
   */
  default TestConnectionOutcome testConnection(ConnectorConfig config) {
    return TestConnectionOutcome.notAvailable(
        type() + " connectivity probe is not implemented in this release (DEBT-018).");
  }

  /**
   * Reports whether {@link #sync(SyncContext)} is implemented for real ingestion in this release.
   *
   * @return true when sync actually ingests
   */
  default boolean syncAvailable() {
    return false;
  }
}
