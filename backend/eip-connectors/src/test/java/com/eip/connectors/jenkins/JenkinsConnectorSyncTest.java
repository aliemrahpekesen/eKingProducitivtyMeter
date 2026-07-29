/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.jenkins;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.eip.connectors.spi.ConnectorConfig;
import com.eip.connectors.spi.RawRecord;
import com.eip.connectors.spi.SyncContext;
import com.eip.connectors.spi.TestConnectionOutcome;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Proves the real Jenkins connector against a WireMock'd Jenkins: bounded top-level job discovery,
 * per-job bounded build sync mapped to canonical {@code build} raw records — epoch-millis
 * timestamps, binary SUCCESS/FAILED status vocabulary, a still-running build (null {@code result})
 * skipped rather than fabricated, the top-level job cap enforced, and NO person identifiers
 * (NFR-071).
 */
class JenkinsConnectorSyncTest {

  private static final WireMockServer JK =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());

  @BeforeAll
  static void start() {
    JK.start();
    String jobsBody =
        "{\"jobs\":["
            + "{\"name\":\"build-app\",\"url\":\""
            + JK.baseUrl()
            + "/job/build-app/\"},"
            + "{\"name\":\"deploy-app\",\"url\":\""
            + JK.baseUrl()
            + "/job/deploy-app/\"}]}";
    JK.stubFor(
        get(urlPathEqualTo("/api/json"))
            .withQueryParam("tree", equalTo("jobs[name,url]"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(jobsBody)));
    JK.stubFor(
        get(urlPathEqualTo("/job/build-app/api/json"))
            .withQueryParam("tree", equalTo("builds[number,timestamp,duration,result]{,50}"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(BUILD_APP_BUILDS_BODY)));
    JK.stubFor(
        get(urlPathEqualTo("/job/deploy-app/api/json"))
            .withQueryParam("tree", equalTo("builds[number,timestamp,duration,result]{,50}"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(DEPLOY_APP_BUILDS_BODY)));
  }

  @AfterAll
  static void stop() {
    JK.stop();
  }

  private ConnectorConfig config() {
    return new ConnectorConfig(
        Map.of("baseUrl", JK.baseUrl(), "username", "svc"), "jenkins-token-1");
  }

  @Test
  void sync_is_marked_available() {
    assertThat(new JenkinsConnector().syncAvailable()).isTrue();
  }

  @Test
  void test_connection_is_honest() {
    // Deliberately isolated servers (never the shared JK): Jenkins's probe path ("/api/json") is
    // the SAME path as the job-list call, differing only by query string, so reusing JK here would
    // register a query-less stub that could shadow the job-list stub for other tests in this class.
    JenkinsConnector connector = new JenkinsConnector();
    WireMockServer ok = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    ok.start();
    try {
      ok.stubFor(get(urlPathEqualTo("/api/json")).willReturn(aResponse().withStatus(200)));
      ConnectorConfig config =
          new ConnectorConfig(
              Map.of("baseUrl", ok.baseUrl(), "username", "svc"), "jenkins-token-1");
      assertThat(connector.testConnection(config).outcome()).isEqualTo("OK");
    } finally {
      ok.stop();
    }

    WireMockServer denied = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    denied.start();
    try {
      denied.stubFor(get(urlPathEqualTo("/api/json")).willReturn(aResponse().withStatus(401)));
      TestConnectionOutcome failed =
          connector.testConnection(
              new ConnectorConfig(
                  Map.of("baseUrl", denied.baseUrl(), "username", "svc"), "bad-token"));
      assertThat(failed.outcome()).isEqualTo("FAILED");
      assertThat(failed.message()).doesNotContain("bad-token"); // never leaks the secret
    } finally {
      denied.stop();
    }
  }

  @Test
  void sync_maps_builds_and_skips_the_still_running_one() {
    List<RawRecord> emitted = runSync(new JenkinsConnector(), config());
    assertThat(emitted).allMatch(r -> r.stream().equals("build"));
    assertThat(emitted).hasSize(3); // build-app#11 (null result) is skipped

    RawRecord success =
        emitted.stream()
            .filter(r -> r.naturalKey().equals("build-app#10"))
            .findFirst()
            .orElseThrow();
    assertThat(success.externalId()).isEqualTo("jenkins:build-app#10");
    assertThat(success.payload())
        .containsEntry("status", "SUCCESS")
        .containsEntry("startedAt", "1970-01-01T00:00:00Z")
        .containsEntry("finishedAt", "1970-01-01T01:00:00Z");
    assertThat(success.payload()).doesNotContainKey("pullRequestKey"); // never derived, documented

    RawRecord failure =
        emitted.stream()
            .filter(r -> r.naturalKey().equals("build-app#9"))
            .findFirst()
            .orElseThrow();
    assertThat(failure.payload())
        .containsEntry("status", "FAILED")
        .containsEntry("startedAt", "1970-01-01T01:00:00Z")
        .containsEntry("finishedAt", "1970-01-01T01:30:00Z");

    RawRecord unstable =
        emitted.stream()
            .filter(r -> r.naturalKey().equals("deploy-app#3"))
            .findFirst()
            .orElseThrow();
    assertThat(unstable.payload())
        .containsEntry("status", "FAILED"); // UNSTABLE collapses to FAILED

    assertThat(emitted).noneMatch(r -> r.naturalKey().equals("build-app#11")); // null result

    for (RawRecord record : emitted) {
      assertThat(record.payload())
          .doesNotContainKeys(
              "author",
              "user",
              "login",
              "email",
              "assignee",
              "reviewer",
              "displayName",
              "reporter");
    }
  }

  @Test
  void sync_caps_top_level_job_discovery_at_fifty() {
    WireMockServer capped = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    capped.start();
    try {
      StringBuilder jobs = new StringBuilder("{\"jobs\":[");
      for (int i = 0; i < 51; i++) {
        if (i > 0) {
          jobs.append(',');
        }
        jobs.append("{\"name\":\"job-")
            .append(i)
            .append("\",\"url\":\"")
            .append(capped.baseUrl())
            .append("/job/job-")
            .append(i)
            .append("/\"}");
      }
      jobs.append("]}");
      capped.stubFor(
          get(urlPathEqualTo("/api/json"))
              .withQueryParam("tree", equalTo("jobs[name,url]"))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody(jobs.toString())));
      capped.stubFor(
          get(urlPathMatching("/job/job-.*/api/json"))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody(
                          "{\"builds\":[{\"number\":1,\"timestamp\":0,\"duration\":0,\"result\":\"SUCCESS\"}]}")));

      List<RawRecord> emitted =
          runSync(
              new JenkinsConnector(),
              new ConnectorConfig(Map.of("baseUrl", capped.baseUrl(), "username", "svc"), "tok"));

      assertThat(emitted).hasSize(50); // 51 jobs discovered, only the first 50 are synced
      assertThat(emitted).noneMatch(r -> r.naturalKey().equals("job-50#1")); // the 51st, excluded
    } finally {
      capped.stop();
    }
  }

  private static List<RawRecord> runSync(JenkinsConnector connector, ConnectorConfig config) {
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

  private static final String BUILD_APP_BUILDS_BODY =
      """
      {"builds":[
        {"number":10,"timestamp":0,"duration":3600000,"result":"SUCCESS"},
        {"number":9,"timestamp":3600000,"duration":1800000,"result":"FAILURE"},
        {"number":11,"timestamp":7200000,"duration":0,"result":null}
      ]}
      """;

  private static final String DEPLOY_APP_BUILDS_BODY =
      """
      {"builds":[
        {"number":3,"timestamp":10800000,"duration":600000,"result":"UNSTABLE"}
      ]}
      """;
}
