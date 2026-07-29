/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.sonarqube;

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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Real SonarQube connector (Bearer user token). {@code testConnection} validates the token against
 * {@code /api/authentication/validate}; {@code sync} pages every project and emits one {@code
 * quality_gate} raw record per project (gate status + most recent analysis instant) plus one per
 * decorated pull request, when the edition supports PR decoration. Never fabricates an {@code
 * evaluatedAt} timestamp — projects with no analysis history yet are skipped (documented, v0.1).
 */
public final class SonarQubeConnector implements Connector {

  /** The {@code core.connector.type} discriminator. */
  public static final String TYPE = "sonarqube";

  private static final int PROJECT_PAGE_SIZE = 100;
  private static final int MAX_PROJECT_PAGES = 10; // v0.1 bound: 1000 projects per sync

  private static final ConnectorDescriptor DESCRIPTOR =
      new ConnectorDescriptor(
          TYPE,
          "SonarQube",
          "Quality gates, issues and coverage metrics from SonarQube.",
          """
          {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
           "title":"SonarQube","properties":{
             "baseUrl":{"type":"string","format":"uri","title":"Base URL"},
             "webhookToken":{"type":"string","title":"Webhook token (optional)",
                             "description":"Optional shared token that authorizes webhook-triggered syncs (X-EIP-Webhook-Token header)"}},
           "required":["baseUrl"]}
          """,
          "User token");

  private final SourceHttp http;

  /** Creates the connector with its own HTTP client. */
  public SonarQubeConnector() {
    this(new SourceHttp());
  }

  SonarQubeConnector(SourceHttp http) {
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
      return TestConnectionOutcome.failed("SonarQube requires a user token secret");
    }
    try {
      String base = baseUrl(config);
      JsonResponse response =
          http.getJson(base + "/api/authentication/validate", SourceHttp.bearer(secret));
      if (response.status() == 200 && response.body().path("valid").asBoolean(false)) {
        return TestConnectionOutcome.ok("SonarQube token is valid.");
      }
      if (response.status() == 200) {
        return TestConnectionOutcome.failed("SonarQube reports the token as invalid.");
      }
      return TestConnectionOutcome.failed(
          "Unexpected SonarQube response: HTTP " + response.status());
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

    for (String projectKey : fetchProjectKeys(base, auth)) {
      emitProjectQualityGate(context, base, auth, instance, projectKey);
      emitPullRequestQualityGates(context, base, auth, instance, projectKey);
    }
  }

  private List<String> fetchProjectKeys(String base, String auth) {
    List<String> keys = new ArrayList<>();
    int total = Integer.MAX_VALUE;
    for (int page = 1; page <= MAX_PROJECT_PAGES && keys.size() < total; page++) {
      JsonResponse response =
          http.getJson(base + "/api/projects/search?ps=" + PROJECT_PAGE_SIZE + "&p=" + page, auth);
      if (response.status() != 200) {
        throw new IllegalStateException(
            "SonarQube projects/search failed: HTTP " + response.status());
      }
      JsonNode components = response.body().path("components");
      if (!components.isArray() || components.isEmpty()) {
        break;
      }
      for (JsonNode component : components) {
        keys.add(component.path("key").asText());
      }
      total = response.body().path("paging").path("total").asInt(keys.size());
    }
    return keys;
  }

  private void emitProjectQualityGate(
      SyncContext context, String base, String auth, String instance, String projectKey) {
    JsonResponse statusResponse =
        http.getJson(
            base + "/api/qualitygates/project_status?projectKey=" + encode(projectKey), auth);
    if (statusResponse.status() != 200) {
      throw new IllegalStateException(
          "SonarQube project_status failed: HTTP "
              + statusResponse.status()
              + " for "
              + projectKey);
    }

    @Nullable Analysis analysis = latestAnalysis(base, auth, projectKey);
    if (analysis == null) {
      return; // no analysis history yet; never invent an evaluatedAt timestamp (v0.1, documented)
    }

    String sonarStatus = statusResponse.body().path("projectStatus").path("status").asText("");
    String key = "sonar:" + projectKey;
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put("key", key);
    payload.put("status", "OK".equals(sonarStatus) ? "PASSED" : "FAILED");
    payload.put("evaluatedAt", analysis.instant());

    context
        .rawSink()
        .emit(
            RawRecord.ofFlat(
                "quality_gate",
                key,
                TYPE,
                instance,
                "sonarqube:" + (analysis.key().isBlank() ? projectKey : analysis.key()),
                Op.UPSERT,
                FetchKind.FULL,
                payload));
  }

  private void emitPullRequestQualityGates(
      SyncContext context, String base, String auth, String instance, String projectKey) {
    JsonResponse response =
        http.getJson(base + "/api/project_pull_requests/list?project=" + encode(projectKey), auth);
    if (response.status() == 404) {
      return; // older SonarQube without PR decoration support; skip gracefully (documented)
    }
    if (response.status() != 200) {
      throw new IllegalStateException(
          "SonarQube project_pull_requests/list failed: HTTP "
              + response.status()
              + " for "
              + projectKey);
    }
    for (JsonNode pr : response.body().path("pullRequests")) {
      String prKey = pr.path("key").asText("");
      String analysisDate = pr.path("analysisDate").asText("");
      if (prKey.isBlank() || analysisDate.isBlank()) {
        continue; // never invent an evaluatedAt timestamp (v0.1, documented)
      }
      String gateStatus = pr.path("status").path("qualityGateStatus").asText("");
      String key = "sonar:" + projectKey + "/pr/" + prKey;

      Map<String, String> payload = new LinkedHashMap<>();
      payload.put("key", key);
      payload.put("status", "OK".equals(gateStatus) ? "PASSED" : "FAILED");
      payload.put("evaluatedAt", SourceTimestamps.fromCompactOffset(analysisDate));
      // pullRequestKey intentionally OMITTED: Sonar's numeric PR id + project key cannot be
      // reliably mapped to the Bitbucket-style "{repoSlug}#{number}" natural key from this
      // endpoint alone (v0.1; project-level correlation lands later — DEBT).

      context
          .rawSink()
          .emit(
              RawRecord.ofFlat(
                  "quality_gate",
                  key,
                  TYPE,
                  instance,
                  "sonarqube:" + projectKey + "/pr/" + prKey,
                  Op.UPSERT,
                  FetchKind.FULL,
                  payload));
    }
  }

  private @Nullable Analysis latestAnalysis(String base, String auth, String projectKey) {
    JsonResponse response =
        http.getJson(
            base + "/api/project_analyses/search?project=" + encode(projectKey) + "&ps=1", auth);
    if (response.status() != 200) {
      throw new IllegalStateException(
          "SonarQube project_analyses/search failed: HTTP "
              + response.status()
              + " for "
              + projectKey);
    }
    JsonNode analyses = response.body().path("analyses");
    if (!analyses.isArray() || analyses.isEmpty()) {
      return null;
    }
    JsonNode first = analyses.get(0);
    String date = first.path("date").asText("");
    if (date.isBlank()) {
      return null;
    }
    return new Analysis(first.path("key").asText(""), SourceTimestamps.fromCompactOffset(date));
  }

  /** One project's most recent analysis: its native key and normalized instant. */
  private record Analysis(String key, String instant) {}

  private static String baseUrl(ConnectorConfig config) {
    String base = config.require("baseUrl");
    return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }

  private static String authorization(ConnectorConfig config) {
    String secret = config.secret();
    if (secret == null || secret.isBlank()) {
      throw new IllegalArgumentException("SonarQube requires a user token secret");
    }
    return SourceHttp.bearer(secret);
  }

  private static String instance(ConnectorConfig config) {
    return config.settings().getOrDefault("baseUrl", "sonarqube");
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
