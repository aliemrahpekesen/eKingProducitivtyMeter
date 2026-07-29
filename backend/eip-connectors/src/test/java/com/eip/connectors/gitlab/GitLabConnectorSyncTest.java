/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.gitlab;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
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
 * Proves the real GitLab connector against a WireMock'd GitLab: paginated group-project sync mapped
 * to canonical {@code pull_request} raw records (from merge requests) and {@code build} raw records
 * (from terminal-status pipelines only) — Jira-key linkage from the source branch, strict ISO-8601
 * instants, deliberately NO {@code code_review} records (no reliable approval timestamp), NO person
 * identifiers (NFR-071), and page-number pagination followed across pages.
 */
class GitLabConnectorSyncTest {

  private static final WireMockServer GL =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());

  @BeforeAll
  static void start() {
    GL.start();
    GL.stubFor(
        get(urlEqualTo(
                "/api/v4/groups/acme-group/projects?include_subgroups=true&per_page=100&page=1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"id\":11,\"path_with_namespace\":\"acme-group/svc-a\"}]")));
    GL.stubFor(
        get(urlEqualTo("/api/v4/projects/11/merge_requests?state=all&per_page=100&page=1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(MERGE_REQUESTS_BODY)));
    GL.stubFor(
        get(urlEqualTo("/api/v4/projects/11/pipelines?per_page=100"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(PIPELINES_BODY)));
  }

  @AfterAll
  static void stop() {
    GL.stop();
  }

  private ConnectorConfig config() {
    return new ConnectorConfig(
        Map.of("baseUrl", GL.baseUrl(), "group", "acme-group"), "gl-token-1");
  }

  @Test
  void sync_is_marked_available() {
    assertThat(new GitLabConnector().syncAvailable()).isTrue();
  }

  @Test
  void test_connection_is_honest() {
    GitLabConnector connector = new GitLabConnector();
    GL.stubFor(get(urlPathEqualTo("/api/v4/user")).willReturn(aResponse().withStatus(200)));
    assertThat(connector.testConnection(config()).outcome()).isEqualTo("OK");

    WireMockServer denied = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    denied.start();
    try {
      denied.stubFor(get(urlPathEqualTo("/api/v4/user")).willReturn(aResponse().withStatus(401)));
      TestConnectionOutcome failed =
          connector.testConnection(
              new ConnectorConfig(
                  Map.of("baseUrl", denied.baseUrl(), "group", "acme-group"), "bad-token"));
      assertThat(failed.outcome()).isEqualTo("FAILED");
      assertThat(failed.message()).doesNotContain("bad-token"); // never leaks the secret
    } finally {
      denied.stop();
    }
  }

  @Test
  void sync_maps_merge_requests_and_terminal_pipelines_never_fabricating_a_review() {
    List<RawRecord> emitted = runSync(new GitLabConnector(), config());

    List<RawRecord> pullRequests =
        emitted.stream().filter(r -> r.stream().equals("pull_request")).toList();
    List<RawRecord> builds = emitted.stream().filter(r -> r.stream().equals("build")).toList();
    assertThat(pullRequests).hasSize(3);
    assertThat(builds).hasSize(2); // the running pipeline is skipped
    assertThat(emitted).noneMatch(r -> r.stream().equals("code_review")); // never fabricated

    RawRecord opened =
        pullRequests.stream()
            .filter(r -> r.naturalKey().equals("acme-group/svc-a!5"))
            .findFirst()
            .orElseThrow();
    assertThat(opened.externalId()).isEqualTo("gitlab:701@acme-group/svc-a");
    assertThat(opened.payload())
        .containsEntry("workItemKey", "PLAT-101")
        .containsEntry("status", "OPEN")
        .containsEntry("createdAt", "2026-01-05T09:00:00Z")
        .containsEntry("sourceBranch", "feature/PLAT-101-checkout");
    assertThat(opened.payload()).doesNotContainKey("mergedAt");

    RawRecord merged =
        pullRequests.stream()
            .filter(r -> r.naturalKey().equals("acme-group/svc-a!6"))
            .findFirst()
            .orElseThrow();
    assertThat(merged.payload())
        .containsEntry("status", "MERGED")
        .containsEntry("mergedAt", "2026-01-06T18:00:00Z"); // real merged_at, not approximated
    assertThat(merged.payload()).doesNotContainKey("workItemKey");

    RawRecord declined =
        pullRequests.stream()
            .filter(r -> r.naturalKey().equals("acme-group/svc-a!7"))
            .findFirst()
            .orElseThrow();
    assertThat(declined.payload()).containsEntry("status", "DECLINED");
    assertThat(declined.payload()).doesNotContainKey("mergedAt");

    RawRecord build8001 =
        builds.stream()
            .filter(r -> r.naturalKey().equals("acme-group/svc-a/pipeline/8001"))
            .findFirst()
            .orElseThrow();
    assertThat(build8001.externalId()).isEqualTo("gitlab:pipeline:8001@acme-group/svc-a");
    assertThat(build8001.payload())
        .containsEntry("status", "SUCCESS")
        .containsEntry("startedAt", "2026-01-05T09:00:00Z")
        .containsEntry("finishedAt", "2026-01-05T09:05:00Z");
    assertThat(build8001.payload())
        .doesNotContainKey("pullRequestKey"); // never derived, documented

    RawRecord build8002 =
        builds.stream()
            .filter(r -> r.naturalKey().equals("acme-group/svc-a/pipeline/8002"))
            .findFirst()
            .orElseThrow();
    assertThat(build8002.payload()).containsEntry("status", "FAILED");

    assertThat(builds).noneMatch(r -> r.naturalKey().endsWith("/pipeline/8003")); // still running

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
  void sync_follows_page_number_pagination_across_project_pages() {
    WireMockServer paged = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    paged.start();
    try {
      paged.stubFor(
          get(urlEqualTo(
                  "/api/v4/groups/acme-group/projects?include_subgroups=true&per_page=100&page=1"))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody(projectPage(100))));
      paged.stubFor(
          get(urlEqualTo(
                  "/api/v4/groups/acme-group/projects?include_subgroups=true&per_page=100&page=2"))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody(
                          "[{\"id\":999,\"path_with_namespace\":\"acme-group/proj-last\"}]")));
      paged.stubFor(
          get(urlPathMatching("/api/v4/projects/.*/merge_requests"))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody("[]")));
      paged.stubFor(
          get(urlPathMatching("/api/v4/projects/.*/pipelines"))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody("[]")));

      ConnectorConfig config =
          new ConnectorConfig(Map.of("baseUrl", paged.baseUrl(), "group", "acme-group"), "tok");
      runSync(new GitLabConnector(), config);

      paged.verify(
          getRequestedFor(
              urlEqualTo(
                  "/api/v4/groups/acme-group/projects?include_subgroups=true&per_page=100&page=2")));
    } finally {
      paged.stop();
    }
  }

  private static String projectPage(int count) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < count; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{\"id\":")
          .append(i)
          .append(",\"path_with_namespace\":\"acme-group/proj-")
          .append(i)
          .append("\"}");
    }
    return sb.append(']').toString();
  }

  private static List<RawRecord> runSync(GitLabConnector connector, ConnectorConfig config) {
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

  private static final String MERGE_REQUESTS_BODY =
      """
      [
        {"id":701,"iid":5,"title":"Checkout revamp","state":"opened",
         "source_branch":"feature/PLAT-101-checkout","created_at":"2026-01-05T09:00:00.000Z"},
        {"id":702,"iid":6,"title":"Cleanup","state":"merged",
         "source_branch":"chore/no-ticket","created_at":"2026-01-06T09:00:00.000Z",
         "merged_at":"2026-01-06T18:00:00.000Z"},
        {"id":703,"iid":7,"title":"Dead end","state":"closed",
         "source_branch":"chore/dead","created_at":"2026-01-07T09:00:00.000Z","merged_at":null}
      ]
      """;

  private static final String PIPELINES_BODY =
      """
      [
        {"id":8001,"status":"success",
         "created_at":"2026-01-05T09:00:00.000Z","updated_at":"2026-01-05T09:05:00.000Z"},
        {"id":8002,"status":"failed",
         "created_at":"2026-01-05T10:00:00.000Z","updated_at":"2026-01-05T10:05:00.000Z"},
        {"id":8003,"status":"running",
         "created_at":"2026-01-05T11:00:00.000Z","updated_at":"2026-01-05T11:01:00.000Z"}
      ]
      """;
}
