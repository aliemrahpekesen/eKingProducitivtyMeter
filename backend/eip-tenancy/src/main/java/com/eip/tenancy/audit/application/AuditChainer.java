/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.audit.application;

import com.eip.tenancy.api.ManageTenantsUseCase;
import com.eip.tenancy.api.ManageTenantsUseCase.TenantView;
import com.eip.tenancy.audit.api.AuditChainerUseCase;
import com.eip.tenancy.audit.persistence.AuditEventRepository;
import com.eip.tenancy.audit.persistence.AuditEventRepository.ChainCandidateRow;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.tx.TenantTransactionRunner;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The async batch chainer (SecurityModel §11): for every tenant with unchained rows, assigns {@code
 * prev_hash}/{@code hash} in strict {@code (occurred_at, id)} order — id is UUIDv7, so id order is
 * time order, giving one unambiguous per-tenant sequence across monthly partition boundaries.
 * Single-writer-per-tenant is enforced by {@link AuditEventRepository#tryAcquireChainerLock}, a
 * Postgres transaction-scoped advisory lock (see this module's root package-info for why not
 * Redisson); a tenant already being chained by an overlapping sweep or another instance is simply
 * skipped this pass, not blocked on.
 *
 * <p>One tenant's failure is isolated: caught, logged, counted as an {@code
 * eip_job_failures_total{job="audit-chain"}} failure, and never blocks the sweep for the rest.
 */
@Service
public class AuditChainer implements AuditChainerUseCase {

  private static final Logger log = LoggerFactory.getLogger(AuditChainer.class);

  private final ManageTenantsUseCase tenants;
  private final TenantTransactionRunner txRunner;
  private final AuditEventRepository repository;
  private final AuditRecordCanonicalizer canonicalizer;
  private final int batchSize;
  private final Counter rowsChained;
  private final Counter sweepFailures;
  private final AtomicLong chainLagSeconds = new AtomicLong(0);

  /**
   * Creates the chainer.
   *
   * @param tenants the platform tenant directory
   * @param txRunner the tenant-aware transaction boundary
   * @param repository the audit-event repository
   * @param canonicalizer the per-row hash computation
   * @param registry the Micrometer registry
   * @param batchSize the maximum rows chained per tenant per sweep
   */
  public AuditChainer(
      ManageTenantsUseCase tenants,
      TenantTransactionRunner txRunner,
      AuditEventRepository repository,
      AuditRecordCanonicalizer canonicalizer,
      MeterRegistry registry,
      @Value("${eip.audit.chainer-batch-size:500}") int batchSize) {
    this.tenants = tenants;
    this.txRunner = txRunner;
    this.repository = repository;
    this.canonicalizer = canonicalizer;
    this.batchSize = batchSize;
    this.rowsChained =
        Counter.builder("eip.audit.chain.rows")
            .description("Audit rows assigned prev_hash/hash by the chainer")
            .register(registry);
    this.sweepFailures =
        Counter.builder("eip.job.failures")
            .tag("job", "audit-chain")
            .description("Scheduled job failures (audit-verify, audit-chain, retention-purge, ...)")
            .register(registry);
    Gauge.builder("eip.audit.chain.lag.seconds", chainLagSeconds, AtomicLong::get)
        .description(
            "Age, in seconds, of the globally oldest not-yet-chained audit row observed in the"
                + " most recent chainer sweep (0 when nothing was pending)")
        .register(registry);
  }

  @Override
  public ChainSweepResult chainPendingRows() {
    int tenantsWithWork = 0;
    int totalChained = 0;
    @Nullable Instant oldestPending = null;
    for (TenantView tenant : tenants.list()) {
      try {
        ChainOneTenantResult result =
            txRunner.call(TenantContext.of(tenant.id()), () -> chainOneTenant(tenant.id()));
        if (result.rowsChained() > 0) {
          tenantsWithWork++;
          totalChained += result.rowsChained();
        }
        if (result.oldestPending() != null
            && (oldestPending == null || result.oldestPending().isBefore(oldestPending))) {
          oldestPending = result.oldestPending();
        }
      } catch (RuntimeException e) {
        sweepFailures.increment();
        log.error("audit chainer sweep failed for tenant {}", tenant.id(), e);
      }
    }
    chainLagSeconds.set(
        oldestPending == null ? 0 : Duration.between(oldestPending, Instant.now()).toSeconds());
    if (totalChained > 0) {
      rowsChained.increment(totalChained);
    }
    return new ChainSweepResult(tenantsWithWork, totalChained);
  }

  private ChainOneTenantResult chainOneTenant(UUID tenantId) {
    if (!repository.tryAcquireChainerLock()) {
      return ChainOneTenantResult.EMPTY; // another writer is already chaining this tenant now
    }
    List<ChainCandidateRow> pending = repository.findUnchainedOrdered(batchSize);
    if (pending.isEmpty()) {
      return ChainOneTenantResult.EMPTY;
    }
    byte[] prevHash =
        repository.findLastChainedHash().orElseGet(() -> canonicalizer.genesisHash(tenantId));
    for (ChainCandidateRow row : pending) {
      byte[] hash = canonicalizer.computeHash(prevHash, tenantId, row);
      repository.setChain(row.id(), row.occurredAt(), prevHash, hash);
      prevHash = hash;
    }
    return new ChainOneTenantResult(pending.size(), pending.get(0).occurredAt());
  }

  private record ChainOneTenantResult(int rowsChained, @Nullable Instant oldestPending) {
    private static final ChainOneTenantResult EMPTY = new ChainOneTenantResult(0, null);
  }
}
