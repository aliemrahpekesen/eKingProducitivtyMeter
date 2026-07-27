/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.audit.api;

/**
 * The chain-integrity verifier's port (SecurityModel §11): re-walks each tenant's already-chained
 * rows, recomputes every hash, and compares it to what is stored. The {@code eip-app} scheduled
 * bean is the only intended caller.
 */
public interface AuditChainVerifierUseCase {

  /**
   * Runs one verification sweep across every tenant. A tamper finding for a tenant increments the
   * {@code eip_job_failures_total{job="audit-verify"}} counter and logs the tenant + first-bad
   * audit id (never row content); it does not stop verification of the remaining tenants.
   *
   * @return the sweep's tally
   */
  VerifySweepResult verifyChains();

  /**
   * One sweep's tally.
   *
   * @param tenantsVerified tenants whose chain was walked this sweep
   * @param tenantsTampered tenants where a stored hash did not match its recomputed value
   */
  record VerifySweepResult(int tenantsVerified, int tenantsTampered) {}
}
