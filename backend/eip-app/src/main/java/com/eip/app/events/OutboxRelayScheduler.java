/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.events;

import com.eip.ingestion.api.RelayOutboxUseCase;
import com.eip.ingestion.api.RelayOutboxUseCase.OutboxRelayResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls the transactional outbox on a fixed delay (BackendPlan §6) and records the per-call tally
 * as Micrometer counters — {@code eip_outbox_published_total}, {@code eip_outbox_failed_total},
 * {@code eip_outbox_dead_total} (ObservabilityModel: {@code eip_*} metric names; Micrometer maps
 * dotted names to Prometheus's underscored {@code _total} counter convention). Gated by {@code
 * eip.events.enabled} (default {@code true}); with Kafka down the relay's publish attempts simply
 * fail and count, retrying on the next poll — the deployable still boots and serves HTTP.
 */
@Component
@ConditionalOnProperty(prefix = "eip.events", name = "enabled", matchIfMissing = true)
public class OutboxRelayScheduler {

  private static final int BATCH_SIZE = 100;

  private final RelayOutboxUseCase relay;
  private final Counter published;
  private final Counter failed;
  private final Counter dead;

  /**
   * Creates the scheduler.
   *
   * @param relay the outbox relay use case
   * @param registry the Micrometer registry
   */
  public OutboxRelayScheduler(RelayOutboxUseCase relay, MeterRegistry registry) {
    this.relay = relay;
    this.published =
        Counter.builder("eip.outbox.published")
            .description("Outbox rows successfully published")
            .register(registry);
    this.failed =
        Counter.builder("eip.outbox.failed")
            .description("Outbox publish attempts that failed but have not hit the retry ceiling")
            .register(registry);
    this.dead =
        Counter.builder("eip.outbox.dead")
            .description("Outbox rows that reached the retry ceiling and were dead-lettered")
            .register(registry);
  }

  /** Relays one batch per tenant; runs every {@code eip.events.relay-delay-ms} (default 1000). */
  @Scheduled(fixedDelayString = "${eip.events.relay-delay-ms:1000}")
  public void relay() {
    OutboxRelayResult result = relay.relayOnce(BATCH_SIZE);
    published.increment(result.published());
    failed.increment(result.failed());
    dead.increment(result.deadLettered());
  }
}
