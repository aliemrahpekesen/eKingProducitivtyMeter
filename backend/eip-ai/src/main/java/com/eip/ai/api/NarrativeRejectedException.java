/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai.api;

/**
 * Unchecked: a generated narrative cited at least one number that does not appear in the
 * deterministic data it was given ({@code NumericCrossChecker}, ADR-024) and was discarded rather
 * than returned. The message deliberately reports only a count, never the offending numbers
 * themselves — the narrative text is LLM output and this exception's message ends up in the
 * hash-only {@code ai.llm_call_audit.error} column, which never carries response content (V7
 * migration comment). Not part of the sealed {@code com.eip.core.error} taxonomy; {@code eip-app}'s
 * {@code ApiExceptionHandler} maps this to a 502 {@code /problems/ai-rejected}.
 */
public final class NarrativeRejectedException extends RuntimeException {

  /**
   * Creates the failure.
   *
   * @param message a content-free summary (e.g. a count of rejected numbers)
   */
  public NarrativeRejectedException(String message) {
    super(message);
  }
}
