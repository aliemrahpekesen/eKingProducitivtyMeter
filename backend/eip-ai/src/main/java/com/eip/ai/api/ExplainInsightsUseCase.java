/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai.api;

/**
 * Generates an AI explanation of the current tenant's already-computed dashboard metrics — friction
 * summary, weekly trends, and recommendations — for the current tenant (ADR-024). Composes the
 * exact same deterministic reads the dashboard shows; never recomputes anything.
 */
public interface ExplainInsightsUseCase {

  /**
   * Explains the current tenant's dashboard for the given trend window.
   *
   * @param weeks the requested trend window width, in weeks (must be in {@code 4..52})
   * @return the generated explanation
   * @throws com.eip.core.error.ValidationException if {@code weeks} is outside {@code 4..52}
   * @throws AiDisabledException if the current tenant has not enabled the AI explanation layer
   * @throws LlmUnavailableException if the configured provider is unreachable, times out, or
   *     returns a non-2xx response
   * @throws NarrativeRejectedException if the generated narrative cites a number not present in the
   *     source data
   */
  ExplanationView explain(int weeks);
}
