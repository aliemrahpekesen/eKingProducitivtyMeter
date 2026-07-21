/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai.spi;

import org.jspecify.annotations.Nullable;

/**
 * One chat-completion response (LLM SPI 0.1, ADR-024).
 *
 * @param text the generated completion text
 * @param modelReported the model identifier the provider itself reported, if any (may differ from
 *     {@link LlmRequest#model()} for providers that alias/redirect model names)
 * @param latencyMs wall-clock time the provider call took, in milliseconds
 */
public record LlmResponse(String text, @Nullable String modelReported, long latencyMs) {}
