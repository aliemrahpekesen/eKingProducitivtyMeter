/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.core.events;

import com.eip.core.domain.EntityType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The canonical domain-event envelope (DomainModel §1 principle 6, §6; BackendPlan §6). Every
 * create/update on a canonical entity emits an event wrapped in exactly these eleven fields — the
 * envelope is a contract anchor (AP-3), so the field set here matches the canonical list in
 * DomainModel §6 and CLAUDE.md exactly.
 *
 * <p>This is the envelope <em>value type</em> only. Kafka topic mapping, the transactional outbox,
 * partition keys, and idempotent-consumer dedup on {@code eventId} are defined in EventModel and
 * arrive in Phase 1.
 *
 * @param eventId unique event id (UUIDv7); consumers dedup on this
 * @param tenantId owning tenant (UUIDv7) — every event is tenant-scoped by construction (AP-2)
 * @param source emitting source/connector identity
 * @param entityType canonical entity the event concerns
 * @param entityId internal id of the entity the event concerns (UUIDv7)
 * @param eventType event kind, e.g. {@code created}, {@code updated}
 * @param occurredAt when the change occurred at the source (UTC instant)
 * @param ingestedAt when EIP ingested the change (UTC instant)
 * @param schemaVersion envelope/payload schema version for additive evolution
 * @param payload the canonical event body
 * @param traceparent W3C trace-context header for span propagation; null when no trace is active
 */
public record EventEnvelope(
    UUID eventId,
    UUID tenantId,
    String source,
    EntityType entityType,
    UUID entityId,
    String eventType,
    Instant occurredAt,
    Instant ingestedAt,
    SchemaVersion schemaVersion,
    DomainEventPayload payload,
    @Nullable String traceparent) {

  public EventEnvelope {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(tenantId, "tenantId");
    source = requireText(source, "source");
    Objects.requireNonNull(entityType, "entityType");
    Objects.requireNonNull(entityId, "entityId");
    eventType = requireText(eventType, "eventType");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(ingestedAt, "ingestedAt");
    Objects.requireNonNull(schemaVersion, "schemaVersion");
    Objects.requireNonNull(payload, "payload");
  }

  private static String requireText(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
