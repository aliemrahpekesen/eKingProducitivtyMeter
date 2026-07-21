/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai.providers;

import com.eip.ai.api.LlmUnavailableException;
import com.eip.ai.spi.LlmClient;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Selects an {@link LlmClient} implementation for the current tenant's configured provider
 * (ADR-024) — the one public entry point into this package. No egress happens here or anywhere in
 * this package on its own: a client only ever calls out when {@code com.eip.ai.application} invokes
 * {@link LlmClient#complete}, and only to the tenant's own explicitly configured {@code baseUrl}.
 */
@Component
public final class LlmClientFactory {

  private final AiHttp http = new AiHttp();

  /**
   * Builds the client for one provider configuration.
   *
   * @param provider {@code "ollama"} | {@code "openai-compatible"}
   * @param baseUrl the tenant-configured provider base URL
   * @param apiKey the revealed provider API key, required for {@code "openai-compatible"} and
   *     ignored for {@code "ollama"}
   * @return the selected client
   * @throws LlmUnavailableException if {@code provider} is unknown, or if {@code
   *     "openai-compatible"} is requested with no {@code apiKey}
   */
  public LlmClient forPolicy(String provider, String baseUrl, @Nullable String apiKey) {
    return switch (provider) {
      case "ollama" -> new OllamaClient(baseUrl, http);
      case "openai-compatible" -> new OpenAiCompatibleClient(baseUrl, requireApiKey(apiKey), http);
      default -> throw new LlmUnavailableException("unknown LLM provider: " + provider);
    };
  }

  private static String requireApiKey(@Nullable String apiKey) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new LlmUnavailableException("openai-compatible provider requires an API key");
    }
    return apiKey;
  }
}
