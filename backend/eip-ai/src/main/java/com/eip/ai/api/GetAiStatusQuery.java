/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai.api;

/**
 * Reads whether the AI explanation layer is turned on for the current tenant — a minimal,
 * dashboard-view-permission-gated signal a client uses to decide whether to offer AI explanation
 * actions at all, distinct from {@link ManageAiPolicyUseCase#get()} (which exposes the full policy
 * and requires the policy-management permission). Fails closed when no tenant is bound to the
 * calling thread.
 */
public interface GetAiStatusQuery {

  /**
   * Returns whether the AI explanation layer is enabled for the current tenant.
   *
   * @return {@code true} if enabled
   */
  boolean enabled();
}
