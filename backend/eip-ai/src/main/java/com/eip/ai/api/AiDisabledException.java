/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai.api;

/**
 * Unchecked: the current tenant has not enabled the AI explanation layer ({@link
 * ManageAiPolicyUseCase}, ADR-024 — opt-in, default OFF). Not part of the sealed {@code
 * com.eip.core.error} taxonomy (mirrors {@code com.eip.ingestion.api.SourceSyncException}'s
 * module-local exception style); {@code eip-app}'s {@code ApiExceptionHandler} maps this to a 409
 * {@code /problems/ai-disabled}.
 */
public final class AiDisabledException extends RuntimeException {

  /**
   * Creates the failure.
   *
   * @param message what is disabled
   */
  public AiDisabledException(String message) {
    super(message);
  }
}
