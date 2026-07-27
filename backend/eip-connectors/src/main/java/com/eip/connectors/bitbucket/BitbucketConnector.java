/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.bitbucket;

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
 * Real Bitbucket Cloud connector (API 2.0, Basic auth with a service-account username + app
 * password). {@code testConnection} authenticates against {@code /2.0/user}; {@code sync} pages
 * every workspace repository and its merged/open pull requests, emitting {@code pull_request} +
 * {@code code_review} raw records with strict ISO-8601 instants and a best-effort Jira issue-key
 * link (branch name, falling back to title). Reviews are attributed to the pull request only —
 * never a person (NFR-071).
 */
public final class BitbucketConnector implements Connector {

  /** The {@code core.connector.type} discriminator. */
  public static final String TYPE = "bitbucket";

  private static final int REPO_PAGE_SIZE = 50;
  private static final int MAX_REPO_PAGES = 20; // v0.1 bound: 1000 repositories per workspace
  private static final int PR_PAGE_SIZE = 50;
  private static final int MAX_PR_PAGES_PER_REPO = 40; // v0.1 bound: 2000 PRs per repository
  private static final int ACTIVITY_PAGE_SIZE = 50; // v0.1: first page only, per fetched PR

  private static final ConnectorDescriptor DESCRIPTOR =
      new ConnectorDescriptor(
          TYPE,
          "Bitbucket",
          "Repositories, pull requests, reviews and commits from Bitbucket Cloud/Server.",
          """
          {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
           "title":"Bitbucket","properties":{
             "baseUrl":{"type":"string","format":"uri","title":"Base URL",
                        "description":"e.g. https://api.bitbucket.org"},
             "username":{"type":"string","title":"Service account username"},
             "workspace":{"type":"string","title":"Workspace / project"},
             "webhookToken":{"type":"string","title":"Webhook token (optional)",
                             "description":"Optional shared token that authorizes webhook-triggered syncs (X-EIP-Webhook-Token header)"}},
           "required":["baseUrl","username","workspace"]}
          """,
          "App password / token");

  private final SourceHttp http;

  /** Creates the connector with its own HTTP client. */
  public BitbucketConnector() {
    this(new SourceHttp());
  }

  BitbucketConnector(SourceHttp http) {
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
      return TestConnectionOutcome.failed("Bitbucket requires an app password / token secret");
    }
    try {
      String base = baseUrl(config);
      JsonResponse response =
          http.getJson(base + "/2.0/user", SourceHttp.basic(config.require("username"), secret));
      if (response.status() == 200) {
        return TestConnectionOutcome.ok("Authenticated to Bitbucket.");
      }
      if (response.status() == 401 || response.status() == 403) {
        return TestConnectionOutcome.failed(
            "Bitbucket rejected the credentials (HTTP " + response.status() + ").");
      }
      return TestConnectionOutcome.failed(
          "Unexpected Bitbucket response: HTTP " + response.status());
    } catch (SourceHttp.SourceHttpException e) {
      return TestConnectionOutcome.failed(String.valueOf(e.getMessage()));
    }
  }

  @Override
  public void sync(SyncContext context) {
    ConnectorConfig config = context.config();
    String auth = authorization(config);
    String base = baseUrl(config);
    String workspace = config.require("workspace");
    String instance = instance(config);

    List<JsonNode> repos =
        fetchPages(
            base + "/2.0/repositories/" + workspace + "?pagelen=" + REPO_PAGE_SIZE,
            auth,
            MAX_REPO_PAGES);
    for (JsonNode repo : repos) {
      String slug = repo.path("slug").asText("");
      if (slug.isBlank()) {
        continue;
      }
      String repoUrl = base + "/2.0/repositories/" + workspace + "/" + slug;
      List<JsonNode> pullRequests =
          fetchPages(
              repoUrl + "/pullrequests?state=MERGED&state=OPEN&pagelen=" + PR_PAGE_SIZE,
              auth,
              MAX_PR_PAGES_PER_REPO);
      for (JsonNode pr : pullRequests) {
        emitPullRequest(context, repoUrl, auth, instance, slug, pr);
      }
    }
  }

  private void emitPullRequest(
      SyncContext context, String repoUrl, String auth, String instance, String slug, JsonNode pr) {
    long id = pr.path("id").asLong();
    String key = slug + "#" + id;
    String title = pr.path("title").asText("");
    String sourceBranch = pr.path("source").path("branch").path("name").asText("");
    String status = canonicalStatus(pr.path("state").asText(""));
    String createdAt = SourceTimestamps.fromIsoOffset(pr.path("created_on").asText());

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
    if ("MERGED".equals(status)) {
      // Bitbucket's PR resource has no explicit "merged at" field; updated_on is the closest
      // available signal once a PR has reached MERGED state (documented approximation, v0.1).
      payload.put("mergedAt", SourceTimestamps.fromIsoOffset(pr.path("updated_on").asText()));
    }

    context
        .rawSink()
        .emit(
            RawRecord.ofFlat(
                "pull_request",
                key,
                TYPE,
                instance,
                "bitbucket:" + id + "@" + slug,
                Op.UPSERT,
                FetchKind.FULL,
                payload));

    emitCodeReviews(context, repoUrl, auth, instance, slug, id, key, createdAt);
  }

  private void emitCodeReviews(
      SyncContext context,
      String repoUrl,
      String auth,
      String instance,
      String slug,
      long prId,
      String prKey,
      String requestedAt) {
    // v0.1 bound: one activity call per PR, first page only — every PR fetched this sync (the
    // "sync window") gets its reviews refreshed; deeper activity history is out of scope until
    // cursor-based pagination lands (documented).
    JsonResponse response =
        http.getJson(
            repoUrl + "/pullrequests/" + prId + "/activity?pagelen=" + ACTIVITY_PAGE_SIZE, auth);
    if (response.status() != 200) {
      throw new IllegalStateException(
          "Bitbucket activity fetch failed: HTTP " + response.status() + " for " + prKey);
    }
    int seq = 0;
    for (JsonNode entry : response.body().path("values")) {
      JsonNode approval = entry.path("approval");
      JsonNode changesRequested = entry.path("changes_requested");
      String outcome;
      String completedAt;
      if (!approval.isMissingNode() && !approval.isNull()) {
        outcome = "APPROVED";
        completedAt = SourceTimestamps.fromIsoOffset(approval.path("date").asText());
      } else if (!changesRequested.isMissingNode() && !changesRequested.isNull()) {
        outcome = "CHANGES_REQUESTED";
        completedAt = SourceTimestamps.fromIsoOffset(changesRequested.path("date").asText());
      } else {
        continue; // comment/update activity entries carry no review outcome
      }
      seq++;
      String reviewKey = prKey + "/approval/" + seq;

      Map<String, String> payload = new LinkedHashMap<>();
      payload.put("key", reviewKey);
      payload.put("pullRequestKey", prKey);
      payload.put("outcome", outcome);
      // Bitbucket's activity feed has no explicit "review requested" timestamp; the PR's own
      // createdAt is used as the request instant (documented approximation, v0.1).
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
                  "bitbucket:" + prId + "@" + slug + "/activity/" + seq,
                  Op.UPSERT,
                  FetchKind.FULL,
                  payload));
    }
  }

  /** Follows Bitbucket's {@code next}-link pagination, concatenating each page's {@code values}. */
  private List<JsonNode> fetchPages(String firstUrl, String auth, int maxPages) {
    List<JsonNode> values = new ArrayList<>();
    @Nullable String url = firstUrl;
    for (int page = 0; page < maxPages && url != null; page++) {
      JsonResponse response = http.getJson(url, auth);
      if (response.status() != 200) {
        throw new IllegalStateException(
            "Bitbucket request failed: HTTP " + response.status() + " for " + url);
      }
      response.body().path("values").forEach(values::add);
      JsonNode next = response.body().path("next");
      url = (next.isMissingNode() || next.isNull()) ? null : next.asText();
    }
    return values;
  }

  /** Maps Bitbucket's PR {@code state} to the canonical pull-request status vocabulary. */
  static String canonicalStatus(String bitbucketState) {
    return switch (bitbucketState.toUpperCase(Locale.ROOT)) {
      case "MERGED" -> "MERGED";
      case "OPEN" -> "OPEN";
      default -> "DECLINED"; // DECLINED, SUPERSEDED, or unrecognized collapse to DECLINED
    };
  }

  private static String baseUrl(ConnectorConfig config) {
    String base = config.require("baseUrl");
    return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }

  private static String authorization(ConnectorConfig config) {
    String secret = config.secret();
    if (secret == null || secret.isBlank()) {
      throw new IllegalArgumentException("Bitbucket requires an app password / token secret");
    }
    return SourceHttp.basic(config.require("username"), secret);
  }

  private static String instance(ConnectorConfig config) {
    return config.settings().getOrDefault("baseUrl", "bitbucket");
  }
}
