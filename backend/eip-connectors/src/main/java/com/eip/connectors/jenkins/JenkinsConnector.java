/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.jenkins;

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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Real Jenkins connector (classic REST API, Basic auth with a service-account username + API
 * token). {@code testConnection} authenticates against {@code /api/json}; {@code sync} lists
 * top-level jobs (folder/multibranch recursion is out of scope for v0.1, documented) and, per job,
 * pages the most recent builds into canonical {@code build} raw records. Timestamps are epoch
 * millis from the source — no string parsing needed. Builds with no {@code result} yet (still
 * running) are skipped rather than fabricating a finish instant. Carries NO person identifiers
 * (NFR-071).
 */
public final class JenkinsConnector implements Connector {

  /** The {@code core.connector.type} discriminator. */
  public static final String TYPE = "jenkins";

  private static final int MAX_JOBS = 50; // v0.1 bound: top-level jobs only
  private static final int MAX_BUILDS_PER_JOB = 50;

  private static final ConnectorDescriptor DESCRIPTOR =
      new ConnectorDescriptor(
          TYPE,
          "Jenkins",
          "Jobs and build results from a Jenkins controller.",
          """
          {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
           "title":"Jenkins","properties":{
             "baseUrl":{"type":"string","format":"uri","title":"Base URL",
                        "description":"e.g. https://ci.example.com"},
             "username":{"type":"string","title":"Service account username"},
             "webhookToken":{"type":"string","title":"Webhook token (optional)",
                             "description":"Optional shared token that authorizes webhook-triggered syncs (X-EIP-Webhook-Token header)"}},
           "required":["baseUrl","username"]}
          """,
          "API token");

  private final SourceHttp http;

  /** Creates the connector with its own HTTP client. */
  public JenkinsConnector() {
    this(new SourceHttp());
  }

  JenkinsConnector(SourceHttp http) {
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
      return TestConnectionOutcome.failed("Jenkins requires an API token secret");
    }
    try {
      JsonResponse response = http.getJson(baseUrl(config) + "/api/json", authorization(config));
      if (response.status() == 200) {
        return TestConnectionOutcome.ok("Authenticated to Jenkins.");
      }
      if (response.status() == 401 || response.status() == 403) {
        return TestConnectionOutcome.failed(
            "Jenkins rejected the credentials (HTTP " + response.status() + ").");
      }
      return TestConnectionOutcome.failed("Unexpected Jenkins response: HTTP " + response.status());
    } catch (SourceHttp.SourceHttpException e) {
      return TestConnectionOutcome.failed(String.valueOf(e.getMessage()));
    }
  }

  @Override
  public void sync(SyncContext context) {
    ConnectorConfig config = context.config();
    String auth = authorization(config);
    String base = baseUrl(config);
    String instance = instance(config);

    // v0.1 bound: top-level jobs only, one call (documented) — folder/multibranch recursive
    // discovery is out of scope until DEBT-018 lands. The Jenkins "tree" query uses [] and {}
    // range syntax, both illegal in a bare java.net.URI query — percent-encode the value so the
    // request URI stays well-formed.
    JsonResponse response = http.getJson(base + "/api/json?tree=" + encode("jobs[name,url]"), auth);
    if (response.status() != 200) {
      throw new IllegalStateException("Jenkins jobs fetch failed: HTTP " + response.status());
    }
    int count = 0;
    for (JsonNode job : response.body().path("jobs")) {
      if (count >= MAX_JOBS) {
        break;
      }
      count++;
      String name = job.path("name").asText("");
      String url = job.path("url").asText("");
      if (name.isBlank() || url.isBlank()) {
        continue;
      }
      emitBuilds(context, auth, instance, name, url);
    }
  }

  private void emitBuilds(
      SyncContext context, String auth, String instance, String jobName, String jobUrl) {
    String jobBase = jobUrl.endsWith("/") ? jobUrl : jobUrl + "/";
    String treeValue = "builds[number,timestamp,duration,result]{," + MAX_BUILDS_PER_JOB + "}";
    JsonResponse response = http.getJson(jobBase + "api/json?tree=" + encode(treeValue), auth);
    if (response.status() != 200) {
      throw new IllegalStateException(
          "Jenkins builds fetch failed: HTTP " + response.status() + " for " + jobName);
    }
    for (JsonNode build : response.body().path("builds")) {
      @Nullable String result = textOrNull(build, "result");
      if (result == null) {
        continue; // still building: never fabricate a finishedAt (documented tolerance case)
      }
      int number = build.path("number").asInt();
      long timestamp = build.path("timestamp").asLong();
      long duration = build.path("duration").asLong();
      String key = jobName + "#" + number;
      String status = "SUCCESS".equals(result.toUpperCase(Locale.ROOT)) ? "SUCCESS" : "FAILED";
      String startedAt = Instant.ofEpochMilli(timestamp).toString();
      String finishedAt = Instant.ofEpochMilli(timestamp + duration).toString();

      Map<String, String> payload = new LinkedHashMap<>();
      payload.put("key", key);
      // pullRequestKey intentionally OMITTED: the classic Jenkins build API carries no reliable
      // PR/MR linkage (v0.1; documented, never fabricated).
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
                  "jenkins:" + jobName + "#" + number,
                  Op.UPSERT,
                  FetchKind.FULL,
                  payload));
    }
  }

  private static String baseUrl(ConnectorConfig config) {
    String base = config.require("baseUrl");
    return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }

  private static String authorization(ConnectorConfig config) {
    String secret = config.secret();
    if (secret == null || secret.isBlank()) {
      throw new IllegalArgumentException("Jenkins requires an API token secret");
    }
    return SourceHttp.basic(config.require("username"), secret);
  }

  private static String instance(ConnectorConfig config) {
    return config.settings().getOrDefault("baseUrl", "jenkins");
  }

  private static @Nullable String textOrNull(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return (v == null || v.isNull()) ? null : v.asText();
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
