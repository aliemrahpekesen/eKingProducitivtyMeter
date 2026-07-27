/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.audit;

import com.eip.tenancy.audit.api.AuditChainVerifierUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers the audit chain-integrity verifier on a cron schedule (DEBT-024 Wave 3A, SecurityModel
 * §11). Mirrors {@code ScheduledReportRunner}'s idiom: eip-app wires and invokes the {@code
 * eip-tenancy} port only ({@link AuditChainVerifierUseCase}) — the verification logic itself lives
 * in the owning module (ADR-019). Gated by {@code eip.audit.enabled} (default {@code true}).
 */
@Component
@ConditionalOnProperty(prefix = "eip.audit", name = "enabled", matchIfMissing = true)
public class AuditChainVerifierScheduler {

  private static final Logger log = LoggerFactory.getLogger(AuditChainVerifierScheduler.class);

  private final AuditChainVerifierUseCase verifier;

  /**
   * Creates the scheduler.
   *
   * @param verifier the audit chain-verifier port
   */
  public AuditChainVerifierScheduler(AuditChainVerifierUseCase verifier) {
    this.verifier = verifier;
  }

  /** Runs one verification sweep on {@code eip.audit.verify-cron} (default hourly). */
  @Scheduled(cron = "${eip.audit.verify-cron:0 0 * * * *}")
  public void verify() {
    try {
      verifier.verifyChains();
    } catch (RuntimeException e) {
      // AuditChainVerifierUseCase already isolates per-tenant failures internally; this guards the
      // @Scheduled thread itself from an uncaught exception cancelling all future cron firings.
      log.error("audit chain verification sweep failed unexpectedly", e);
    }
  }
}
