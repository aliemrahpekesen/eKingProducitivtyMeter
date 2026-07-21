/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai.providers;

import com.eip.ai.api.LlmUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Minimal blocking JSON-over-HTTP POST helper for LLM provider clients (v0.1: 60s timeout, no
 * retries — mirrors {@code com.eip.connectors.http.SourceHttp}'s framework-free style, but simpler:
 * a single request shape, no throttling or {@code 429} backoff, since a tenant's own configured LLM
 * endpoint is not a shared, rate-limited third-party API). Never logs or echoes the
 * request/response body (may embed tenant metric data or a secret Authorization header) — failures
 * report only the URL and HTTP status/transport error.
 */
final class AiHttp {

  private static final Duration TIMEOUT = Duration.ofSeconds(60);

  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(TIMEOUT)
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();
  private final ObjectMapper mapper = new ObjectMapper();

  /**
   * Sends a JSON POST and parses the JSON response body.
   *
   * @param url the absolute request URL
   * @param headers extra request headers (e.g. {@code Authorization}); never logged
   * @param body the request body, serialized to JSON
   * @return the parsed JSON response body
   * @throws LlmUnavailableException if the endpoint is unreachable, times out, or returns a non-2xx
   *     response
   */
  JsonNode postJson(String url, Map<String, String> headers, Object body) {
    HttpRequest request = buildRequest(url, headers, body);
    HttpResponse<String> response;
    try {
      response = client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new LlmUnavailableException("LLM endpoint unreachable: " + url, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new LlmUnavailableException("interrupted while calling LLM endpoint: " + url, e);
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new LlmUnavailableException(
          "LLM endpoint " + url + " returned HTTP " + response.statusCode());
    }
    return parseBody(url, response.body());
  }

  private HttpRequest buildRequest(String url, Map<String, String> headers, Object body) {
    String json;
    try {
      json = mapper.writeValueAsString(body);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new LlmUnavailableException("failed to serialize LLM request body", e);
    }
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json));
    headers.forEach(builder::header);
    return builder.build();
  }

  private JsonNode parseBody(String url, @Nullable String body) {
    try {
      return (body == null || body.isBlank()) ? mapper.nullNode() : mapper.readTree(body);
    } catch (IOException e) {
      throw new LlmUnavailableException("invalid JSON response from LLM endpoint: " + url, e);
    }
  }
}
