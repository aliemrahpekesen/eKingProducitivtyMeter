/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.events;

import com.eip.analytics.api.ComputeFrictionUseCase;
import com.eip.tenancy.context.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Coalesces per-event friction recompute triggers into one recompute per tenant per sweep (DEBT-017
 * residual): {@link WorkItemEventsConsumer} calls {@link #markDirty} once per distinct (deduped)
 * event instead of recomputing inline, and this component's {@link #drain} sweep — not the consumer
 * — is the only caller of {@link ComputeFrictionUseCase#compute}. A burst of N events for one
 * tenant within one sweep window therefore triggers at most one compute call, not N.
 *
 * <p><strong>Correctness under coalescing (at-least-once + coalescing = the final compute always
 * covers the last event):</strong> each drain claims a tenant by removing it from the dirty set
 * <em>before</em> computing, so a {@link #markDirty} call that lands for the same tenant while its
 * compute is in flight re-adds the tenant and is picked up by the next sweep — no mark is ever lost
 * to a race with an in-progress compute. The one honest gap is a crash <em>between</em> a mark and
 * the sweep that would have drained it: an in-memory set does not survive a restart, so a mark made
 * and then lost to a crash before the next sweep runs is not itself replayed. This is healed, not
 * by this component, but by the system around it — either a later event for the same tenant (any
 * subsequent upsert re-marks it) or the next full recompute the operator/schedule triggers; no
 * financial or safety invariant depends on sub-second recompute freshness (FrictionSummaryView
 * always carries its own {@code computedAt}, so a stale-but-uncrashed window is visible, never
 * silently wrong).
 */
@Component
@ConditionalOnProperty(prefix = "eip.events", name = "enabled", matchIfMissing = true)
public class FrictionRecomputeCoalescer {

  private static final Logger log = LoggerFactory.getLogger(FrictionRecomputeCoalescer.class);

  private final ComputeFrictionUseCase compute;
  private final Set<UUID> dirtyTenants = ConcurrentHashMap.newKeySet();
  private final Counter runs;
  private final Counter coalesced;

  /**
   * Creates the coalescer.
   *
   * @param compute the friction recompute use case
   * @param registry the Micrometer registry
   */
  public FrictionRecomputeCoalescer(ComputeFrictionUseCase compute, MeterRegistry registry) {
    this.compute = compute;
    this.runs =
        Counter.builder("eip.events.recompute.runs")
            .description("Friction recompute calls actually executed by the coalescer sweep")
            .register(registry);
    this.coalesced =
        Counter.builder("eip.events.recompute.coalesced")
            .description("Events that marked an already-dirty tenant — folded into one recompute")
            .register(registry);
  }

  /**
   * Marks a tenant dirty; a tenant already marked dirty (not yet drained) counts this call as
   * coalesced rather than adding a second pending recompute.
   *
   * @param tenantId the tenant whose events changed canonical data
   */
  public void markDirty(UUID tenantId) {
    if (!dirtyTenants.add(tenantId)) {
      coalesced.increment();
    }
  }

  /**
   * Drains the dirty set, computing each pending tenant at most once. Each tenant is unmarked
   * <em>before</em> its compute call runs, so a mark that lands concurrently for the same tenant is
   * never dropped — it simply survives for the next sweep.
   */
  @Scheduled(fixedDelayString = "${eip.events.recompute-delay-ms:5000}")
  public void drain() {
    for (UUID tenantId : Set.copyOf(dirtyTenants)) {
      if (!dirtyTenants.remove(tenantId)) {
        continue; // already claimed by a concurrent sweep (single-threaded scheduler in practice)
      }
      try {
        compute.compute(TenantContext.of(tenantId));
        runs.increment();
      } catch (RuntimeException e) {
        // Per-tenant isolation: one tenant's compute failure must never block the sweep for the
        // rest, nor crash the scheduler thread (an uncaught exception here would cancel all future
        // @Scheduled invocations of this method).
        log.error("friction recompute failed for tenant {}", tenantId, e);
      }
    }
  }
}
