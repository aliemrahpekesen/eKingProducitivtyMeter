/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.audit.api;

/**
 * The async batch chainer's port (SecurityModel §11): assigns {@code prev_hash}/{@code hash} to
 * rows the write path inserted unchained, in strict per-tenant {@code (occurred_at, id)} order. The
 * {@code eip-app} scheduled bean is the only intended caller.
 */
public interface AuditChainerUseCase {

  /**
   * Runs one sweep: for every tenant with unchained rows, chains up to that tenant's configured
   * per-sweep batch. One tenant's failure is isolated — it never blocks or fails the sweep for
   * other tenants.
   *
   * @return the sweep's tally
   */
  ChainSweepResult chainPendingRows();

  /**
   * One sweep's tally.
   *
   * @param tenantsWithWork tenants that had at least one row chained this sweep
   * @param rowsChained total rows chained across all tenants this sweep
   */
  record ChainSweepResult(int tenantsWithWork, int rowsChained) {}
}
