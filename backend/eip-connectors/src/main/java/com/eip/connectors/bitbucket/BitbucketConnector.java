/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.bitbucket;

import com.eip.connectors.http.SourceHttp;
import com.eip.connectors.http.SourceHttp.JsonResponse;
import com.eip.connectors.spi.Connector;
import com.eip.connectors.spi.ConnectorConfig;
import com.eip.connectors.spi.SyncContext;
import com.eip.connectors.spi.TestConnectionOutcome;

/**
 * Bitbucket Cloud connector (M2 partial): {@code testConnection} authenticates for real against
 * {@code /2.0/user}; PR/review sync lands with M2b (DEBT-018) and {@code sync} says so honestly.
 */
public final class BitbucketConnector implements Connector {

  /** The {@code core.connector.type} discriminator. */
  public static final String TYPE = "bitbucket";

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
  public boolean simulation() {
    return false;
  }

  @Override
  public TestConnectionOutcome testConnection(ConnectorConfig config) {
    String secret = config.secret();
    if (secret == null || secret.isBlank()) {
      return TestConnectionOutcome.failed("Bitbucket requires an app password / token secret");
    }
    try {
      String base = config.require("baseUrl");
      base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
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
    throw new UnsupportedOperationException(
        "Bitbucket sync arrives with M2b (DEBT-018); connectivity test and configuration are"
            + " available now.");
  }
}
