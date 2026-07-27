/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.jira;

import com.eip.connectors.http.SourceHttp;
import com.eip.connectors.http.SourceHttp.JsonResponse;
import com.eip.connectors.spi.Connector;
import com.eip.connectors.spi.ConnectorConfig;
import com.eip.connectors.spi.ConnectorDescriptor;
import com.eip.connectors.spi.FetchKind;
import com.eip.connectors.spi.Op;
import com.eip.connectors.spi.RawRecord;
import com.eip.connectors.spi.SyncContext;
import com.eip.connectors.spi.TestConnectionOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 *
 * <p><b>Incremental sync (TASK-0021 Wave 2C):</b> when {@link SyncContext#cursor()} carries an
 * {@code updatedSince} instant, {@code sync} narrows its JQL with {@code updated >= "..."} and
 * emits every record as {@link FetchKind#INCREMENTAL} instead of {@link FetchKind#FULL}. Jira's
 * bare JQL datetime literals (minute precision, no offset) are interpreted in the searching user's
 * Jira <em>profile</em> timezone, not UTC — a v0.1 approximation, since the service account's
 * profile timezone is not modeled here. A fixed {@value #OVERLAP_MINUTES}-minute overlap is
 * subtracted from the cursor before formatting, which absorbs both that timezone skew and any clock
 * drift between this service and Jira: the re-fetched overlap tail is free because staging is
 * content-hash idempotent (unchanged issues upsert as no-ops).
 *
 * <p><b>Changelog pagination (DEBT-018 item 5):</b> the search response embeds each issue's first
 * changelog page (Cloud caps it around 100 entries). When the embedded {@code changelog.total}
 * exceeds the embedded entry count, {@code emitIssue} pages the remainder via the dedicated {@code
 * GET /rest/api/3/issue/{issueId}/changelog} endpoint (bounded, see {@link
 * #fetchOverflowChangelog}) and merges all entries chronologically before emitting transitions — an
 * issue with a complete embedded changelog makes no extra call.
 */
public final class JiraConnector implements Connector {

  /** The {@code core.connector.type} discriminator. */
  public static final String TYPE = "jira";

  private static final int PAGE_SIZE = 100;
  private static final int MAX_PAGES = 50; // v0.1 bound: 5000 issues per sync
  private static final int CHANGELOG_PAGE_SIZE = 100;
  private static final int MAX_CHANGELOG_PAGES = 10; // v0.1 bound: 1000 extra history entries/issue
  private static final int OVERLAP_MINUTES = 10;
  private static final DateTimeFormatter JIRA_TS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT);
  private static final DateTimeFormatter JIRA_JQL_MINUTE =
      DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC);

  private static final ConnectorDescriptor DESCRIPTOR =
      new ConnectorDescriptor(
          TYPE,
          "Atlassian Jira",
          "Work items, sprints and workflow transitions from Jira Cloud/Data Center.",
          """
          {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
           "title":"Jira","properties":{
             "baseUrl":{"type":"string","format":"uri","title":"Base URL",
                        "description":"e.g. https://your-org.atlassian.net"},
             "email":{"type":"string","title":"Service account e-mail",
                      "description":"Integration (service) account for API auth only — EIP never surfaces individual activity (NFR-071)"},
             "projectKeys":{"type":"string","title":"Project keys",
                            "description":"Comma-separated, e.g. PLAT,PAY"},
             "webhookToken":{"type":"string","title":"Webhook token (optional)",
                             "description":"Optional shared token that authorizes webhook-triggered syncs (X-EIP-Webhook-Token header)"}},
           "required":["baseUrl","email"]}
          """,
          "API token");

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
  public ConnectorDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public boolean simulation() {
    return false;
  }

  @Override
  public boolean syncAvailable() {
    return true;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Jira is the sole connector that actually reads {@link SyncContext#cursor()} today (the
   * {@code updatedSince} JQL narrowing in {@link #jql(ConnectorConfig, Map)}), so it is the sole
   * override of this flag (DEBT-018 item 3).
   */
  @Override
  public boolean incrementalSupported() {
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
    Map<String, String> cursor = context.cursor();
    String auth = authorization(config);
    String base = baseUrl(config);
    String jql = jql(config, cursor);
    FetchKind kind = cursor.containsKey("updatedSince") ? FetchKind.INCREMENTAL : FetchKind.FULL;

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
        emitIssue(context, issue, kind, base, auth);
      }
      int total = response.body().path("total").asInt(0);
      startAt += issues.size();
      if (startAt >= total || issues.isEmpty()) {
        return;
      }
    }
  }

  private void emitIssue(
      SyncContext context, JsonNode issue, FetchKind kind, String base, String auth) {
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
            RawRecord.ofFlat(
                "work_item", key, "jira", instance(context), "jira:" + id, Op.UPSERT, kind, item));

    // Changelog → canonical transitions, chronological. The search response embeds the first
    // changelog page per issue (Cloud caps it around 100 entries — ConnectorFramework §11.1); when
    // Jira's own total says more history exists than what came back embedded, page the rest via the
    // dedicated /changelog endpoint (DEBT-018 item 5) instead of silently truncating history.
    JsonNode changelog = issue.path("changelog");
    List<JsonNode> histories = new ArrayList<>();
    changelog.path("histories").forEach(histories::add);
    int changelogTotal = changelog.path("total").asInt(histories.size());
    if (changelogTotal > histories.size()) {
      histories.addAll(fetchOverflowChangelog(base, auth, id, histories.size(), changelogTotal));
    }
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
                RawRecord.ofFlat(
                    "work_item_transition",
                    key + "#" + history.path("id").asText(seq + ""),
                    "jira",
                    instance(context),
                    "jira:" + id + "#" + history.path("id").asText(seq + ""),
                    Op.UPSERT,
                    kind,
                    transition));
      }
    }
  }

  /**
   * Pages the dedicated {@code GET /rest/api/3/issue/{issueId}/changelog} endpoint for the history
   * entries the search response's embedded {@code changelog.histories} truncated (DEBT-018 item 5).
   * Confirmed against the Jira Cloud REST v3 "Get changelogs" endpoint: this endpoint's response
   * shape names its entry array {@code values} — NOT {@code histories}, unlike the search
   * response's embedded object — but each entry has the identical {@code id}/{@code created}/{@code
   * items} shape, so the merge below needs no separate mapping. Bounded to {@value
   * #MAX_CHANGELOG_PAGES} extra pages per issue, matching this connector's existing bounded-
   * pagination style (v0.1: a pathological single-issue history beyond that many extra pages is
   * truncated rather than fetched without bound).
   *
   * @param base the Jira base URL
   * @param auth the resolved Basic auth header value
   * @param issueId the issue's immutable native id
   * @param alreadyFetched how many history entries the embedded changelog already carried (the
   *     {@code startAt} to resume from)
   * @param total the changelog's total entry count, per Jira's own {@code changelog.total}
   * @return the additional history entries, in the order Jira returned them
   */
  private List<JsonNode> fetchOverflowChangelog(
      String base, String auth, String issueId, int alreadyFetched, int total) {
    List<JsonNode> extra = new ArrayList<>();
    int fetched = alreadyFetched;
    for (int page = 0; page < MAX_CHANGELOG_PAGES && fetched < total; page++) {
      JsonResponse response =
          http.getJson(
              base
                  + "/rest/api/3/issue/"
                  + issueId
                  + "/changelog?startAt="
                  + fetched
                  + "&maxResults="
                  + CHANGELOG_PAGE_SIZE,
              auth);
      if (response.status() != 200) {
        throw new IllegalStateException(
            "Jira changelog fetch failed: HTTP " + response.status() + " for issue " + issueId);
      }
      JsonNode values = response.body().path("values");
      if (!values.isArray() || values.isEmpty()) {
        break;
      }
      values.forEach(extra::add);
      fetched += values.size();
    }
    return extra;
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

  /**
   * Builds the JQL for one sync. A {@code project in (...)} clause when project keys are
   * configured, an {@code updated >= "..."} clause when the cursor carries {@code updatedSince}
   * (see the class javadoc for the overlap-window rationale), joined with {@code AND} — {@code
   * order by created asc} is always last (Jira JQL requires it trailing).
   */
  private static String jql(ConnectorConfig config, Map<String, String> cursor) {
    List<String> clauses = new ArrayList<>();
    String keys = config.settings().getOrDefault("projectKeys", "").trim();
    if (!keys.isEmpty()) {
      String in =
          String.join(
              ",",
              java.util.Arrays.stream(keys.split(",")).map(String::trim).toArray(String[]::new));
      clauses.add("project in (" + in + ")");
    }
    @Nullable String updatedSince = cursor.get("updatedSince");
    if (updatedSince != null) {
      clauses.add("updated >= \"" + overlapWindowStart(updatedSince) + "\"");
    }
    String prefix = clauses.isEmpty() ? "" : String.join(" AND ", clauses) + " ";
    return prefix + "order by created asc";
  }

  /**
   * Formats the incremental cursor as a Jira JQL minute-precision, UTC-labeled datetime literal,
   * subtracting the {@value #OVERLAP_MINUTES}-minute overlap window (class javadoc).
   */
  private static String overlapWindowStart(String updatedSinceIso) {
    Instant cursorInstant =
        Instant.parse(updatedSinceIso).minus(Duration.ofMinutes(OVERLAP_MINUTES));
    return JIRA_JQL_MINUTE.format(cursorInstant);
  }

  private static String instance(SyncContext context) {
    return context.config().settings().getOrDefault("baseUrl", "jira");
  }

  private static @Nullable String textOrNull(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return (v == null || v.isNull()) ? null : v.asText();
  }
}
