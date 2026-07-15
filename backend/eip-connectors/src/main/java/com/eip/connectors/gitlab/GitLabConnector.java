/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.gitlab;

import com.eip.connectors.common.JiraKeyExtractor;
import com.eip.connectors.common.SourceTimestamps;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Real GitLab connector (API v4, Bearer personal access token). {@code testConnection}
 * authenticates against {@code /api/v4/user}; {@code sync} pages every group project (including
 * subgroups) and, per project, emits {@code pull_request} raw records from merge requests plus
 * {@code build} raw records from the first page of pipelines. GitLab's approval state exposes
 * approval booleans but no per-approval timestamp, so — never fabricating a required instant — v0.1
 * emits NO {@code code_review} records for this source (documented limitation). Strict ISO-8601
 * instants; NEVER a person identifier (NFR-071).
 */
public final class GitLabConnector implements Connector {

  /** The {@code core.connector.type} discriminator. */
  public static final String TYPE = "gitlab";

  private static final int PAGE_SIZE = 100;
  private static final int MAX_PROJECT_PAGES = 10; // v0.1 bound: 1000 projects per group
  private static final int MAX_MR_PAGES_PER_PROJECT = 20; // v0.1 bound: 2000 MRs per project
  private static final Set<String> TERMINAL_PIPELINE_STATUSES =
      Set.of("success", "failed", "canceled", "skipped");

  private final SourceHttp http;

  /** Creates the connector with its own HTTP client. */
  public GitLabConnector() {
    this(new SourceHttp());
  }

  GitLabConnector(SourceHttp http) {
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
    String secret = config.secret();
    if (secret == null || secret.isBlank()) {
      return TestConnectionOutcome.failed("GitLab requires a personal access token secret");
    }
    try {
      JsonResponse response =
          http.getJson(baseUrl(config) + "/api/v4/user", SourceHttp.bearer(secret));
      if (response.status() == 200) {
        return TestConnectionOutcome.ok("Authenticated to GitLab.");
      }
      if (response.status() == 401 || response.status() == 403) {
        return TestConnectionOutcome.failed(
            "GitLab rejected the credentials (HTTP " + response.status() + ").");
      }
      return TestConnectionOutcome.failed("Unexpected GitLab response: HTTP " + response.status());
    } catch (SourceHttp.SourceHttpException e) {
      return TestConnectionOutcome.failed(String.valueOf(e.getMessage()));
    }
  }

  @Override
  public void sync(SyncContext context) {
    ConnectorConfig config = context.config();
    String auth = authorization(config);
    String base = baseUrl(config);
    String group = config.require("group");
    String instance = instance(config);

    List<JsonNode> projects =
        fetchPages(
            base
                + "/api/v4/groups/"
                + group
                + "/projects?include_subgroups=true&per_page="
                + PAGE_SIZE,
            auth,
            MAX_PROJECT_PAGES);
    for (JsonNode project : projects) {
      String path = project.path("path_with_namespace").asText("");
      if (path.isBlank()) {
        continue;
      }
      long projectId = project.path("id").asLong();
      emitMergeRequests(context, base, auth, instance, projectId, path);
      emitPipelines(context, base, auth, instance, projectId, path);
    }
  }

  private void emitMergeRequests(
      SyncContext context, String base, String auth, String instance, long projectId, String path) {
    List<JsonNode> mergeRequests =
        fetchPages(
            base
                + "/api/v4/projects/"
                + projectId
                + "/merge_requests?state=all&per_page="
                + PAGE_SIZE,
            auth,
            MAX_MR_PAGES_PER_PROJECT);
    for (JsonNode mr : mergeRequests) {
      emitMergeRequest(context, instance, path, mr);
    }
  }

  private void emitMergeRequest(SyncContext context, String instance, String path, JsonNode mr) {
    long id = mr.path("id").asLong();
    int iid = mr.path("iid").asInt();
    String key = path + "!" + iid;
    String title = mr.path("title").asText("");
    String sourceBranch = mr.path("source_branch").asText("");
    String state = mr.path("state").asText(""); // "opened" | "merged" | "closed" | "locked"
    @Nullable String mergedAtRaw = textOrNull(mr, "merged_at");
    String status =
        switch (state.toLowerCase(Locale.ROOT)) {
          case "opened", "locked" -> "OPEN";
          case "merged" -> "MERGED";
          default -> "DECLINED"; // "closed" without merging
        };
    String createdAt = SourceTimestamps.fromIsoOffset(mr.path("created_at").asText());

    Map<String, String> payload = new LinkedHashMap<>();
    payload.put("key", key);
    @Nullable String workItemKey = JiraKeyExtractor.extract(sourceBranch, title);
    if (workItemKey != null) {
      payload.put("workItemKey", workItemKey);
    }
    payload.put("title", title);
    payload.put("sourceBranch", sourceBranch);
    payload.put("status", status);
    payload.put("createdAt", createdAt);
    if ("MERGED".equals(status) && mergedAtRaw != null) {
      payload.put("mergedAt", SourceTimestamps.fromIsoOffset(mergedAtRaw));
    }

    context
        .rawSink()
        .emit(
            new RawRecord(
                "pull_request",
                key,
                TYPE,
                instance,
                "gitlab:" + id + "@" + path,
                Op.UPSERT,
                FetchKind.FULL,
                payload));

    // Deliberately NO code_review emission here: GitLab's /merge_requests/{iid}/approval_state
    // exposes per-rule approval booleans but no per-approval timestamp, and the raw contract
    // requires a real requestedAt/completedAt. Fabricating one would violate the platform-wide
    // "never invent a timestamp" rule (documented v0.1 limitation; a future DEBT could derive an
    // approximate instant from MR notes/events, which do carry timestamps).
  }

  private void emitPipelines(
      SyncContext context, String base, String auth, String instance, long projectId, String path) {
    // v0.1 bound: first page only, per project (documented) — deeper backfill needs cursor-based
    // pagination (DEBT-018).
    JsonResponse response =
        http.getJson(
            base + "/api/v4/projects/" + projectId + "/pipelines?per_page=" + PAGE_SIZE, auth);
    if (response.status() != 200) {
      throw new IllegalStateException(
          "GitLab pipelines fetch failed: HTTP " + response.status() + " for " + path);
    }
    for (JsonNode pipeline : response.body()) {
      String glStatus = pipeline.path("status").asText("").toLowerCase(Locale.ROOT);
      if (!TERMINAL_PIPELINE_STATUSES.contains(glStatus)) {
        continue; // still running/pending/etc: no reliable finish instant yet (never fabricate)
      }
      long id = pipeline.path("id").asLong();
      String key = path + "/pipeline/" + id;
      String status = "success".equals(glStatus) ? "SUCCESS" : "FAILED";
      String startedAt = SourceTimestamps.fromIsoOffset(pipeline.path("created_at").asText());
      // GitLab's pipeline list has no distinct "finished at" for every status; updated_at is the
      // closest available signal once a pipeline reaches a terminal status (documented
      // approximation, v0.1 — same choice as Bitbucket's mergedAt / GitHub's workflow-run finish).
      String finishedAt = SourceTimestamps.fromIsoOffset(pipeline.path("updated_at").asText());

      Map<String, String> payload = new LinkedHashMap<>();
      payload.put("key", key);
      // pullRequestKey intentionally OMITTED: a pipeline's ref is a branch name, not reliably a
      // merge-request natural key, from this endpoint alone (v0.1; documented, never fabricated).
      payload.put("status", status);
      payload.put("startedAt", startedAt);
      payload.put("finishedAt", finishedAt);

      context
          .rawSink()
          .emit(
              new RawRecord(
                  "build",
                  key,
                  TYPE,
                  instance,
                  "gitlab:pipeline:" + id + "@" + path,
                  Op.UPSERT,
                  FetchKind.FULL,
                  payload));
    }
  }

  /**
   * Follows GitLab's page-number pagination, stopping once a page returns fewer than a full page.
   */
  private List<JsonNode> fetchPages(String urlWithoutPageParam, String auth, int maxPages) {
    List<JsonNode> values = new ArrayList<>();
    for (int page = 1; page <= maxPages; page++) {
      String url = urlWithoutPageParam + "&page=" + page;
      JsonResponse response = http.getJson(url, auth);
      if (response.status() != 200) {
        throw new IllegalStateException(
            "GitLab request failed: HTTP " + response.status() + " for " + url);
      }
      int count = 0;
      for (JsonNode node : response.body()) {
        values.add(node);
        count++;
      }
      if (count < PAGE_SIZE) {
        break; // last page: fewer results than the requested per_page
      }
    }
    return values;
  }

  private static String baseUrl(ConnectorConfig config) {
    String base = config.require("baseUrl");
    return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }

  private static String authorization(ConnectorConfig config) {
    String secret = config.secret();
    if (secret == null || secret.isBlank()) {
      throw new IllegalArgumentException("GitLab requires a personal access token secret");
    }
    return SourceHttp.bearer(secret);
  }

  private static String instance(ConnectorConfig config) {
    return config.settings().getOrDefault("baseUrl", "gitlab");
  }

  private static @Nullable String textOrNull(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return (v == null || v.isNull()) ? null : v.asText();
  }
}
