/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.github;

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
 * Proves the real GitHub connector against a WireMock'd GitHub: paginated repository sync mapped to
 * canonical {@code pull_request} + {@code code_review} (first page of reviews), {@code work_item}
 * (issues, excluding entries GitHub also lists as pull requests) and {@code build} (first page of
 * completed Actions workflow runs) raw records — Jira-key linkage from the branch name, strict
 * ISO-8601 instants, NO person identifiers (NFR-071), and page-number pagination followed across
 * pages.
 */
class GitHubConnectorSyncTest {

  private static final WireMockServer GH =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());

  @BeforeAll
  static void start() {
    GH.start();
    GH.stubFor(
        get(urlEqualTo("/orgs/acme/repos?per_page=100&page=1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"name\":\"acme-repo\"}]")));
    GH.stubFor(
        get(urlEqualTo("/repos/acme/acme-repo/pulls?state=all&per_page=100&page=1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(PULLS_BODY)));
    GH.stubFor(
        get(urlEqualTo("/repos/acme/acme-repo/pulls/42/reviews?per_page=100"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        [{"id":1,"state":"APPROVED","submitted_at":"2026-01-05T10:00:00Z"},
                         {"id":2,"state":"COMMENTED","submitted_at":"2026-01-05T10:30:00Z"}]
                        """)));
    GH.stubFor(
        get(urlEqualTo("/repos/acme/acme-repo/pulls/43/reviews?per_page=100"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "[{\"id\":3,\"state\":\"CHANGES_REQUESTED\","
                            + "\"submitted_at\":\"2026-01-06T11:00:00Z\"}]")));
    GH.stubFor(
        get(urlEqualTo("/repos/acme/acme-repo/pulls/44/reviews?per_page=100"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[]")));
    GH.stubFor(
        get(urlEqualTo("/repos/acme/acme-repo/issues?state=all&per_page=100&page=1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(ISSUES_BODY)));
    GH.stubFor(
        get(urlEqualTo("/repos/acme/acme-repo/actions/runs?per_page=100"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(RUNS_BODY)));
  }

  @AfterAll
  static void stop() {
    GH.stop();
  }

  private ConnectorConfig config() {
    return new ConnectorConfig(Map.of("baseUrl", GH.baseUrl(), "org", "acme"), "gh-token-1");
  }

  @Test
  void sync_is_marked_available() {
    assertThat(new GitHubConnector().syncAvailable()).isTrue();
  }

  @Test
  void test_connection_is_honest() {
    GitHubConnector connector = new GitHubConnector();
    GH.stubFor(get(urlPathEqualTo("/user")).willReturn(aResponse().withStatus(200)));
    assertThat(connector.testConnection(config()).outcome()).isEqualTo("OK");

    WireMockServer denied = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    denied.start();
    try {
      denied.stubFor(get(urlPathEqualTo("/user")).willReturn(aResponse().withStatus(401)));
      TestConnectionOutcome failed =
          connector.testConnection(
              new ConnectorConfig(Map.of("baseUrl", denied.baseUrl(), "org", "acme"), "bad-token"));
      assertThat(failed.outcome()).isEqualTo("FAILED");
      assertThat(failed.message()).doesNotContain("bad-token"); // never leaks the secret
    } finally {
      denied.stop();
    }
  }

  @Test
  void sync_maps_pull_requests_reviews_issues_and_builds_to_canonical_raw_records() {
    List<RawRecord> emitted = runSync(new GitHubConnector(), config());

    List<RawRecord> pullRequests =
        emitted.stream().filter(r -> r.stream().equals("pull_request")).toList();
    List<RawRecord> reviews =
        emitted.stream().filter(r -> r.stream().equals("code_review")).toList();
    List<RawRecord> workItems =
        emitted.stream().filter(r -> r.stream().equals("work_item")).toList();
    List<RawRecord> builds = emitted.stream().filter(r -> r.stream().equals("build")).toList();

    assertThat(pullRequests).hasSize(3);
    assertThat(reviews).hasSize(2);
    assertThat(workItems).hasSize(2); // issue #42 is a PR-as-issue and is skipped
    assertThat(builds).hasSize(2); // the in_progress run is skipped

    RawRecord pr42 =
        pullRequests.stream()
            .filter(r -> r.naturalKey().equals("acme-repo#42"))
            .findFirst()
            .orElseThrow();
    assertThat(pr42.externalId()).isEqualTo("github:501@acme-repo");
    assertThat(pr42.payload())
        .containsEntry("workItemKey", "PLAT-101")
        .containsEntry("status", "OPEN")
        .containsEntry("createdAt", "2026-01-05T09:00:00Z")
        .containsEntry("sourceBranch", "feature/PLAT-101-checkout");
    assertThat(pr42.payload()).doesNotContainKey("mergedAt"); // still open

    RawRecord pr43 =
        pullRequests.stream()
            .filter(r -> r.naturalKey().equals("acme-repo#43"))
            .findFirst()
            .orElseThrow();
    assertThat(pr43.payload())
        .containsEntry("status", "MERGED")
        .containsEntry("mergedAt", "2026-01-06T18:00:00Z"); // real merged_at, not approximated
    assertThat(pr43.payload()).doesNotContainKey("workItemKey"); // no key in branch or title

    RawRecord pr44 =
        pullRequests.stream()
            .filter(r -> r.naturalKey().equals("acme-repo#44"))
            .findFirst()
            .orElseThrow();
    assertThat(pr44.payload()).containsEntry("status", "DECLINED");
    assertThat(pr44.payload()).doesNotContainKey("mergedAt");

    RawRecord review42 =
        reviews.stream()
            .filter(r -> r.naturalKey().equals("acme-repo#42/review/1"))
            .findFirst()
            .orElseThrow();
    assertThat(review42.payload())
        .containsEntry("pullRequestKey", "acme-repo#42")
        .containsEntry("outcome", "APPROVED")
        .containsEntry("requestedAt", "2026-01-05T09:00:00Z") // PR's own createdAt (approximation)
        .containsEntry("completedAt", "2026-01-05T10:00:00Z");

    RawRecord review43 =
        reviews.stream()
            .filter(r -> r.naturalKey().equals("acme-repo#43/review/3"))
            .findFirst()
            .orElseThrow();
    assertThat(review43.payload()).containsEntry("outcome", "CHANGES_REQUESTED");
    // COMMENTED review on PR 42 carries no canonical outcome and is never emitted.
    assertThat(reviews).noneMatch(r -> r.payload().get("outcome") == null);

    RawRecord issue7 =
        workItems.stream()
            .filter(r -> r.naturalKey().equals("acme/acme-repo#7"))
            .findFirst()
            .orElseThrow();
    assertThat(issue7.externalId()).isEqualTo("github:901@acme-repo");
    assertThat(issue7.payload())
        .containsEntry("type", "issue")
        .containsEntry("status", "TODO")
        .containsEntry("createdAt", "2026-01-04T09:00:00Z");
    assertThat(issue7.payload()).doesNotContainKey("resolvedAt");

    RawRecord issue8 =
        workItems.stream()
            .filter(r -> r.naturalKey().equals("acme/acme-repo#8"))
            .findFirst()
            .orElseThrow();
    assertThat(issue8.payload())
        .containsEntry("status", "DONE")
        .containsEntry("resolvedAt", "2026-01-04T09:00:00Z");
    assertThat(workItems).noneMatch(r -> r.naturalKey().equals("acme/acme-repo#42")); // PR-as-issue

    RawRecord build9001 =
        builds.stream()
            .filter(r -> r.naturalKey().equals("acme-repo/actions/9001"))
            .findFirst()
            .orElseThrow();
    assertThat(build9001.externalId()).isEqualTo("github:actions:9001@acme-repo");
    assertThat(build9001.payload())
        .containsEntry("status", "SUCCESS")
        .containsEntry("startedAt", "2026-01-05T09:00:00Z")
        .containsEntry("finishedAt", "2026-01-05T09:05:00Z");
    assertThat(build9001.payload())
        .doesNotContainKey("pullRequestKey"); // never derived, documented

    RawRecord build9002 =
        builds.stream()
            .filter(r -> r.naturalKey().equals("acme-repo/actions/9002"))
            .findFirst()
            .orElseThrow();
    assertThat(build9002.payload()).containsEntry("status", "FAILED");

    assertThat(builds)
        .noneMatch(r -> r.naturalKey().equals("acme-repo/actions/9003")); // in_progress

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
  void sync_follows_page_number_pagination_across_repository_pages() {
    WireMockServer paged = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    paged.start();
    try {
      paged.stubFor(
          get(urlEqualTo("/orgs/acme/repos?per_page=100&page=1"))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody(repoPage(100))));
      paged.stubFor(
          get(urlEqualTo("/orgs/acme/repos?per_page=100&page=2"))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody("[{\"name\":\"repo-last\"}]")));
      paged.stubFor(
          get(urlPathMatching("/repos/acme/.*/pulls"))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody("[]")));
      paged.stubFor(
          get(urlPathMatching("/repos/acme/.*/issues"))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody("[]")));
      paged.stubFor(
          get(urlPathMatching("/repos/acme/.*/actions/runs"))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody("{\"workflow_runs\":[]}")));

      ConnectorConfig config =
          new ConnectorConfig(Map.of("baseUrl", paged.baseUrl(), "org", "acme"), "tok");
      runSync(new GitHubConnector(), config);

      // Proves page 2 was fetched: repo-last only exists there (page 1 is a full 100-item page).
      paged.verify(getRequestedFor(urlEqualTo("/orgs/acme/repos?per_page=100&page=2")));
    } finally {
      paged.stop();
    }
  }

  private static String repoPage(int count) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < count; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{\"name\":\"repo-").append(i).append("\"}");
    }
    return sb.append(']').toString();
  }

  private static List<RawRecord> runSync(GitHubConnector connector, ConnectorConfig config) {
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

  private static final String PULLS_BODY =
      """
      [
        {"id":501,"number":42,"title":"Checkout revamp","state":"open",
         "created_at":"2026-01-05T09:00:00Z",
         "head":{"ref":"feature/PLAT-101-checkout"}},
        {"id":502,"number":43,"title":"Cleanup script","state":"closed",
         "created_at":"2026-01-06T09:00:00Z","merged_at":"2026-01-06T18:00:00Z",
         "head":{"ref":"chore/no-ticket"}},
        {"id":503,"number":44,"title":"Abandoned idea","state":"closed",
         "created_at":"2026-01-07T09:00:00Z","merged_at":null,
         "head":{"ref":"chore/dead-end"}}
      ]
      """;

  private static final String ISSUES_BODY =
      """
      [
        {"id":901,"number":7,"title":"Bug report","state":"open",
         "created_at":"2026-01-04T09:00:00Z"},
        {"id":902,"number":8,"title":"Old bug","state":"closed",
         "created_at":"2026-01-03T09:00:00Z","closed_at":"2026-01-04T09:00:00Z"},
        {"id":903,"number":42,"title":"Checkout revamp","state":"open",
         "created_at":"2026-01-05T09:00:00Z","pull_request":{"url":"https://api.github.com/x"}}
      ]
      """;

  private static final String RUNS_BODY =
      """
      {"workflow_runs":[
        {"id":9001,"status":"completed","conclusion":"success",
         "run_started_at":"2026-01-05T09:00:00Z","updated_at":"2026-01-05T09:05:00Z"},
        {"id":9002,"status":"completed","conclusion":"failure",
         "run_started_at":"2026-01-05T10:00:00Z","updated_at":"2026-01-05T10:05:00Z"},
        {"id":9003,"status":"in_progress","conclusion":null,
         "run_started_at":"2026-01-05T11:00:00Z","updated_at":"2026-01-05T11:01:00Z"}
      ]}
      """;
}
