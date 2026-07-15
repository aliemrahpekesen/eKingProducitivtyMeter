/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.jira;

import com.eip.connectors.http.SourceHttp;
import com.eip.connectors.http.SourceHttp.JsonResponse;
import com.eip.connectors.spi.Connector;
import com.eip.connectors.spi.ConnectorConfig;
import com.eip.connectors.spi.FetchKind;
import com.eip.connectors.spi.Op;
import com.eip.connectors.spi.RawRecord;
import com.eip.connectors.spi.SyncContext;
import com.eip.connectors.spi.TestConnectionOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Real Jira connector (Cloud/DC REST v3, Basic auth with a service-account e-mail + API token).
 * {@code testConnection} authenticates against {@code /rest/api/3/myself}; {@code sync} pages
 * {@code /rest/api/3/search?expand=changelog} and emits {@code work_item} + {@code
 * work_item_transition} raw records with canonical workflow states and strict ISO-8601 instants, so
 * the existing normalization/correlation/friction pipeline consumes Jira data unchanged. Team
 * attribution = the Jira project key (never a person — NFR-071).
 */
public final class JiraConnector implements Connector {

  /** The {@code core.connector.type} discriminator. */
  public static final String TYPE = "jira";

  private static final int PAGE_SIZE = 100;
  private static final int MAX_PAGES = 50; // v0.1 bound: 5000 issues per sync
  private static final DateTimeFormatter JIRA_TS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT);

  private final SourceHttp http;

  /** Creates the connector with its own HTTP client. */
  public JiraConnector() {
    this(new SourceHttp());
  }

  JiraConnector(SourceHttp http) {
    this.http = http;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public boolean simulation() {
    return false;
  }

  @Override
  public boolean syncAvailable() {
    return true;
  }

  @Override
  public TestConnectionOutcome testConnection(ConnectorConfig config) {
    try {
      JsonResponse response =
          http.getJson(baseUrl(config) + "/rest/api/3/myself", authorization(config));
      if (response.status() == 200) {
        return TestConnectionOutcome.ok(
            "Authenticated to Jira (account type: "
                + response.body().path("accountType").asText("unknown")
                + ").");
      }
      if (response.status() == 401 || response.status() == 403) {
        return TestConnectionOutcome.failed(
            "Jira rejected the credentials (HTTP "
                + response.status()
                + ") — check the service"
                + " account e-mail and API token.");
      }
      return TestConnectionOutcome.failed("Unexpected Jira response: HTTP " + response.status());
    } catch (SourceHttp.SourceHttpException e) {
      return TestConnectionOutcome.failed(String.valueOf(e.getMessage()));
    }
  }

  @Override
  public void sync(SyncContext context) {
    ConnectorConfig config = context.config();
    String auth = authorization(config);
    String base = baseUrl(config);
    String jql = jql(config);

    int startAt = 0;
    for (int page = 0; page < MAX_PAGES; page++) {
      JsonResponse response =
          http.getJson(
              base
                  + "/rest/api/3/search?jql="
                  + URLEncoder.encode(jql, StandardCharsets.UTF_8)
                  + "&startAt="
                  + startAt
                  + "&maxResults="
                  + PAGE_SIZE
                  + "&expand=changelog"
                  + "&fields=summary,issuetype,status,project,created,resolutiondate",
              auth);
      if (response.status() != 200) {
        throw new IllegalStateException("Jira search failed: HTTP " + response.status());
      }
      JsonNode issues = response.body().path("issues");
      for (JsonNode issue : issues) {
        emitIssue(context, issue);
      }
      int total = response.body().path("total").asInt(0);
      startAt += issues.size();
      if (startAt >= total || issues.isEmpty()) {
        return;
      }
    }
  }

  private void emitIssue(SyncContext context, JsonNode issue) {
    String key = issue.path("key").asText();
    String id = issue.path("id").asText();
    JsonNode fields = issue.path("fields");
    String projectKey = fields.path("project").path("key").asText("UNKNOWN");
    @Nullable String resolved = textOrNull(fields, "resolutiondate");

    Map<String, String> item = new java.util.LinkedHashMap<>();
    item.put("key", key);
    item.put("team", projectKey);
    item.put("type", fields.path("issuetype").path("name").asText("task"));
    item.put("title", fields.path("summary").asText(""));
    item.put("status", canonicalState(fields.path("status")));
    item.put("createdAt", toInstant(fields.path("created").asText()));
    if (resolved != null) {
      item.put("resolvedAt", toInstant(resolved));
    }
    context
        .rawSink()
        .emit(
            new RawRecord(
                "work_item",
                key,
                "jira",
                instance(context),
                "jira:" + id,
                Op.UPSERT,
                FetchKind.FULL,
                item));

    // Changelog → canonical transitions, chronological. First changelog page per issue (v0.1).
    List<JsonNode> histories = new ArrayList<>();
    issue.path("changelog").path("histories").forEach(histories::add);
    histories.sort(java.util.Comparator.comparing(h -> h.path("created").asText()));
    int seq = 0;
    for (JsonNode history : histories) {
      for (JsonNode change : history.path("items")) {
        if (!"status".equals(change.path("field").asText())) {
          continue;
        }
        seq++;
        Map<String, String> transition = new java.util.LinkedHashMap<>();
        transition.put("workItemKey", key);
        transition.put("seq", Integer.toString(seq));
        transition.put("fromState", canonicalStateName(change.path("fromString").asText("")));
        transition.put("toState", canonicalStateName(change.path("toString").asText("")));
        transition.put("at", toInstant(history.path("created").asText()));
        context
            .rawSink()
            .emit(
                new RawRecord(
                    "work_item_transition",
                    key + "#" + history.path("id").asText(seq + ""),
                    "jira",
                    instance(context),
                    "jira:" + id + "#" + history.path("id").asText(seq + ""),
                    Op.UPSERT,
                    FetchKind.FULL,
                    transition));
      }
    }
  }

  /** Maps a Jira status object (name + statusCategory) to the canonical workflow state. */
  static String canonicalState(JsonNode status) {
    String category = status.path("statusCategory").path("key").asText("");
    if ("done".equals(category)) {
      return "DONE";
    }
    if ("new".equals(category)) {
      return "TODO";
    }
    return canonicalStateName(status.path("name").asText(""));
  }

  /** Maps a Jira status NAME to the canonical workflow state (blocked/review aware). */
  static String canonicalStateName(String name) {
    String s = name.toLowerCase(Locale.ROOT);
    if (s.contains("block") || s.contains("imped") || s.contains("hold")) {
      return "BLOCKED";
    }
    if (s.contains("review") || s.contains("approval")) {
      return "IN_REVIEW";
    }
    if (s.contains("done") || s.contains("closed") || s.contains("resolved")) {
      return "DONE";
    }
    if (s.contains("to do")
        || s.contains("todo")
        || s.contains("open")
        || s.contains("backlog")
        || s.contains("new")) {
      return "TODO";
    }
    return "IN_PROGRESS";
  }

  /** Normalizes Jira's {@code +0000}-style timestamp to a strict ISO-8601 instant string. */
  static String toInstant(String jiraTimestamp) {
    try {
      return OffsetDateTime.parse(jiraTimestamp, JIRA_TS).toInstant().toString();
    } catch (java.time.format.DateTimeParseException e) {
      return Instant.parse(jiraTimestamp).toString(); // already strict ISO-8601
    }
  }

  private static String baseUrl(ConnectorConfig config) {
    String base = config.require("baseUrl");
    return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }

  private static String authorization(ConnectorConfig config) {
    String secret = config.secret();
    if (secret == null || secret.isBlank()) {
      throw new IllegalArgumentException("Jira requires an API token secret");
    }
    return SourceHttp.basic(config.require("email"), secret);
  }

  private static String jql(ConnectorConfig config) {
    String keys = config.settings().getOrDefault("projectKeys", "").trim();
    if (keys.isEmpty()) {
      return "order by created asc";
    }
    String in =
        String.join(
            ",", java.util.Arrays.stream(keys.split(",")).map(String::trim).toArray(String[]::new));
    return "project in (" + in + ") order by created asc";
  }

  private static String instance(SyncContext context) {
    return context.config().settings().getOrDefault("baseUrl", "jira");
  }

  private static @Nullable String textOrNull(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return (v == null || v.isNull()) ? null : v.asText();
  }
}
