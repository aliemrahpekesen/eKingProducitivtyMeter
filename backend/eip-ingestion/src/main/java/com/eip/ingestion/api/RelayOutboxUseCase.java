/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.api;

/**
 * Drains one batch of unpublished {@code core.event_outbox} rows per tenant to the event transport
 * (BackendPlan §6). Idempotent and safe to call repeatedly (e.g. from a fixed-delay scheduler in
 * eip-app): rows already published are never re-selected, and rows past the attempts ceiling are
 * excluded from the batch (dead-lettered) rather than retried forever.
 */
public interface RelayOutboxUseCase {

  /**
   * Attempts to publish up to {@code batchSize} unpublished rows for every tenant.
   *
   * @param batchSize the maximum rows to relay per tenant in this call
   * @return the outcome tally
   */
  OutboxRelayResult relayOnce(int batchSize);

  /**
   * The outcome of one {@link #relayOnce(int)} call, summed across every tenant.
   *
   * @param published rows successfully published and marked {@code published_at}
   * @param failed rows whose publish attempt failed but have not yet hit the attempts ceiling
   * @param deadLettered rows whose attempts ceiling was reached during this call
   */
  record OutboxRelayResult(int published, int failed, int deadLettered) {}
}
