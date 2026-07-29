/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.core.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eip.core.domain.EntityType;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

  private static final UUID EVENT_ID = UUID.fromString("018f0000-0000-7000-8000-0000000000a1");
  private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-0000000000a2");
  private static final UUID ENTITY = UUID.fromString("018f0000-0000-7000-8000-0000000000a3");
  private static final Instant OCCURRED = Instant.parse("2026-07-07T10:15:30Z");
  private static final Instant INGESTED = Instant.parse("2026-07-07T10:15:31Z");

  /** In-kernel payload double — the real sealed family lands in Phase 1. */
  private record TestPayload(String detail) implements DomainEventPayload {}

  private static EventEnvelope sample(@Nullable String traceparent) {
    return new EventEnvelope(
        EVENT_ID,
        TENANT,
        "github",
        EntityType.WORK_ITEM,
        ENTITY,
        "updated",
        OCCURRED,
        INGESTED,
        SchemaVersion.of(1, 0),
        new TestPayload("x"),
        traceparent);
  }

  @Test
  void carriesExactlyTheElevenCanonicalFields() {
    EventEnvelope e = sample("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");

    assertThat(e.eventId()).isEqualTo(EVENT_ID);
    assertThat(e.tenantId()).isEqualTo(TENANT);
    assertThat(e.source()).isEqualTo("github");
    assertThat(e.entityType()).isEqualTo(EntityType.WORK_ITEM);
    assertThat(e.entityId()).isEqualTo(ENTITY);
    assertThat(e.eventType()).isEqualTo("updated");
    assertThat(e.occurredAt()).isEqualTo(OCCURRED);
    assertThat(e.ingestedAt()).isEqualTo(INGESTED);
    assertThat(e.schemaVersion()).isEqualTo(SchemaVersion.of(1, 0));
    assertThat(e.payload()).isEqualTo(new TestPayload("x"));
    assertThat(e.traceparent()).isNotNull();

    // exactly 11 record components, no drift from the canonical envelope
    assertThat(EventEnvelope.class.getRecordComponents()).hasSize(11);
  }

  @Test
  void allowsAbsentTraceparent() {
    assertThat(sample(null).traceparent()).isNull();
  }

  @Test
  @SuppressWarnings("NullAway") // deliberately passes null to verify the non-null contract
  void rejectsNullRequiredFields() {
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new EventEnvelope(
                    EVENT_ID,
                    TENANT,
                    "github",
                    EntityType.WORK_ITEM,
                    ENTITY,
                    "updated",
                    OCCURRED,
                    INGESTED,
                    SchemaVersion.of(1, 0),
                    null,
                    null))
        .withMessageContaining("payload");
  }

  @Test
  void rejectsBlankEventType() {
    assertThatThrownBy(
            () ->
                new EventEnvelope(
                    EVENT_ID,
                    TENANT,
                    "github",
                    EntityType.WORK_ITEM,
                    ENTITY,
                    "  ",
                    OCCURRED,
                    INGESTED,
                    SchemaVersion.of(1, 0),
                    new TestPayload("x"),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("eventType");
  }

  @Test
  void honoursValueEquality() {
    EventEnvelope a = sample(null);
    EventEnvelope b = sample(null);

    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    assertThat(a.toString()).contains("github", "updated");
  }
}
