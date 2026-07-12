/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.api;

import com.eip.tenancy.context.TenantContext;

/**
 * Normalizes staged raw records into the canonical model (work items + transitions, pull requests +
 * reviews, builds, quality gates) and maintains {@code core.external_ref} identity anchors —
 * eip-ingestion is the single canonical writer (ADR-019). Set-based and idempotent: re-normalizing
 * the same staged data changes nothing.
 */
public interface NormalizeStagedDataUseCase {

  /**
   * Normalizes all staged streams for the given tenant.
   *
   * @param tenant the tenant to normalize for
   */
  void normalize(TenantContext tenant);
}
