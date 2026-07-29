/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.jira;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.eip.connectors.spi.ConnectorConfig;
import com.eip.connectors.spi.FetchKind;
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
 * Proves the real Jira connector against a WireMock'd Jira: honest authenticated probe (OK only on
 * 200, FAILED on 401), and a full sync that maps issues + changelog to canonical raw records —
 * strict ISO-8601 instants, canonical workflow states (blocked/review aware), project-key team
 * attribution, and NO person identifiers (NFR-071).
 */
class JiraConnectorTest {

  private static final WireMockServer JIRA =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());

  @BeforeAll
  static void start() {
    JIRA.start();
    JIRA.stubFor(
        get(urlPathEqualTo("/rest/api/3/myself"))
            .withHeader("Authorization", containing("Basic "))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"accountType\":\"atlassian\"}")));
    JIRA.stubFor(
        get(urlPathEqualTo("/rest/api/3/search"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(SEARCH_BODY)));
  }

  @AfterAll
  static void stop() {
    JIRA.stop();
  }

  private ConnectorConfig config() {
    return new ConnectorConfig(
        Map.of("baseUrl", JIRA.baseUrl(), "email", "svc@acme.io", "projectKeys", "PLAT"),
        "token-1");
  }

  @Test
  void test_connection_is_honest() {
    JiraConnector connector = new JiraConnector();
    TestConnectionOutcome ok = connector.testConnection(config());
    assertThat(ok.outcome()).isEqualTo("OK");

    WireMockServer denied = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    denied.start();
    try {
      denied.stubFor(
          get(urlPathEqualTo("/rest/api/3/myself")).willReturn(aResponse().withStatus(401)));
      TestConnectionOutcome failed =
          connector.testConnection(
              new ConnectorConfig(
                  Map.of("baseUrl", denied.baseUrl(), "email", "svc@acme.io"), "bad"));
      assertThat(failed.outcome()).isEqualTo("FAILED");
      assertThat(failed.message()).doesNotContain("bad"); // never leaks the secret
    } finally {
      denied.stop();
    }
  }

  @Test
  void sync_maps_issues_and_changelog_to_canonical_raw_records() {
    JiraConnector connector = new JiraConnector();
    List<RawRecord> emitted = new ArrayList<>();
    connector.sync(
        new SyncContext() {
          @Override
          public com.eip.connectors.spi.RawSink rawSink() {
            return emitted::add;
          }

          @Override
          public ConnectorConfig config() {
            return JiraConnectorTest.this.config();
          }
        });

    List<RawRecord> items = emitted.stream().filter(r -> r.stream().equals("work_item")).toList();
    List<RawRecord> transitions =
        emitted.stream().filter(r -> r.stream().equals("work_item_transition")).toList();
    assertThat(items).hasSize(2);
    assertThat(transitions).hasSize(8);

    RawRecord plat1 = items.get(0);
    assertThat(plat1.naturalKey()).isEqualTo("PLAT-1");
    assertThat(plat1.externalId()).isEqualTo("jira:10001"); // immutable native id (AD-14)
    assertThat(plat1.payload())
        .containsEntry("team", "PLAT")
        .containsEntry("status", "DONE")
        .containsEntry("createdAt", "2026-01-05T09:00:00Z") // strict ISO-8601 from +0000 format
        .containsEntry("resolvedAt", "2026-01-05T17:00:00Z");
    assertThat(plat1.payload()).doesNotContainKeys("assignee", "reporter", "author");

    // Canonical states incl. blocked/review mapping from Jira status names.
    List<String> plat1States =
        transitions.stream()
            .filter(t -> "PLAT-1".equals(t.payload().get("workItemKey")))
            .map(t -> (String) t.payload().get("toState"))
            .toList();
    assertThat(plat1States)
        .containsExactly("IN_PROGRESS", "BLOCKED", "IN_PROGRESS", "IN_REVIEW", "DONE");

    // Unresolved issue: no resolvedAt key at all (normalization treats it as in-flight).
    RawRecord plat2 = items.get(1);
    assertThat(plat2.payload()).doesNotContainKey("resolvedAt");
    assertThat(plat2.payload()).containsEntry("status", "IN_PROGRESS");
  }

  @Test
  void sync_without_a_cursor_sends_no_updated_clause_and_emits_full_records() {
    JIRA.resetRequests();
    JiraConnector connector = new JiraConnector();
    List<RawRecord> emitted = new ArrayList<>();
    connector.sync(
        new SyncContext() {
          @Override
          public com.eip.connectors.spi.RawSink rawSink() {
            return emitted::add;
          }

          @Override
          public ConnectorConfig config() {
            return JiraConnectorTest.this.config();
          }
        });

    assertThat(emitted).isNotEmpty();
    assertThat(emitted).allMatch(r -> r.fetchKind() == FetchKind.FULL);
    JIRA.verify(
        getRequestedFor(urlPathEqualTo("/rest/api/3/search"))
            .withQueryParam("jql", equalTo("project in (PLAT) order by created asc")));
  }

  @Test
  void sync_with_a_cursor_narrows_the_jql_and_emits_incremental_records() {
    JIRA.resetRequests();
    JiraConnector connector = new JiraConnector();
    List<RawRecord> emitted = new ArrayList<>();
    Map<String, String> cursor = Map.of("updatedSince", "2026-01-05T12:00:00Z");
    connector.sync(
        new SyncContext() {
          @Override
          public com.eip.connectors.spi.RawSink rawSink() {
            return emitted::add;
          }

          @Override
          public ConnectorConfig config() {
            return JiraConnectorTest.this.config();
          }

          @Override
          public Map<String, String> cursor() {
            return cursor;
          }
        });

    assertThat(emitted).isNotEmpty();
    assertThat(emitted).allMatch(r -> r.fetchKind() == FetchKind.INCREMENTAL);
    // 10-minute overlap window subtracted from the cursor (class javadoc): 2026-01-05T12:00:00Z ->
    // 2026/01/05 11:50, minute precision, UTC-labeled.
    JIRA.verify(
        getRequestedFor(urlPathEqualTo("/rest/api/3/search"))
            .withQueryParam(
                "jql",
                equalTo(
                    "project in (PLAT) AND updated >= \"2026/01/05 11:50\" order by created"
                        + " asc")));
  }

  @Test
  void sync_pages_the_changelog_when_the_embedded_history_is_truncated() {
    // DEBT-018 item 5: the search response declares changelog.total=4 but embeds only 2 histories
    // — the connector must page the remainder via the dedicated /changelog endpoint and merge all
    // 4 transitions chronologically, not just the embedded page's 2.
    WireMockServer truncated = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    truncated.start();
    try {
      truncated.stubFor(
          get(urlPathEqualTo("/rest/api/3/search"))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody(TRUNCATED_SEARCH_BODY)));
      truncated.stubFor(
          get(urlPathEqualTo("/rest/api/3/issue/20001/changelog"))
              .withQueryParam("startAt", equalTo("2"))
              .withQueryParam("maxResults", equalTo("100"))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody(CHANGELOG_PAGE_BODY)));

      JiraConnector connector = new JiraConnector();
      List<RawRecord> emitted = new ArrayList<>();
      connector.sync(
          new SyncContext() {
            @Override
            public com.eip.connectors.spi.RawSink rawSink() {
              return emitted::add;
            }

            @Override
            public ConnectorConfig config() {
              return new ConnectorConfig(
                  Map.of("baseUrl", truncated.baseUrl(), "email", "svc@acme.io"), "token-1");
            }
          });

      List<RawRecord> transitions =
          emitted.stream().filter(r -> r.stream().equals("work_item_transition")).toList();
      assertThat(transitions).hasSize(4); // all 4 histories, not just the embedded page's 2
      assertThat(transitions.stream().map(t -> t.payload().get("toState")))
          .containsExactly("IN_PROGRESS", "BLOCKED", "IN_PROGRESS", "DONE");
      truncated.verify(getRequestedFor(urlPathEqualTo("/rest/api/3/issue/20001/changelog")));
    } finally {
      truncated.stop();
    }
  }

  @Test
  void sync_makes_no_extra_changelog_call_when_the_embedded_history_is_complete() {
    // The shared JIRA server's SEARCH_BODY changelogs have no "total" field at all, so the
    // connector defaults total to the embedded histories' own size — never truncated, so no
    // /changelog stub is even registered on JIRA; if the connector wrongly tried to page anyway,
    // WireMock would 404 it and the existing sync tests above would already be failing.
    JIRA.resetRequests();
    JiraConnector connector = new JiraConnector();
    List<RawRecord> emitted = new ArrayList<>();
    connector.sync(
        new SyncContext() {
          @Override
          public com.eip.connectors.spi.RawSink rawSink() {
            return emitted::add;
          }

          @Override
          public ConnectorConfig config() {
            return JiraConnectorTest.this.config();
          }
        });

    assertThat(emitted).isNotEmpty();
    assertThat(JIRA.findAll(getRequestedFor(urlPathEqualTo("/rest/api/3/search")))).hasSize(1);
  }

  @Test
  void state_mapping_covers_the_canonical_vocabulary() {
    assertThat(JiraConnector.canonicalStateName("Impediment / On Hold")).isEqualTo("BLOCKED");
    assertThat(JiraConnector.canonicalStateName("Code Review")).isEqualTo("IN_REVIEW");
    assertThat(JiraConnector.canonicalStateName("Selected for Development"))
        .isEqualTo("IN_PROGRESS");
    assertThat(JiraConnector.canonicalStateName("To Do")).isEqualTo("TODO");
    assertThat(JiraConnector.canonicalStateName("Closed")).isEqualTo("DONE");
  }

  private static final String SEARCH_BODY =
      """
      {"startAt":0,"maxResults":100,"total":2,"issues":[
        {"id":"10001","key":"PLAT-1",
         "fields":{"summary":"Checkout refactor","project":{"key":"PLAT"},
                   "issuetype":{"name":"Story"},
                   "status":{"name":"Done","statusCategory":{"key":"done"}},
                   "created":"2026-01-05T09:00:00.000+0000",
                   "resolutiondate":"2026-01-05T17:00:00.000+0000"},
         "changelog":{"histories":[
           {"id":"h1","created":"2026-01-05T10:00:00.000+0000","items":[
             {"field":"status","fromString":"To Do","toString":"In Progress"}]},
           {"id":"h2","created":"2026-01-05T11:00:00.000+0000","items":[
             {"field":"status","fromString":"In Progress","toString":"Blocked"}]},
           {"id":"h3","created":"2026-01-05T13:00:00.000+0000","items":[
             {"field":"status","fromString":"Blocked","toString":"In Progress"}]},
           {"id":"h4","created":"2026-01-05T14:00:00.000+0000","items":[
             {"field":"status","fromString":"In Progress","toString":"In Review"}]},
           {"id":"h5","created":"2026-01-05T17:00:00.000+0000","items":[
             {"field":"status","fromString":"In Review","toString":"Done"}]}]}},
        {"id":"10002","key":"PLAT-2",
         "fields":{"summary":"Latency fix","project":{"key":"PLAT"},
                   "issuetype":{"name":"Bug"},
                   "status":{"name":"In Progress","statusCategory":{"key":"indeterminate"}},
                   "created":"2026-01-05T09:00:00.000+0000","resolutiondate":null},
         "changelog":{"histories":[
           {"id":"h6","created":"2026-01-05T10:00:00.000+0000","items":[
             {"field":"status","fromString":"To Do","toString":"In Progress"}]},
           {"id":"h7","created":"2026-01-05T11:00:00.000+0000","items":[
             {"field":"status","fromString":"In Progress","toString":"In Review"}]},
           {"id":"h8","created":"2026-01-05T12:00:00.000+0000","items":[
             {"field":"status","fromString":"In Review","toString":"Done"}]}]}}
      ]}
      """;

  // DEBT-018 item 5 fixtures: changelog.total (4) exceeds the embedded histories (2), so the
  // connector must page /rest/api/3/issue/20001/changelog?startAt=2 for the remaining 2.
  private static final String TRUNCATED_SEARCH_BODY =
      """
      {"startAt":0,"maxResults":100,"total":1,"issues":[
        {"id":"20001","key":"PLAT-3",
         "fields":{"summary":"Truncated history","project":{"key":"PLAT"},
                   "issuetype":{"name":"Task"},
                   "status":{"name":"In Progress","statusCategory":{"key":"indeterminate"}},
                   "created":"2026-01-05T09:00:00.000+0000","resolutiondate":null},
         "changelog":{"total":4,"startAt":0,"maxResults":2,"histories":[
           {"id":"h1","created":"2026-01-05T10:00:00.000+0000","items":[
             {"field":"status","fromString":"To Do","toString":"In Progress"}]},
           {"id":"h2","created":"2026-01-05T11:00:00.000+0000","items":[
             {"field":"status","fromString":"In Progress","toString":"Blocked"}]}]}}
      ]}
      """;

  private static final String CHANGELOG_PAGE_BODY =
      """
      {"startAt":2,"maxResults":100,"total":4,"isLast":true,"values":[
        {"id":"h3","created":"2026-01-05T13:00:00.000+0000","items":[
          {"field":"status","fromString":"Blocked","toString":"In Progress"}]},
        {"id":"h4","created":"2026-01-05T14:00:00.000+0000","items":[
          {"field":"status","fromString":"In Progress","toString":"Done"}]}]}
      """;
}
