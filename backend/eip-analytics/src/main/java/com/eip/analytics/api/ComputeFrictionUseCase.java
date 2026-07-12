/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.analytics.api;

import com.eip.tenancy.context.TenantContext;

/**
 * Correlates the tenant's canonical flow (work item → PR → build → quality gate), computes the
 * deterministic team-level Engineering Friction v0.1, and persists correlation evidence, the
 * versioned metric definition, {@code metric_fact} history, and the current read model. Set-based
 * and idempotent: recomputing over unchanged canonical data reproduces identical values.
 */
public interface ComputeFrictionUseCase {

  /**
   * Runs one friction computation for the given tenant.
   *
   * @param tenant the tenant to compute for
   * @return teams computed and work items correlated
   */
  FrictionComputation compute(TenantContext tenant);

  /**
   * The outcome of one friction computation.
   *
   * @param teamsComputed teams with a computed friction row
   * @param itemsCorrelated work items correlated into evidence rows
   */
  record FrictionComputation(int teamsComputed, int itemsCorrelated) {}
}
