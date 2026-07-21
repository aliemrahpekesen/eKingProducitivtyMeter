/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai.api;

import org.jspecify.annotations.Nullable;

/**
 * Reads and updates the current tenant's AI explanation-layer policy (ADR-024: opt-in, default
 * OFF). Fails closed when no tenant is bound to the calling thread.
 */
public interface ManageAiPolicyUseCase {

  /**
   * Reads the current tenant's policy.
   *
   * @return the current policy (a default, disabled view if never configured)
   */
  AiPolicyView get();

  /**
   * Updates the current tenant's policy. Any {@code null} field on {@code command} (other than
   * {@code enabled}, which is always applied) keeps its current stored value.
   *
   * @param command the update
   * @return the policy after the update
   * @throws com.eip.core.error.ValidationException if {@code command.enabled()} is {@code true} and
   *     the resulting policy would be missing a provider, base URL, or model; if {@code provider}
   *     is not one of {@code "ollama"}/{@code "openai-compatible"}; or if the resulting provider is
   *     {@code "openai-compatible"} and no secret is/becomes configured
   */
  AiPolicyView update(UpdateAiPolicyCommand command);

  /**
   * A policy update. Every field except {@code enabled} is optional: {@code null} means "keep the
   * currently stored value" (so, e.g., rotating the secret alone does not require repeating the
   * provider/model).
   *
   * @param enabled whether the AI explanation layer should be turned on for this tenant
   * @param provider the provider to configure ({@code "ollama"} | {@code "openai-compatible"}), or
   *     {@code null} to keep the current value
   * @param baseUrl the provider base URL, or {@code null} to keep the current value
   * @param model the model identifier, or {@code null} to keep the current value
   * @param secret a new provider API key/token to store (envelope-encrypted), or {@code null} to
   *     keep the currently stored secret unchanged
   * @param temperature the sampling temperature, or {@code null} to keep the current value
   * @param maxTokens the maximum generation length in tokens, or {@code null} to keep the current
   *     value
   */
  record UpdateAiPolicyCommand(
      boolean enabled,
      @Nullable String provider,
      @Nullable String baseUrl,
      @Nullable String model,
      @Nullable String secret,
      @Nullable Double temperature,
      @Nullable Integer maxTokens) {}
}
