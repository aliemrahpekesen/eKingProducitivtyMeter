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
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Blocking JSON-over-HTTP helper for connector implementations. One shared client per connector;
 * bounded timeouts; responses parsed with Jackson. Secrets are used for the Authorization header
 * only and never appear in errors.
 *
 * <p><b>Blocking is sanctioned here (M2b Wave 3E):</b> every {@code Connector.sync} runs on a
 * dedicated virtual thread, outside any database transaction (BackendPlan §6) — the throttle sleep
 * and the {@code 429} backoff below block only that calling virtual thread, never a platform thread
 * or a transaction-holding connection.
 *
 * <p><b>Rate limiting:</b> every request made through one {@code SourceHttp} instance is spaced at
 * least {@code 1 / maxRequestsPerSecond} apart from the previous one, enforced by sleeping the
 * remaining interval immediately before sending — guarded by a monotonic {@link System#nanoTime()}
 * mark (never wall-clock, so a system clock adjustment cannot shrink or widen the spacing) and
 * {@code synchronized} so concurrent callers on the same instance serialize through the same mark.
 * A {@code 429} response additionally honors the {@code Retry-After} header (seconds form; capped
 * at {@value #MAX_RETRY_AFTER_SECONDS}s) and is retried up to {@value #MAX_429_RETRIES} times
 * before {@link SourceHttpException} is thrown — a connector's {@code sync} never busy-loops
 * against a rate-limited source.
 */
public final class SourceHttp {

  /** A parsed response: status + JSON body (NullNode for empty bodies). */
  public record JsonResponse(int status, JsonNode body) {}

  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final double DEFAULT_MAX_REQUESTS_PER_SECOND = 10.0;
  private static final int MAX_429_RETRIES = 2;
  private static final long MAX_RETRY_AFTER_SECONDS = 30;
  private static final long DEFAULT_RETRY_AFTER_SECONDS = 1; // no/unparseable header (v0.1)

  private final HttpClient client;
  private final ObjectMapper mapper = new ObjectMapper();
  private final long minIntervalNanos;
  private long lastRequestNanos;

  /** Creates the helper with a fresh JDK HttpClient, throttled to 10 requests/second. */
  public SourceHttp() {
    this(DEFAULT_MAX_REQUESTS_PER_SECOND);
  }

  /**
   * Creates the helper with an explicit per-instance throttle.
   *
   * @param maxRequestsPerSecond the maximum sustained request rate enforced for every call made
   *     through this instance; must be positive
   */
  public SourceHttp(double maxRequestsPerSecond) {
    if (!(maxRequestsPerSecond > 0)) {
      throw new IllegalArgumentException("maxRequestsPerSecond must be positive");
    }
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    this.minIntervalNanos = (long) (1_000_000_000.0 / maxRequestsPerSecond);
    // Backdated so the very first call through a fresh instance never waits.
    this.lastRequestNanos = System.nanoTime() - minIntervalNanos;
  }

  /**
   * Executes an authorized GET and parses the JSON body. Blocks (per-instance throttle spacing,
   * then any {@code 429} backoff/retry) before returning or throwing — see the class javadoc.
   *
   * @param url the absolute request URL
   * @param authorization the Authorization header value
   * @return status + parsed body
   */
  public JsonResponse getJson(String url, String authorization) {
    int retries = 0;
    while (true) {
      awaitThrottle();
      HttpResponse<String> response = send(url, authorization);
      if (response.statusCode() == 429) {
        if (retries >= MAX_429_RETRIES) {
          throw new SourceHttpException(
              "rate limited: " + url + " still returned 429 after " + MAX_429_RETRIES + " retries");
        }
        retries++;
        sleepUninterruptibly(retryAfterNanos(response));
        continue;
      }
      return new JsonResponse(response.statusCode(), parseBody(response.body()));
    }
  }

  /** Sends the request, translating transport failures into {@link SourceHttpException}. */
  private HttpResponse<String> send(String url, String authorization) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(TIMEOUT)
              .header("Authorization", authorization)
              .header("Accept", "application/json")
              .GET()
              .build();
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new SourceHttpException("source unreachable: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SourceHttpException("interrupted while calling source", e);
    }
  }

  private JsonNode parseBody(@Nullable String body) {
    try {
      return (body == null || body.isBlank()) ? mapper.nullNode() : mapper.readTree(body);
    } catch (IOException e) {
      throw new SourceHttpException("invalid JSON body: " + e.getMessage(), e);
    }
  }

  /**
   * Blocks the calling thread until at least {@code minIntervalNanos} have elapsed since the
   * previous request through this instance; records the new mark before returning.
   */
  private synchronized void awaitThrottle() {
    long now = System.nanoTime();
    long waitNanos = (lastRequestNanos + minIntervalNanos) - now;
    if (waitNanos > 0) {
      sleepUninterruptibly(waitNanos);
      now = System.nanoTime();
    }
    lastRequestNanos = now;
  }

  /**
   * Resolves the backoff for one {@code 429}, from {@code Retry-After} (seconds form) or a short
   * default, capped at {@value #MAX_RETRY_AFTER_SECONDS}s.
   */
  private static long retryAfterNanos(HttpResponse<String> response) {
    long seconds =
        response
            .headers()
            .firstValue("Retry-After")
            .map(SourceHttp::parseSeconds)
            .orElse(DEFAULT_RETRY_AFTER_SECONDS);
    return TimeUnit.SECONDS.toNanos(Math.min(Math.max(seconds, 0), MAX_RETRY_AFTER_SECONDS));
  }

  private static long parseSeconds(String value) {
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      // HTTP-date form or malformed value: fall back to the short default (v0.1, documented).
      return DEFAULT_RETRY_AFTER_SECONDS;
    }
  }

  private static void sleepUninterruptibly(long nanos) {
    if (nanos <= 0) {
      return;
    }
    try {
      TimeUnit.NANOSECONDS.sleep(nanos);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
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
    SourceHttpException(String message) {
      super(message);
    }

    SourceHttpException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
