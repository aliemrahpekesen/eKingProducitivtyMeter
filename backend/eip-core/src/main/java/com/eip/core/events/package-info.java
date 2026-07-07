/**
 * Shared-kernel event envelope types: the canonical {@link com.eip.core.events.EventEnvelope}, its
 * {@link com.eip.core.events.SchemaVersion}, and the {@link com.eip.core.events.DomainEventPayload}
 * marker (DomainModel §1 principle 6, §6). This package defines the envelope <em>type</em> only —
 * Kafka topics, the outbox, and consumer wiring are specified in EventModel and land in Phase 1.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.core.events;

import org.jspecify.annotations.NullMarked;
