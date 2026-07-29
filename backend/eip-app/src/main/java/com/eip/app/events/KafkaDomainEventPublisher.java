/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.events;

import com.eip.ingestion.api.DomainEventPublisher;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka adapter for {@link DomainEventPublisher} (BackendPlan §6): {@code acks=all} (configured on
 * the producer factory, {@code application.yaml}) and a short, bounded wait on the send future so a
 * broker that is down or slow fails fast — the outbox relay counts the failure and retries on its
 * next poll instead of blocking indefinitely.
 */
@Component
@ConditionalOnProperty(prefix = "eip.events", name = "enabled", matchIfMissing = true)
public class KafkaDomainEventPublisher implements DomainEventPublisher {

  private static final long SEND_TIMEOUT_SECONDS = 5;

  private final KafkaTemplate<String, String> kafka;

  /**
   * Creates the publisher.
   *
   * @param kafka the Boot-autoconfigured string-valued Kafka template
   */
  public KafkaDomainEventPublisher(KafkaTemplate<String, String> kafka) {
    this.kafka = kafka;
  }

  @Override
  public void publish(String topic, String partitionKey, String envelopeJson) {
    try {
      kafka.send(topic, partitionKey, envelopeJson).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while publishing to " + topic, e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("failed to publish to " + topic, e);
    }
  }
}
