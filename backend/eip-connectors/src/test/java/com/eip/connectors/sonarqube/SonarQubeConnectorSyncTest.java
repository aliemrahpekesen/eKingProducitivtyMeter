/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.sonarqube;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.eip.connectors.spi.ConnectorConfig;
import com.eip.connectors.spi.RawRecord;
import com.eip.connectors.spi.SyncContext;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Proves the real SonarQube connector against a WireMock'd SonarQube: paginated project sync mapped
 * to canonical {@code quality_gate} raw records at both project and pull-request scope, strict
 * ISO-8601 instants derived only from real analysis dates (never fabricated), and graceful
 * tolerance of a 404 on the (edition-gated) pull-request-decoration endpoint.
 */
class SonarQubeConnectorSyncTest {

  private static final WireMockServer SONAR =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());

  @BeforeAll
  static void start() {
    SONAR.start();
    SONAR.stubFor(
        get(urlPathEqualTo("/api/projects/search"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"paging":{"pageIndex":1,"pageSize":100,"total":2},
                         "components":[{"key":"proj-a"},{"key":"proj-b"}]}
                        """)));
    SONAR.stubFor(
        get(urlPathEqualTo("/api/qualitygates/project_status"))
            .withQueryParam("projectKey", equalTo("proj-a"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"projectStatus\":{\"status\":\"OK\"}}")));
    SONAR.stubFor(
        get(urlPathEqualTo("/api/qualitygates/project_status"))
            .withQueryParam("projectKey", equalTo("proj-b"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"projectStatus\":{\"status\":\"ERROR\"}}")));
    SONAR.stubFor(
        get(urlPathEqualTo("/api/project_analyses/search"))
            .withQueryParam("project", equalTo("proj-a"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"analyses\":[{\"key\":\"AU-1\",\"date\":\"2026-01-05T09:00:00+0000\"}]}")));
    SONAR.stubFor(
        get(urlPathEqualTo("/api/project_analyses/search"))
            .withQueryParam("project", equalTo("proj-b"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"analyses\":[{\"key\":\"AU-2\",\"date\":\"2026-01-06T09:00:00+0000\"}]}")));
    SONAR.stubFor(
        get(urlPathEqualTo("/api/project_pull_requests/list"))
            .withQueryParam("project", equalTo("proj-a"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"pullRequests\":[{\"key\":\"7\",\"status\":{\"qualityGateStatus\":\"OK\"},"
                            + "\"analysisDate\":\"2026-01-05T10:00:00+0000\"}]}")));
    SONAR.stubFor(
        get(urlPathEqualTo("/api/project_pull_requests/list"))
            .withQueryParam("project", equalTo("proj-b"))
            .willReturn(aResponse().withStatus(404)));
  }

  @AfterAll
  static void stop() {
    SONAR.stop();
  }

  private ConnectorConfig config() {
    return new ConnectorConfig(Map.of("baseUrl", SONAR.baseUrl()), "token-1");
  }

  @Test
  void sync_is_marked_available() {
    assertThat(new SonarQubeConnector().syncAvailable()).isTrue();
  }

  @Test
  void sync_maps_project_and_pr_quality_gates_to_canonical_raw_records() {
    List<RawRecord> emitted = runSync(new SonarQubeConnector(), config());
    List<RawRecord> gates =
        emitted.stream().filter(r -> r.stream().equals("quality_gate")).toList();
    assertThat(gates).hasSize(3); // proj-a project gate + proj-b project gate + proj-a PR gate

    RawRecord projectA =
        gates.stream().filter(r -> r.naturalKey().equals("sonar:proj-a")).findFirst().orElseThrow();
    assertThat(projectA.externalId()).isEqualTo("sonarqube:AU-1"); // immutable native analysis id
    assertThat(projectA.payload())
        .containsEntry("status", "PASSED")
        .containsEntry("evaluatedAt", "2026-01-05T09:00:00Z");

    RawRecord projectB =
        gates.stream().filter(r -> r.naturalKey().equals("sonar:proj-b")).findFirst().orElseThrow();
    assertThat(projectB.externalId()).isEqualTo("sonarqube:AU-2");
    assertThat(projectB.payload())
        .containsEntry("status", "FAILED")
        .containsEntry("evaluatedAt", "2026-01-06T09:00:00Z");
    // proj-b's project_pull_requests/list returns 404 (older Sonar): tolerated, no PR gate for it.
    assertThat(gates).noneMatch(r -> r.naturalKey().startsWith("sonar:proj-b/pr/"));

    RawRecord prGate =
        gates.stream()
            .filter(r -> r.naturalKey().equals("sonar:proj-a/pr/7"))
            .findFirst()
            .orElseThrow();
    assertThat(prGate.payload())
        .containsEntry("status", "PASSED")
        .containsEntry("evaluatedAt", "2026-01-05T10:00:00Z");
    assertThat(prGate.payload()).doesNotContainKey("pullRequestKey"); // never derived, documented

    for (RawRecord record : emitted) {
      assertThat(record.payload()).doesNotContainKeys("author", "reviewer", "user", "email");
    }
  }

  @Test
  void sync_skips_gate_when_no_analysis_history_exists_never_fabricating_a_timestamp() {
    WireMockServer sparse = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    sparse.start();
    try {
      sparse.stubFor(
          get(urlPathEqualTo("/api/projects/search"))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody(
                          "{\"paging\":{\"pageIndex\":1,\"pageSize\":100,\"total\":1},"
                              + "\"components\":[{\"key\":\"proj-x\"}]}")));
      sparse.stubFor(
          get(urlPathEqualTo("/api/qualitygates/project_status"))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody("{\"projectStatus\":{\"status\":\"OK\"}}")));
      sparse.stubFor(
          get(urlPathEqualTo("/api/project_analyses/search"))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody("{\"analyses\":[]}")));
      sparse.stubFor(
          get(urlPathEqualTo("/api/project_pull_requests/list"))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody("{\"pullRequests\":[]}")));

      List<RawRecord> emitted =
          runSync(
              new SonarQubeConnector(),
              new ConnectorConfig(Map.of("baseUrl", sparse.baseUrl()), "token-2"));

      assertThat(emitted).isEmpty(); // no analysis history -> skipped, never an invented timestamp
    } finally {
      sparse.stop();
    }
  }

  private static List<RawRecord> runSync(SonarQubeConnector connector, ConnectorConfig config) {
    List<RawRecord> emitted = new ArrayList<>();
    connector.sync(
        new SyncContext() {
          @Override
          public com.eip.connectors.spi.RawSink rawSink() {
            return emitted::add;
          }

          @Override
          public ConnectorConfig config() {
            return config;
          }
        });
    return emitted;
  }
}
