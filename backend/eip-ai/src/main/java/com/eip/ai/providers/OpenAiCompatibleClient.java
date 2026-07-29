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
 * {@link LlmClient} for any OpenAI-compatible chat-completions endpoint (ADR-024): {@code POST
 * {baseUrl}/v1/chat/completions} with a standard {@code messages}/{@code temperature}/{@code
 * max_tokens} body and a Bearer {@code Authorization} header carrying the tenant's stored API key.
 * The key is used for the header only and never appears in any error message.
 */
final class OpenAiCompatibleClient implements LlmClient {

  private final String baseUrl;
  private final String apiKey;
  private final AiHttp http;

  OpenAiCompatibleClient(String baseUrl, String apiKey, AiHttp http) {
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
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
            "temperature", request.temperature(),
            "max_tokens", request.maxTokens());
    JsonNode json =
        http.postJson(
            baseUrl + "/v1/chat/completions", Map.of("Authorization", "Bearer " + apiKey), body);
    long latencyMs = (System.nanoTime() - start) / 1_000_000;
    JsonNode choices = json.path("choices");
    String text =
        choices.isArray() && !choices.isEmpty()
            ? choices.get(0).path("message").path("content").asText("")
            : "";
    @Nullable String modelReported = json.hasNonNull("model") ? json.get("model").asText() : null;
    return new LlmResponse(text, modelReported, latencyMs);
  }
}
