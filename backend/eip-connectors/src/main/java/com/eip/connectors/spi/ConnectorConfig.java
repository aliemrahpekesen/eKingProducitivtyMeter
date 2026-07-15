/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.spi;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The resolved runtime configuration handed to a connector: the operator's non-secret settings plus
 * the revealed secret (in-process only — never logged, never serialized).
 *
 * @param settings non-secret configuration (validated against the type's schema upstream)
 * @param secret the revealed API token/secret, or null for types without one
 */
public record ConnectorConfig(Map<String, String> settings, @Nullable String secret) {

  public ConnectorConfig {
    settings = Map.copyOf(settings);
  }

  /**
   * Returns a required setting.
   *
   * @param key the setting key
   * @return the value
   */
  public String require(String key) {
    String value = settings.get(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("missing required connector setting: " + key);
    }
    return value;
  }
}
