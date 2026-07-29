/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.github;

import com.eip.connectors.common.JiraKeyExtractor;
import com.eip.connectors.common.SourceTimestamps;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Real GitHub connector (REST v3, Bearer PAT/App token). {@code testConnection} authenticates
 * against {@code /user}; {@code sync} pages every organization repository and, per repository,
 * emits {@code pull_request} raw records (with a bounded first page of {@code code_review}s per
 * PR), {@code work_item} raw records from issues (excluding entries GitHub also lists as pull
 * requests), and {@code build} raw records from the first page of Actions workflow runs. Strict
 * ISO-8601 instants; NEVER a person identifier — reviews and issues are attributed to the artifact
 * only (NFR-071).
 */
public final class GitHubConnector implements Connector {

  /** The {@code core.connector.type} discriminator. */
  public static final String TYPE = "github";

  private static final String DEFAULT_BASE_URL = "https://api.github.com";
  private static final int PAGE_SIZE = 100;
  private static final int MAX_REPO_PAGES = 10; // v0.1 bound: 1000 repositories per org
  private static final int MAX_PR_PAGES_PER_REPO = 20; // v0.1 bound: 2000 PRs per repository
  private static final int MAX_ISSUE_PAGES_PER_REPO = 10; // v0.1 bound: 1000 issues per repository

  private static final ConnectorDescriptor DESCRIPTOR =
      new ConnectorDescriptor(
          TYPE,
          "GitHub",
          "Repositories, pull requests, reviews, issues and Actions builds from GitHub.",
          """
          {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
           "title":"GitHub","properties":{
             "baseUrl":{"type":"string","format":"uri","title":"Base URL",
                        "description":"e.g. https://api.github.com (GitHub Enterprise Server: your own API host)"},
             "org":{"type":"string","title":"Organization"},
             "webhookToken":{"type":"string","title":"Webhook token (optional)",
                             "description":"Optional shared token that authorizes webhook-triggered syncs (X-EIP-Webhook-Token header)"}},
           "required":["org"]}
          """,
          "Personal access token");

  private final SourceHttp http;

  /** Creates the connector with its own HTTP client. */
  public GitHubConnector() {
    this(new SourceHttp());
  }

  GitHubConnector(SourceHttp http) {
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

  @Override
  public TestConnectionOutcome testConnection(ConnectorConfig config) {
    String secret = config.secret();
    if (secret == null || secret.isBlank()) {
      return TestConnectionOutcome.failed("GitHub requires a personal access token secret");
    }
    try {
      JsonResponse response = http.getJson(baseUrl(config) + "/user", SourceHttp.bearer(secret));
      if (response.status() == 200) {
        return TestConnectionOutcome.ok("Authenticated to GitHub.");
      }
      if (response.status() == 401 || response.status() == 403) {
        return TestConnectionOutcome.failed(
            "GitHub rejected the credentials (HTTP " + response.status() + ").");
      }
      return TestConnectionOutcome.failed("Unexpected GitHub response: HTTP " + response.status());
    } catch (SourceHttp.SourceHttpException e) {
      return TestConnectionOutcome.failed(String.valueOf(e.getMessage()));
    }
  }

  @Override
  public void sync(SyncContext context) {
    ConnectorConfig config = context.config();
    String auth = authorization(config);
    String base = baseUrl(config);
    String org = config.require("org");
    String instance = instance(config);

    List<JsonNode> repos =
        fetchPages(base + "/orgs/" + org + "/repos?per_page=" + PAGE_SIZE, auth, MAX_REPO_PAGES);
    for (JsonNode repo : repos) {
      String repoName = repo.path("name").asText("");
      if (repoName.isBlank()) {
        continue;
      }
      emitPullRequests(context, base, auth, instance, org, repoName);
      emitIssues(context, base, auth, instance, org, repoName);
      emitWorkflowRuns(context, base, auth, instance, org, repoName);
    }
  }

  private void emitPullRequests(
      SyncContext context, String base, String auth, String instance, String org, String repo) {
    String repoUrl = base + "/repos/" + org + "/" + repo;
    List<JsonNode> pulls =
        fetchPages(repoUrl + "/pulls?state=all&per_page=" + PAGE_SIZE, auth, MAX_PR_PAGES_PER_REPO);
    for (JsonNode pr : pulls) {
      emitPullRequest(context, repoUrl, auth, instance, repo, pr);
    }
  }

  private void emitPullRequest(
      SyncContext context, String repoUrl, String auth, String instance, String repo, JsonNode pr) {
    long id = pr.path("id").asLong();
    int number = pr.path("number").asInt();
    String key = repo + "#" + number;
    String title = pr.path("title").asText("");
    String sourceBranch = pr.path("head").path("ref").asText("");
    String state = pr.path("state").asText(""); // "open" | "closed"
    @Nullable String mergedAtRaw = textOrNull(pr, "merged_at");
    String status =
        switch (state.toLowerCase(Locale.ROOT)) {
          case "open" -> "OPEN";
          default -> mergedAtRaw != null ? "MERGED" : "DECLINED"; // closed without merging
        };
    String createdAt = SourceTimestamps.fromIsoOffset(pr.path("created_at").asText());

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
            RawRecord.ofFlat(
                "pull_request",
                key,
                TYPE,
                instance,
                "github:" + id + "@" + repo,
                Op.UPSERT,
                FetchKind.FULL,
                payload));

    emitReviews(context, repoUrl, auth, instance, number, key, createdAt);
  }

  private void emitReviews(
      SyncContext context,
      String repoUrl,
      String auth,
      String instance,
      int prNumber,
      String prKey,
      String requestedAt) {
    // v0.1 bound: one reviews call per PR, first page only — every PR fetched this sync gets its
    // reviews refreshed; deeper review history needs cursor-based pagination (documented, matches
    // Bitbucket's activity-feed bound).
    JsonResponse response =
        http.getJson(repoUrl + "/pulls/" + prNumber + "/reviews?per_page=" + PAGE_SIZE, auth);
    if (response.status() != 200) {
      throw new IllegalStateException(
          "GitHub reviews fetch failed: HTTP " + response.status() + " for " + prKey);
    }
    for (JsonNode review : response.body()) {
      String state = review.path("state").asText("");
      @Nullable String outcome =
          switch (state.toUpperCase(Locale.ROOT)) {
            case "APPROVED" -> "APPROVED";
            case "CHANGES_REQUESTED" -> "CHANGES_REQUESTED";
            default -> null; // COMMENTED/DISMISSED/PENDING carry no canonical review outcome
          };
      if (outcome == null) {
        continue;
      }
      long reviewId = review.path("id").asLong();
      String reviewKey = prKey + "/review/" + reviewId;
      String completedAt = SourceTimestamps.fromIsoOffset(review.path("submitted_at").asText());

      Map<String, String> payload = new LinkedHashMap<>();
      payload.put("key", reviewKey);
      payload.put("pullRequestKey", prKey);
      payload.put("outcome", outcome);
      // GitHub's review resource has no "requested" timestamp; the PR's own createdAt is used as
      // the request instant (documented approximation, v0.1 — same choice as Bitbucket).
      payload.put("requestedAt", requestedAt);
      payload.put("completedAt", completedAt);

      context
          .rawSink()
          .emit(
              RawRecord.ofFlat(
                  "code_review",
                  reviewKey,
                  TYPE,
                  instance,
                  "github:" + reviewId + "@" + prKey,
                  Op.UPSERT,
                  FetchKind.FULL,
                  payload));
    }
  }

  private void emitIssues(
      SyncContext context, String base, String auth, String instance, String org, String repo) {
    String repoUrl = base + "/repos/" + org + "/" + repo;
    List<JsonNode> issues =
        fetchPages(
            repoUrl + "/issues?state=all&per_page=" + PAGE_SIZE, auth, MAX_ISSUE_PAGES_PER_REPO);
    for (JsonNode issue : issues) {
      if (issue.has("pull_request")) {
        continue; // GitHub lists pull requests as issues too; skip (documented)
      }
      int number = issue.path("number").asInt();
      long id = issue.path("id").asLong();
      String key = org + "/" + repo + "#" + number;
      String title = issue.path("title").asText("");
      String state = issue.path("state").asText(""); // "open" | "closed"
      String status = "open".equals(state.toLowerCase(Locale.ROOT)) ? "TODO" : "DONE";
      String createdAt = SourceTimestamps.fromIsoOffset(issue.path("created_at").asText());

      Map<String, String> payload = new LinkedHashMap<>();
      payload.put("key", key);
      // GitHub issues carry no Jira-style issue type; "issue" falls through the normalizer's
      // default branch to the canonical TASK type (documented, honest v0.1 simplification).
      payload.put("type", "issue");
      payload.put("title", title);
      payload.put("status", status);
      payload.put("createdAt", createdAt);
      if ("DONE".equals(status)) {
        @Nullable String closedAt = textOrNull(issue, "closed_at");
        if (closedAt != null) {
          payload.put("resolvedAt", SourceTimestamps.fromIsoOffset(closedAt));
        }
      }

      context
          .rawSink()
          .emit(
              RawRecord.ofFlat(
                  "work_item",
                  key,
                  TYPE,
                  instance,
                  "github:" + id + "@" + repo,
                  Op.UPSERT,
                  FetchKind.FULL,
                  payload));
    }
    // No work_item_transition stream: GitHub issues expose no changelog-equivalent in this API;
    // cycle-time decomposition needs transitions, so v0.1 issues contribute basic counts only
    // (honest limitation, documented).
  }

  private void emitWorkflowRuns(
      SyncContext context, String base, String auth, String instance, String org, String repo) {
    // v0.1 bound: first page only, per repository (documented) — deeper backfill needs
    // cursor-based pagination (DEBT-018).
    JsonResponse response =
        http.getJson(
            base + "/repos/" + org + "/" + repo + "/actions/runs?per_page=" + PAGE_SIZE, auth);
    if (response.status() != 200) {
      throw new IllegalStateException(
          "GitHub workflow runs fetch failed: HTTP " + response.status() + " for " + repo);
    }
    for (JsonNode run : response.body().path("workflow_runs")) {
      String runStatus = run.path("status").asText("");
      if (!"completed".equals(runStatus.toLowerCase(Locale.ROOT))) {
        continue; // still queued/in_progress: no reliable finish instant yet (never fabricate)
      }
      long runId = run.path("id").asLong();
      String key = repo + "/actions/" + runId;
      String conclusion = run.path("conclusion").asText("");
      String status = "success".equals(conclusion.toLowerCase(Locale.ROOT)) ? "SUCCESS" : "FAILED";
      String startedAt = SourceTimestamps.fromIsoOffset(run.path("run_started_at").asText());
      // Actions has no distinct "run finished at" field; updated_at is the closest available
      // signal once a run reaches "completed" status (documented approximation, v0.1 — same
      // choice as Bitbucket's mergedAt).
      String finishedAt = SourceTimestamps.fromIsoOffset(run.path("updated_at").asText());

      Map<String, String> payload = new LinkedHashMap<>();
      payload.put("key", key);
      // pullRequestKey intentionally OMITTED: a workflow run's "pull_requests" array is unreliable
      // for cross-repo/fork PRs and head-branch races (v0.1; documented, never fabricated).
      payload.put("status", status);
      payload.put("startedAt", startedAt);
      payload.put("finishedAt", finishedAt);

      context
          .rawSink()
          .emit(
              RawRecord.ofFlat(
                  "build",
                  key,
                  TYPE,
                  instance,
                  "github:actions:" + runId + "@" + repo,
                  Op.UPSERT,
                  FetchKind.FULL,
                  payload));
    }
  }

  /**
   * Follows GitHub's page-number pagination, stopping once a page returns fewer than a full page.
   */
  private List<JsonNode> fetchPages(String urlWithoutPageParam, String auth, int maxPages) {
    List<JsonNode> values = new ArrayList<>();
    for (int page = 1; page <= maxPages; page++) {
      String url = urlWithoutPageParam + "&page=" + page;
      JsonResponse response = http.getJson(url, auth);
      if (response.status() != 200) {
        throw new IllegalStateException(
            "GitHub request failed: HTTP " + response.status() + " for " + url);
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
    String base = config.settings().getOrDefault("baseUrl", DEFAULT_BASE_URL);
    return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }

  private static String authorization(ConnectorConfig config) {
    String secret = config.secret();
    if (secret == null || secret.isBlank()) {
      throw new IllegalArgumentException("GitHub requires a personal access token secret");
    }
    return SourceHttp.bearer(secret);
  }

  private static String instance(ConnectorConfig config) {
    return config.settings().getOrDefault("baseUrl", "github");
  }

  private static @Nullable String textOrNull(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return (v == null || v.isNull()) ? null : v.asText();
  }
}
