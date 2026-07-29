/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.http;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eip.connectors.http.SourceHttp.JsonResponse;
import com.eip.connectors.http.SourceHttp.SourceHttpException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link SourceHttp}'s per-instance rate limiting (M2b Wave 3E): the minimum-interval
 * throttle actually spaces requests, a {@code 429} is retried honoring {@code Retry-After} and
 * eventually succeeds, and a source stuck at {@code 429} fails loudly instead of looping forever.
 */
class SourceHttpTest {

  private static final String AUTH = "Bearer test-token";

  private WireMockServer source;

  @BeforeEach
  void start() {
    source = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    source.start();
  }

  @AfterEach
  void stop() {
    source.stop();
  }

  @Test
  void throttles_two_immediate_calls_to_at_least_the_configured_interval() {
    source.stubFor(
        get(urlPathEqualTo("/ping"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{}")));
    // 5 requests/second -> 200ms minimum spacing; two back-to-back calls must take at least that
    // long. Generous (>=150ms, not the full 200ms) to absorb scheduling jitter without flaking.
    SourceHttp http = new SourceHttp(5.0);

    long start = System.nanoTime();
    http.getJson(source.url("/ping"), AUTH);
    http.getJson(source.url("/ping"), AUTH);
    long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

    assertThat(elapsedMillis).isGreaterThanOrEqualTo(150);
  }

  @Test
  void retries_a_429_honoring_retry_after_then_succeeds() {
    source.stubFor(
        get(urlPathEqualTo("/flaky"))
            .inScenario("rate-limit-then-ok")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
            .willSetStateTo("recovered"));
    source.stubFor(
        get(urlPathEqualTo("/flaky"))
            .inScenario("rate-limit-then-ok")
            .whenScenarioStateIs("recovered")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"ok\":true}")));

    SourceHttp http = new SourceHttp(100.0); // fast throttle so the test itself stays quick
    JsonResponse response = http.getJson(source.url("/flaky"), AUTH);

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body().path("ok").asBoolean()).isTrue();
  }

  @Test
  void throws_after_exhausting_retries_against_a_source_stuck_at_429() {
    source.stubFor(
        get(urlPathEqualTo("/stuck"))
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0")));

    SourceHttp http = new SourceHttp(100.0);

    assertThatThrownBy(() -> http.getJson(source.url("/stuck"), AUTH))
        .isInstanceOf(SourceHttpException.class)
        .hasMessageContaining("rate limited");
  }
}
