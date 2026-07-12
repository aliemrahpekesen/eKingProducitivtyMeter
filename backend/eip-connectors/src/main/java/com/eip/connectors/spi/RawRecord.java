/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.spi;

import java.util.Map;
import java.util.Objects;

/**
 * A single raw source record as emitted by a {@link Connector}, before any normalization
 * (ConnectorFramework §3, DatabasePlan §2 {@code staging.raw_<connector>}). It carries exactly the
 * source's own shape plus the provenance needed to normalize and correlate later:
 *
 * <ul>
 *   <li>{@code stream} — the logical source stream, e.g. {@code work_item}, {@code pull_request},
 *       {@code build}, {@code quality_gate}; selects the normalizer downstream.
 *   <li>{@code naturalKey} — the source's own stable key within the stream (e.g. {@code PLAT-101}).
 *       Together with the connector + stream it is the idempotency key: re-emitting the same key
 *       upserts in place rather than duplicating.
 *   <li>{@code sourceSystem} / {@code sourceInstance} — provenance ({@code jira}, {@code
 *       bitbucket}, {@code ci}, {@code sonarqube}); survives into {@code core.external_ref}.
 *   <li>{@code externalId} — the source's immutable native id (AD-14); the identity anchor a later
 *       key rename cannot break.
 *   <li>{@code payload} — the source fields as a flat string map (v0.1). Serialized to the {@code
 *       jsonb} staging column; the map's key set is the raw schema for this stream.
 * </ul>
 *
 * @param stream logical source stream name
 * @param naturalKey source-native stable key within the stream
 * @param sourceSystem provenance system of record
 * @param sourceInstance provenance instance discriminator (e.g. {@code sim})
 * @param externalId immutable native id
 * @param op assert-exists (upsert) or removed (delete)
 * @param fetchKind how the record was fetched
 * @param payload flat source-field map (defensively copied, unmodifiable)
 */
public record RawRecord(
    String stream,
    String naturalKey,
    String sourceSystem,
    String sourceInstance,
    String externalId,
    Op op,
    FetchKind fetchKind,
    Map<String, String> payload) {

  public RawRecord {
    stream = requireText(stream, "stream");
    naturalKey = requireText(naturalKey, "naturalKey");
    sourceSystem = requireText(sourceSystem, "sourceSystem");
    sourceInstance = requireText(sourceInstance, "sourceInstance");
    externalId = requireText(externalId, "externalId");
    Objects.requireNonNull(op, "op");
    Objects.requireNonNull(fetchKind, "fetchKind");
    payload = Map.copyOf(payload); // NPE on null map or null entry; unmodifiable snapshot
  }

  private static String requireText(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
