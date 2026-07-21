/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai.api;

import java.time.Instant;
import java.util.List;

/**
 * One AI-generated explanation or report narrative (ADR-024): prose composed only from
 * already-computed, team-level deterministic reads, numerically cross-checked before being returned
 * — every number the narrative cites is verified present in the source data it was given ({@code
 * citedNumbers}), or the call is rejected ({@link NarrativeRejectedException}) instead of being
 * returned. Stateless: nothing here is persisted beyond the hash-only {@code ai.llm_call_audit}
 * row.
 *
 * @param narrative the generated prose
 * @param provider the provider that generated it ({@code "ollama"} | {@code "openai-compatible"})
 * @param model the model identifier used
 * @param citedNumbers every numeric token found in {@code narrative}, normalized, all verified
 *     present in the source data
 * @param generatedAt when this explanation was generated
 * @param disclaimer the fixed {@link #DISCLAIMER} text, carried on every response so a client never
 *     has to hardcode it
 */
public record ExplanationView(
    String narrative,
    String provider,
    String model,
    List<String> citedNumbers,
    Instant generatedAt,
    String disclaimer) {

  /**
   * The fixed en-US disclaimer every {@link ExplanationView} carries (ADR-024): AI explains, never
   * computes, and the deterministic numbers shown remain the source of truth.
   */
  public static final String DISCLAIMER =
      "AI-generated explanation of deterministic metrics. Verify against the numbers shown; the"
          + " AI did not compute anything.";
}
