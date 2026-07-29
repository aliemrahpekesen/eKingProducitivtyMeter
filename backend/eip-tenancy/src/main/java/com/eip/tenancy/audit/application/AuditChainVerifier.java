/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.audit.application;

import com.eip.tenancy.api.ManageTenantsUseCase;
import com.eip.tenancy.api.ManageTenantsUseCase.TenantView;
import com.eip.tenancy.audit.api.AuditChainVerifierUseCase;
import com.eip.tenancy.audit.persistence.AuditEventRepository;
import com.eip.tenancy.audit.persistence.AuditEventRepository.ChainedRow;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.tx.TenantTransactionRunner;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The chain-integrity verifier (SecurityModel §11): re-walks each tenant's already-chained rows in
 * order, recomputing every row's hash from its stored fields and comparing it to what is stored.
 * The first mismatch for a tenant is reported and the walk for that tenant stops there — once one
 * hash is wrong, every hash after it is definitionally wrong too (each links to the previous), so
 * continuing adds nothing but noise.
 *
 * <p>Scope fence (see this module's root package-info): this MVP verifies what IS chained; it does
 * not additionally treat unchained rows older than the chain-lag SLO as failures (SecurityModel
 * §11's lag-SLO clause) — that is a deferred volume-scaling refinement, not a correctness gap in
 * what it does check.
 */
@Service
public class AuditChainVerifier implements AuditChainVerifierUseCase {

  private static final Logger log = LoggerFactory.getLogger(AuditChainVerifier.class);

  private final ManageTenantsUseCase tenants;
  private final TenantTransactionRunner txRunner;
  private final AuditEventRepository repository;
  private final AuditRecordCanonicalizer canonicalizer;
  private final int maxRowsPerTenant;
  private final Counter tamperFailures;
  private final Counter sweepFailures;

  /**
   * Creates the verifier.
   *
   * @param tenants the platform tenant directory
   * @param txRunner the tenant-aware transaction boundary
   * @param repository the audit-event repository
   * @param canonicalizer the per-row hash computation
   * @param registry the Micrometer registry
   * @param maxRowsPerTenant the maximum chained rows re-verified per tenant per sweep (a defensive
   *     cap — see {@link AuditEventRepository#findChainedOrdered})
   */
  public AuditChainVerifier(
      ManageTenantsUseCase tenants,
      TenantTransactionRunner txRunner,
      AuditEventRepository repository,
      AuditRecordCanonicalizer canonicalizer,
      MeterRegistry registry,
      @Value("${eip.audit.verify-max-rows:50000}") int maxRowsPerTenant) {
    this.tenants = tenants;
    this.txRunner = txRunner;
    this.repository = repository;
    this.canonicalizer = canonicalizer;
    this.maxRowsPerTenant = maxRowsPerTenant;
    this.tamperFailures =
        Counter.builder("eip.job.failures")
            .tag("job", "audit-verify")
            .description("Scheduled job failures (audit-verify, audit-chain, retention-purge, ...)")
            .register(registry);
    this.sweepFailures =
        Counter.builder("eip.job.failures")
            .tag("job", "audit-verify-sweep")
            .description("Verifier sweep runs that failed to complete for a tenant (not tamper)")
            .register(registry);
  }

  @Override
  public VerifySweepResult verifyChains() {
    int verified = 0;
    int tampered = 0;
    for (TenantView tenant : tenants.list()) {
      try {
        boolean intact =
            txRunner.call(TenantContext.of(tenant.id()), () -> verifyOneTenant(tenant.id()));
        verified++;
        if (!intact) {
          tampered++;
        }
      } catch (RuntimeException e) {
        sweepFailures.increment();
        log.error("audit chain verification failed to run for tenant {}", tenant.id(), e);
      }
    }
    return new VerifySweepResult(verified, tampered);
  }

  private boolean verifyOneTenant(UUID tenantId) {
    List<ChainedRow> rows = repository.findChainedOrdered(maxRowsPerTenant);
    byte[] expectedPrev = canonicalizer.genesisHash(tenantId);
    for (ChainedRow row : rows) {
      byte[] expectedHash = canonicalizer.computeHash(expectedPrev, tenantId, row.fields());
      if (!Arrays.equals(row.prevHash(), expectedPrev)
          || !Arrays.equals(row.hash(), expectedHash)) {
        tamperFailures.increment();
        log.warn(
            "audit chain tamper detected for tenant {} at first-bad auditId {}",
            tenantId,
            row.id());
        return false;
      }
      expectedPrev = row.hash();
    }
    return true;
  }
}
