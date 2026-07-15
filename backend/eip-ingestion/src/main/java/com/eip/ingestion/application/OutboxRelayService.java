/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.application;

import com.eip.ingestion.api.DomainEventPublisher;
import com.eip.ingestion.api.RelayOutboxUseCase;
import com.eip.ingestion.persistence.OutboxRepository;
import com.eip.ingestion.persistence.OutboxRepository.OutboxRow;
import com.eip.tenancy.api.ManageTenantsUseCase;
import com.eip.tenancy.api.ManageTenantsUseCase.TenantView;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.tx.TenantTransactionRunner;
import org.springframework.stereotype.Service;

/**
 * Drains the transactional outbox tenant-by-tenant (BackendPlan §6). {@code core.tenant} is
 * platform-scoped (no RLS), so the tenant list itself is read once per call through {@link
 * ManageTenantsUseCase}; each tenant's batch is then selected, published, and marked/incremented
 * inside that tenant's own bound transaction (the connection carries {@code app.tenant_id} so
 * {@code core.event_outbox}'s RLS policy scopes the {@code SELECT ... FOR UPDATE SKIP LOCKED} to
 * that tenant automatically).
 *
 * <p>The publish call runs inside the same transaction as the row's lock/mark-published update — a
 * deliberate v0.1 trade-off (bounded by the publisher's own short send timeout) that keeps the
 * relay's success/failure bookkeeping atomic with the row state change; it trades a briefly longer
 * transaction for never marking a row published without a confirmed send.
 */
@Service
public class OutboxRelayService implements RelayOutboxUseCase {

  private final TenantTransactionRunner tx;
  private final OutboxRepository outbox;
  private final DomainEventPublisher publisher;
  private final ManageTenantsUseCase tenants;

  /**
   * Creates the relay service.
   *
   * @param tx the tenant-aware transaction boundary
   * @param outbox the outbox adapter
   * @param publisher the event-transport port
   * @param tenants the platform tenant directory
   */
  public OutboxRelayService(
      TenantTransactionRunner tx,
      OutboxRepository outbox,
      DomainEventPublisher publisher,
      ManageTenantsUseCase tenants) {
    this.tx = tx;
    this.outbox = outbox;
    this.publisher = publisher;
    this.tenants = tenants;
  }

  @Override
  public OutboxRelayResult relayOnce(int batchSize) {
    int published = 0;
    int failed = 0;
    int deadLettered = 0;
    for (TenantView tenant : tenants.list()) {
      RelayTally tally = tx.call(TenantContext.of(tenant.id()), () -> relayTenantBatch(batchSize));
      published += tally.published();
      failed += tally.failed();
      deadLettered += tally.deadLettered();
    }
    return new OutboxRelayResult(published, failed, deadLettered);
  }

  private RelayTally relayTenantBatch(int batchSize) {
    int published = 0;
    int failed = 0;
    int deadLettered = 0;
    for (OutboxRow row : outbox.selectUnpublishedBatch(batchSize)) {
      try {
        publisher.publish(row.topic(), row.partitionKey(), row.envelopeJson());
        outbox.markPublished(row.eventId());
        published++;
      } catch (RuntimeException e) {
        int attempts = outbox.incrementAttempts(row.eventId());
        if (attempts >= OutboxRepository.MAX_ATTEMPTS) {
          deadLettered++;
        } else {
          failed++;
        }
      }
    }
    return new RelayTally(published, failed, deadLettered);
  }

  /** Per-tenant relay tally, summed by the caller across every tenant. */
  private record RelayTally(int published, int failed, int deadLettered) {}
}
