/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai.api;

import org.jspecify.annotations.Nullable;

/**
 * The current tenant's AI explanation-layer policy (ADR-024). Never carries the secret's plaintext
 * — {@code hasSecret} is the only signal a caller gets that a provider API key is configured,
 * mirroring how connector secrets are never read back through any admin surface.
 *
 * @param enabled whether the AI explanation layer is turned on for this tenant (default {@code
 *     false} — opt-in)
 * @param provider the configured provider ({@code "ollama"} | {@code "openai-compatible"}), or
 *     {@code null} if never configured
 * @param baseUrl the configured provider base URL, or {@code null} if never configured
 * @param model the configured model identifier, or {@code null} if never configured
 * @param hasSecret whether a provider API key is currently stored
 * @param temperature the configured sampling temperature
 * @param maxTokens the configured maximum generation length, in tokens
 */
public record AiPolicyView(
    boolean enabled,
    @Nullable String provider,
    @Nullable String baseUrl,
    @Nullable String model,
    boolean hasSecret,
    double temperature,
    int maxTokens) {}
