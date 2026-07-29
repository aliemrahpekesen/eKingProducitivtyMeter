/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai.api;

/**
 * Generates an AI narrative of a deterministic report's already-generated content (ADR-024).
 * Composes the exact same {@code totals}/{@code teams} content the report document carries; never
 * recomputes anything. See {@link ReportNarrativeInput}'s javadoc for why this takes the report's
 * content directly rather than an id.
 */
public interface NarrateReportUseCase {

  /**
   * Narrates a report's content for the current tenant.
   *
   * @param input the report's already-generated content
   * @return the generated narrative
   * @throws AiDisabledException if the current tenant has not enabled the AI explanation layer
   * @throws LlmUnavailableException if the configured provider is unreachable, times out, or
   *     returns a non-2xx response
   * @throws NarrativeRejectedException if the generated narrative cites a number not present in the
   *     source data
   */
  ExplanationView narrate(ReportNarrativeInput input);
}
