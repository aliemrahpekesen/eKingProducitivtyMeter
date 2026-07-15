/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.events;

import com.eip.analytics.api.ComputeFrictionUseCase;
import com.eip.app.persistence.ProcessedEventsRepository;
import com.eip.tenancy.context.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Reacts to {@code eip.domain.workitem} events by scheduling a friction recompute for the event's
 * tenant (BackendPlan §6). Idempotent via {@link ProcessedEventsRepository} (dedup on {@code
 * eventId}): a redelivered event is acknowledged and skipped without recomputing. Any processing
 * failure (malformed envelope, dedup/compute error) routes the raw message to {@link #DLQ_TOPIC}
 * and the listener returns normally — never rethrows — so the container acks the original message
 * and there is no infinite redelivery loop.
 */
@Component
@ConditionalOnProperty(prefix = "eip.events", name = "enabled", matchIfMissing = true)
public class WorkItemEventsConsumer {

  /** Consumer group (EventModel: {@code eip.<module>.<purpose>}). */
  static final String CONSUMER_GROUP = "eip.analytics.recompute";

  /** Dead-letter topic for envelopes this consumer could not process. */
  static final String DLQ_TOPIC = "eip.domain.workitem.dlq";

  private static final Logger log = LoggerFactory.getLogger(WorkItemEventsConsumer.class);

  private final ProcessedEventsRepository processed;
  private final ComputeFrictionUseCase compute;
  private final KafkaTemplate<String, String> kafka;
  private final ObjectMapper mapper;
  private final Counter dlqCounter;

  /**
   * Creates the consumer.
   *
   * @param processed the consumer idempotency ledger
   * @param compute the friction recompute use case
   * @param kafka the Kafka template (used only to route failures to the DLQ topic)
   * @param mapper the shared {@link ObjectMapper}
   * @param registry the Micrometer registry
   */
  public WorkItemEventsConsumer(
      ProcessedEventsRepository processed,
      ComputeFrictionUseCase compute,
      KafkaTemplate<String, String> kafka,
      ObjectMapper mapper,
      MeterRegistry registry) {
    this.processed = processed;
    this.compute = compute;
    this.kafka = kafka;
    this.mapper = mapper;
    this.dlqCounter =
        Counter.builder("eip.events.dlq")
            .description("Messages that failed processing and were routed to a DLQ topic")
            .register(registry);
  }

  /**
   * Consumes one work-item event envelope.
   *
   * @param envelopeJson the raw envelope JSON
   */
  @KafkaListener(topics = "eip.domain.workitem", groupId = CONSUMER_GROUP)
  public void onMessage(String envelopeJson) {
    try {
      JsonNode node = mapper.readTree(envelopeJson);
      UUID tenantId = UUID.fromString(node.get("tenantId").asText());
      UUID eventId = UUID.fromString(node.get("eventId").asText());
      TenantContext tenant = TenantContext.of(tenantId);

      if (processed.recordIfNew(tenant, CONSUMER_GROUP, eventId)) {
        compute.compute(tenant);
      } else {
        log.info("duplicate work-item event {} — skipped", eventId);
      }
    } catch (Exception e) {
      // Broad by design: malformed JSON (checked JsonProcessingException), a missing/invalid
      // field (NullPointerException/IllegalArgumentException), or a compute failure must all
      // route to the DLQ rather than block or infinitely redeliver this partition.
      log.error("failed to process work-item event; routing to DLQ", e);
      dlqCounter.increment();
      var unused =
          kafka
              .send(DLQ_TOPIC, envelopeJson)
              .whenComplete(
                  (result, sendError) -> {
                    if (sendError != null) {
                      log.error(
                          "failed to route work-item event to DLQ topic {}", DLQ_TOPIC, sendError);
                    }
                  });
    }
  }
}
