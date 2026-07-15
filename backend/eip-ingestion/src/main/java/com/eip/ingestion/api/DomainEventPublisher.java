/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.api;

/**
 * Publishes one already-persisted outbox row to the event transport (BackendPlan §6). Implemented
 * in eip-app as a Kafka adapter; eip-ingestion depends only on this port so the transactional
 * outbox relay stays transport-agnostic and unit-testable with an in-memory fake.
 *
 * <p>Implementations MUST throw on any failure (timeout, broker unavailable, send rejected) so the
 * relay can count the attempt and retry — never swallow a failure as success.
 */
public interface DomainEventPublisher {

  /**
   * Publishes one envelope to the transport.
   *
   * @param topic the destination topic
   * @param partitionKey the partition/ordering key ({@code "{tenantId}:{entityId}"})
   * @param envelopeJson the serialized {@code EventEnvelope} JSON
   */
  void publish(String topic, String partitionKey, String envelopeJson);
}
