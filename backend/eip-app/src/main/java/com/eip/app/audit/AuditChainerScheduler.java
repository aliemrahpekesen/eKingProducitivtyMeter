/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.audit;

import com.eip.tenancy.audit.api.AuditChainerUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers the audit hash-chainer sweep on a fixed delay (DEBT-024 Wave 3A, SecurityModel §11).
 * Mirrors {@code OutboxRelayScheduler}/{@code FrictionRecomputeCoalescer}'s idiom: eip-app wires
 * and invokes the {@code eip-tenancy} port only ({@link AuditChainerUseCase}) — the chaining logic
 * itself lives in the owning module (ADR-019). Gated by {@code eip.audit.enabled} (default {@code
 * true}).
 */
@Component
@ConditionalOnProperty(prefix = "eip.audit", name = "enabled", matchIfMissing = true)
public class AuditChainerScheduler {

  private static final Logger log = LoggerFactory.getLogger(AuditChainerScheduler.class);

  private final AuditChainerUseCase chainer;

  /**
   * Creates the scheduler.
   *
   * @param chainer the audit chainer port
   */
  public AuditChainerScheduler(AuditChainerUseCase chainer) {
    this.chainer = chainer;
  }

  /** Runs one chainer sweep every {@code eip.audit.chainer-delay-ms} (default 5000). */
  @Scheduled(fixedDelayString = "${eip.audit.chainer-delay-ms:5000}")
  public void chain() {
    try {
      chainer.chainPendingRows();
    } catch (RuntimeException e) {
      // AuditChainerUseCase already isolates per-tenant failures internally; this guards the
      // @Scheduled thread itself — an uncaught exception here would cancel all future invocations
      // of this method (Spring cancels a fixed-delay task's future runs on an uncaught throw).
      log.error("audit chainer sweep failed unexpectedly", e);
    }
  }
}
