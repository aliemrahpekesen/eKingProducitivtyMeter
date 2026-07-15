/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Kafka adapters for the transactional outbox → event spine (BackendPlan §6): the {@code
 * DomainEventPublisher} implementation, the outbox relay's {@code @Scheduled} poll, and the
 * work-item events consumer that triggers friction recomputation. Every bean here is gated by
 * {@code eip.events.enabled} (default {@code true}) so the deployable still boots and serves HTTP
 * with Kafka unreachable — the relay simply retries and the listener container reconnects in the
 * background.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.app.events;

import org.jspecify.annotations.NullMarked;
