/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A source connector (ConnectorFramework §3). Implementations pull from one class of source system
 * (Jira, a Git host, a CI server, SonarQube, …) and emit {@link RawRecord}s via {@link
 * SyncContext#rawSink()}. They hold no persistence or tenant concerns — the caller runs each {@code
 * sync} inside a tenant-bound transaction and supplies the sink.
 *
 * <p><b>SPI 0.2:</b> {@link #descriptor()} added (DEBT-018) — a sanctioned pre-1.0 SPI break so the
 * admin connector-type catalog is descriptor-driven from the installed registry instead of a
 * hand-maintained static list.
 *
 * <p><b>SPI 0.3 (DEBT-018 residual, additive):</b> {@link #validate(ConnectorConfig)}, {@link
 * #healthCheck(ConnectorConfig)}, and {@link #incrementalSupported()} land as default methods —
 * every existing connector gets them for free with no override required.
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

  /**
   * Validates a configuration with pure schema/semantic checks — NO network round-trip
   * (ConnectorFramework §3: "{@code validate()} is pure schema/semantic validation (no network).
   * {@code testConnection()} performs a bounded, read-only probe."; DEBT-018 item 1).
   *
   * <p>The default implementation is generic: every connector gets it for free because it only
   * reads the connector's OWN already-published {@link ConnectorDescriptor#configSchema()} (JSON
   * Schema draft 2020-12) — via {@link MinimalJson}, a hand-rolled reader with NO framework
   * dependency ({@code com.eip.connectors.spi} is pure Java by architecture rule, so this cannot
   * use Jackson even though concrete connector implementations do). It walks the schema's {@code
   * required} array and, for each required property, its basic type ({@code string} or {@code
   * boolean}; any other declared type is treated as {@code string} since every value in {@link
   * ConnectorConfig#settings()} is itself a flat string, v0.1), then checks that {@code
   * config.settings()} carries every required key, present and non-blank, and that {@code
   * config.secret()} is present and non-blank whenever {@link ConnectorDescriptor#secretLabel()} is
   * non-null. No per-connector override is needed unless a connector has semantic rules beyond
   * "required keys present" (none do today).
   *
   * @param config the configuration to validate (secret may be the not-yet-revealed placeholder;
   *     this check only inspects presence/blankness, never the secret's content)
   * @return {@link ValidationResult#ok()}, or {@link ValidationResult#invalid(List)} listing every
   *     missing/blank field by name
   */
  default ValidationResult validate(ConnectorConfig config) {
    @Nullable Object schema;
    try {
      schema = MinimalJson.parse(descriptor().configSchema());
    } catch (RuntimeException e) {
      throw new IllegalStateException(type() + " descriptor configSchema is not valid JSON", e);
    }
    List<String> errors = new ArrayList<>();
    Map<String, @Nullable Object> properties = MinimalJson.asMap(schema, "properties");
    for (@Nullable Object requiredField : MinimalJson.asList(schema, "required")) {
      String key = String.valueOf(requiredField);
      String propertyType = MinimalJson.asString(properties.get(key), "type", "string");
      @Nullable String value = config.settings().get(key);
      boolean present =
          "boolean".equals(propertyType) ? value != null : value != null && !value.isBlank();
      if (!present) {
        errors.add("missing required field: " + key);
      }
    }
    @Nullable String secretLabel = descriptor().secretLabel();
    if (secretLabel != null && (config.secret() == null || config.secret().isBlank())) {
      errors.add("missing required secret: " + secretLabel);
    }
    return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.invalid(errors);
  }

  /**
   * Probes connector health for the Connector Health Monitor, exposed at {@code GET
   * /api/v1/connectors/{instanceId}/health} (ConnectorFramework §9; DEBT-018 item 2).
   *
   * <p>Default implementation delegates straight to {@link #testConnection(ConnectorConfig)} — see
   * {@link HealthStatus}'s class javadoc for the documented v0.1 equivalence rationale (no
   * scheduler/session/DEGRADED state machine exists yet to make health and connectivity distinct
   * operations).
   *
   * @param config the resolved configuration (settings + revealed secret)
   * @return the health outcome
   */
  default HealthStatus healthCheck(ConnectorConfig config) {
    return HealthStatus.from(testConnection(config));
  }

  /**
   * Reports whether this connector narrows its {@link #sync(SyncContext)} fetch using {@link
   * SyncContext#cursor()} for a true incremental fetch (ConnectorFramework §4 watermarking;
   * DEBT-018 item 3). Default {@code false}: most shipped connectors always perform a full fetch
   * regardless of the cursor they are handed, relying on content-hash staging idempotency to make
   * an "incremental" label a costly no-op rather than a real narrowing. The caller ({@code
   * RealConnectorSyncService}) uses this flag — never checkpoint presence alone — to decide whether
   * an {@code AUTO}-mode sync with an existing checkpoint may actually run as incremental, so a
   * connector that never asked for a cursor is never handed one and its raw records are never
   * mislabeled {@code INCREMENTAL}.
   *
   * @return {@code true} only for connectors that actually narrow their source query from the
   *     cursor {@link #sync(SyncContext)} is handed
   */
  default boolean incrementalSupported() {
    return false;
  }
}
