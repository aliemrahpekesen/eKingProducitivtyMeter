/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai.spi;

/**
 * One chat-completion request (LLM SPI 0.1, ADR-024): a system prompt (guardrails), a user prompt
 * (the deterministic data to explain), and generation parameters.
 *
 * @param systemPrompt the guardrail/instruction prompt
 * @param userPrompt the data payload to explain, composed only from already-computed deterministic
 *     reads
 * @param model the provider-specific model identifier
 * @param temperature the sampling temperature
 * @param maxTokens the maximum tokens the provider should generate
 */
public record LlmRequest(
    String systemPrompt, String userPrompt, String model, double temperature, int maxTokens) {}
