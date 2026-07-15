/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Blocking JSON-over-HTTP helper for connector implementations. One shared client per connector;
 * bounded timeouts; responses parsed with Jackson. Secrets are used for the Authorization header
 * only and never appear in errors.
 */
public final class SourceHttp {

  /** A parsed response: status + JSON body (NullNode for empty bodies). */
  public record JsonResponse(int status, JsonNode body) {}

  private static final Duration TIMEOUT = Duration.ofSeconds(20);

  private final HttpClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  /** Creates the helper with a fresh JDK HttpClient. */
  public SourceHttp() {
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  /**
   * Executes an authorized GET and parses the JSON body.
   *
   * @param url the absolute request URL
   * @param authorization the Authorization header value
   * @return status + parsed body
   */
  public JsonResponse getJson(String url, String authorization) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(TIMEOUT)
              .header("Authorization", authorization)
              .header("Accept", "application/json")
              .GET()
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      String body = response.body();
      JsonNode json = (body == null || body.isBlank()) ? mapper.nullNode() : mapper.readTree(body);
      return new JsonResponse(response.statusCode(), json);
    } catch (IOException e) {
      throw new SourceHttpException("source unreachable: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SourceHttpException("interrupted while calling source", e);
    }
  }

  /**
   * Builds a Basic Authorization header value.
   *
   * @param user the user/email part
   * @param secret the password/token part
   * @return the header value
   */
  public static String basic(String user, String secret) {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString((user + ":" + secret).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Builds a Bearer Authorization header value.
   *
   * @param token the token
   * @return the header value
   */
  public static String bearer(String token) {
    return "Bearer " + token;
  }

  /** Unchecked transport failure (never carries secret material). */
  public static final class SourceHttpException extends RuntimeException {
    SourceHttpException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
