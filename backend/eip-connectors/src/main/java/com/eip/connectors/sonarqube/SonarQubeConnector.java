/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.sonarqube;

import com.eip.connectors.http.SourceHttp;
import com.eip.connectors.http.SourceHttp.JsonResponse;
import com.eip.connectors.spi.Connector;
import com.eip.connectors.spi.ConnectorConfig;
import com.eip.connectors.spi.SyncContext;
import com.eip.connectors.spi.TestConnectionOutcome;

/**
 * SonarQube connector (M2 partial): {@code testConnection} validates the token for real against
 * {@code /api/authentication/validate}; quality-gate sync lands with M2b (DEBT-018).
 */
public final class SonarQubeConnector implements Connector {

  /** The {@code core.connector.type} discriminator. */
  public static final String TYPE = "sonarqube";

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
  public boolean simulation() {
    return false;
  }

  @Override
  public TestConnectionOutcome testConnection(ConnectorConfig config) {
    String secret = config.secret();
    if (secret == null || secret.isBlank()) {
      return TestConnectionOutcome.failed("SonarQube requires a user token secret");
    }
    try {
      String base = config.require("baseUrl");
      base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
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
    throw new UnsupportedOperationException(
        "SonarQube sync arrives with M2b (DEBT-018); connectivity test and configuration are"
            + " available now.");
  }
}
