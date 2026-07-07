/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Maps one internal canonical entity to one identity in one source tool (DomainModel §2.2). An
 * entity may carry many {@code ExternalRef}s (e.g. a {@code WorkItem} mirrored in Jira and a custom
 * internal demand tool).
 *
 * <p><strong>Identity contract (AD-14 / ADR-014).</strong> {@code externalId} is the
 * <em>immutable</em> native id of the source record and is the only value used for identity
 * resolution; {@code externalKey} is a <em>mutable</em>, human-readable source key (e.g. {@code
 * PROJ-1234}) used for display and correlation heuristics only — never for identity. When a source
 * renames its human-readable key, the normalizer updates {@code externalKey} and appends the prior
 * value to {@code keyAliases}, because old keys persist indefinitely in commit messages and PR
 * titles.
 *
 * <p>This is a shared-kernel value type only; the physical {@code core.external_ref} table and the
 * upsert/resolution algorithm land with persistence in a later phase.
 *
 * @param id internal primary key (UUIDv7)
 * @param tenantId owning tenant (UUIDv7) — provenance is tenant-scoped by construction (AP-2)
 * @param entityType canonical entity this reference points at
 * @param entityId internal id of the referenced entity (UUIDv7)
 * @param sourceSystem connector identity, e.g. {@code jira}, {@code github}
 * @param sourceInstance instance discriminator (enterprises run multiple Jiras)
 * @param externalId immutable native id in the source — never a renamable human key
 * @param externalKey mutable human-readable source key; display/correlation use only
 * @param keyAliases prior {@code externalKey} values retained on source key change (never null;
 *     defaults to empty)
 * @param url deep link into the source tool
 * @param lastSeenAt last time the connector observed this identity
 */
public record ExternalRef(
    UUID id,
    UUID tenantId,
    EntityType entityType,
    UUID entityId,
    String sourceSystem,
    String sourceInstance,
    String externalId,
    @Nullable String externalKey,
    List<String> keyAliases,
    @Nullable String url,
    Instant lastSeenAt) {

  public ExternalRef {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(entityType, "entityType");
    Objects.requireNonNull(entityId, "entityId");
    sourceSystem = requireText(sourceSystem, "sourceSystem");
    sourceInstance = requireText(sourceInstance, "sourceInstance");
    externalId = requireText(externalId, "externalId");
    // Immutable defensive copy; List.copyOf rejects a null element, keeping aliases well-formed.
    keyAliases = List.copyOf(keyAliases);
    Objects.requireNonNull(lastSeenAt, "lastSeenAt");
  }

  private static String requireText(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
