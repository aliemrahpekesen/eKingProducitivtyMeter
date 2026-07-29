/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai.providers;

import com.eip.ai.spi.LlmClient;
import com.eip.ai.spi.LlmRequest;
import com.eip.ai.spi.LlmResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * {@link LlmClient} for a self-hosted Ollama endpoint (the air-gap-friendly default, ADR-024):
 * {@code POST {baseUrl}/api/chat} with a two-message ({@code system}/{@code user}) chat body and
 * {@code stream: false}. No Authorization header — Ollama is assumed to run on a trusted internal
 * network the tenant points {@code baseUrl} at.
 */
final class OllamaClient implements LlmClient {

  private final String baseUrl;
  private final AiHttp http;

  OllamaClient(String baseUrl, AiHttp http) {
    this.baseUrl = baseUrl;
    this.http = http;
  }

  @Override
  public LlmResponse complete(LlmRequest request) {
    long start = System.nanoTime();
    Map<String, Object> body =
        Map.of(
            "model", request.model(),
            "messages",
                List.of(
                    Map.of("role", "system", "content", request.systemPrompt()),
                    Map.of("role", "user", "content", request.userPrompt())),
            "stream", false,
            "options",
                Map.of(
                    "temperature", request.temperature(),
                    "num_predict", request.maxTokens()));
    JsonNode json = http.postJson(baseUrl + "/api/chat", Map.of(), body);
    long latencyMs = (System.nanoTime() - start) / 1_000_000;
    String text = json.path("message").path("content").asText("");
    @Nullable String modelReported = json.hasNonNull("model") ? json.get("model").asText() : null;
    return new LlmResponse(text, modelReported, latencyMs);
  }
}
