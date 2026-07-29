/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.core.events;

/**
 * Marker for the {@link EventEnvelope#payload()} slot — the canonical, tool-agnostic body carried
 * by a domain event (DomainModel §6).
 *
 * <p>Concrete payloads form a sealed {@code DomainEvent} family (per BackendPlan §4 /
 * CodingStandards §2.1) introduced when the domain entities and their events land in Phase 1; the
 * shared kernel fixes only the envelope shape and this marker, so the envelope type can be defined
 * before any concrete event exists.
 */
public interface DomainEventPayload {}
