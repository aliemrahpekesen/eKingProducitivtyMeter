/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.bitbucket;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
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
 * Proves the real Bitbucket connector against a WireMock'd Bitbucket Cloud: paginated repository +
 * pull-request sync mapped to canonical {@code pull_request}/{@code code_review} raw records —
 * Jira-key linkage from the branch name (falling back to title), strict ISO-8601 instants, NO
 * person identifiers (NFR-071), and {@code next}-link pagination followed across pages.
 */
class BitbucketConnectorSyncTest {

  private static final WireMockServer BB =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());

  @BeforeAll
  static void start() {
    BB.start();
    BB.stubFor(
        get(urlPathEqualTo("/2.0/repositories/acme"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"pagelen":50,"values":[{"slug":"acme-repo"}],"next":null}
                        """)));
    BB.stubFor(
        get(urlPathEqualTo("/2.0/repositories/acme/acme-repo/pullrequests"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(PULL_REQUESTS_BODY)));
    BB.stubFor(
        get(urlPathEqualTo("/2.0/repositories/acme/acme-repo/pullrequests/42/activity"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(ACTIVITY_42_BODY)));
    BB.stubFor(
        get(urlPathEqualTo("/2.0/repositories/acme/acme-repo/pullrequests/43/activity"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"pagelen":50,"values":[]}
                        """)));
  }

  @AfterAll
  static void stop() {
    BB.stop();
  }

  private ConnectorConfig config() {
    return new ConnectorConfig(
        Map.of("baseUrl", BB.baseUrl(), "username", "svc", "workspace", "acme"), "app-pass-1");
  }

  @Test
  void sync_is_marked_available() {
    assertThat(new BitbucketConnector().syncAvailable()).isTrue();
  }

  @Test
  void sync_maps_pull_requests_and_reviews_to_canonical_raw_records() {
    List<RawRecord> emitted = runSync(new BitbucketConnector(), config());

    List<RawRecord> pullRequests =
        emitted.stream().filter(r -> r.stream().equals("pull_request")).toList();
    List<RawRecord> reviews =
        emitted.stream().filter(r -> r.stream().equals("code_review")).toList();
    assertThat(pullRequests).hasSize(2);
    assertThat(reviews).hasSize(2);

    RawRecord pr42 =
        pullRequests.stream()
            .filter(r -> r.naturalKey().equals("acme-repo#42"))
            .findFirst()
            .orElseThrow();
    assertThat(pr42.externalId()).isEqualTo("bitbucket:42@acme-repo"); // immutable native id
    assertThat(pr42.payload())
        .containsEntry("workItemKey", "PLAT-101")
        .containsEntry("status", "MERGED")
        .containsEntry("createdAt", "2026-01-05T09:00:00Z")
        .containsEntry("mergedAt", "2026-01-05T18:00:00Z") // updated_on approximation (documented)
        .containsEntry("sourceBranch", "feature/PLAT-101-checkout");

    RawRecord pr43 =
        pullRequests.stream()
            .filter(r -> r.naturalKey().equals("acme-repo#43"))
            .findFirst()
            .orElseThrow();
    assertThat(pr43.payload()).doesNotContainKey("workItemKey"); // no key in branch or title
    assertThat(pr43.payload()).doesNotContainKey("mergedAt"); // OPEN, never merged
    assertThat(pr43.payload()).containsEntry("status", "OPEN");

    assertThat(reviews).allMatch(r -> "acme-repo#42".equals(r.payload().get("pullRequestKey")));
    List<String> outcomes =
        reviews.stream().map(r -> (String) r.payload().get("outcome")).sorted().toList();
    assertThat(outcomes).containsExactly("APPROVED", "CHANGES_REQUESTED");
    assertThat(reviews)
        .allSatisfy(
            r -> assertThat(r.payload()).containsEntry("requestedAt", "2026-01-05T09:00:00Z"));

    for (RawRecord record : emitted) {
      assertThat(record.payload())
          .doesNotContainKeys(
              "author", "reviewer", "user", "email", "assignee", "displayName", "reporter");
    }
  }

  @Test
  void sync_follows_next_link_pagination_across_repository_pages() {
    WireMockServer paged = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    paged.start();
    try {
      paged.stubFor(
          get(urlEqualTo("/2.0/repositories/ws?pagelen=50"))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody(
                          "{\"pagelen\":50,\"values\":[{\"slug\":\"repo-a\"}],\"next\":\""
                              + paged.baseUrl()
                              + "/2.0/repositories/ws?pagelen=50&page=2\"}")));
      paged.stubFor(
          get(urlEqualTo("/2.0/repositories/ws?pagelen=50&page=2"))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody(
                          "{\"pagelen\":50,\"values\":[{\"slug\":\"repo-b\"}],\"next\":null}")));
      paged.stubFor(
          get(urlPathEqualTo("/2.0/repositories/ws/repo-a/pullrequests"))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody("{\"pagelen\":50,\"values\":[],\"next\":null}")));
      paged.stubFor(
          get(urlPathEqualTo("/2.0/repositories/ws/repo-b/pullrequests"))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody("{\"pagelen\":50,\"values\":[],\"next\":null}")));

      ConnectorConfig config =
          new ConnectorConfig(
              Map.of("baseUrl", paged.baseUrl(), "username", "svc", "workspace", "ws"), "pw");
      runSync(new BitbucketConnector(), config);

      // Proves the "next" link from page 1 was followed: repo-b only exists on page 2.
      paged.verify(getRequestedFor(urlPathEqualTo("/2.0/repositories/ws/repo-b/pullrequests")));
    } finally {
      paged.stop();
    }
  }

  private static List<RawRecord> runSync(BitbucketConnector connector, ConnectorConfig config) {
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

  private static final String PULL_REQUESTS_BODY =
      """
      {"pagelen":50,"values":[
        {"id":42,"title":"Checkout revamp","state":"MERGED",
         "created_on":"2026-01-05T09:00:00.000000+00:00",
         "updated_on":"2026-01-05T18:00:00.000000+00:00",
         "source":{"branch":{"name":"feature/PLAT-101-checkout"}}},
        {"id":43,"title":"Cleanup script","state":"OPEN",
         "created_on":"2026-01-06T09:00:00.000000+00:00",
         "updated_on":"2026-01-06T09:00:00.000000+00:00",
         "source":{"branch":{"name":"chore/no-ticket"}}}
      ],"next":null}
      """;

  private static final String ACTIVITY_42_BODY =
      """
      {"pagelen":50,"values":[
        {"approval":{"date":"2026-01-05T10:00:00.000000+00:00"}},
        {"changes_requested":{"date":"2026-01-05T11:00:00.000000+00:00"}},
        {"update":{"date":"2026-01-05T12:00:00.000000+00:00"}}
      ]}
      """;
}
