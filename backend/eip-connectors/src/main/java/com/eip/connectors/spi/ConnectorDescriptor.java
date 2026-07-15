/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.spi;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A connector's self-described admin-panel metadata (ConnectorFramework §3; DEBT-018). Every
 * installed {@link Connector} answers {@link Connector#descriptor()} with one of these, so the
 * admin connector-type catalog is assembled by asking the {@code ConnectorRegistry} for its
 * installed connectors rather than consulting a hand-maintained static list — a newly installed
 * connector type appears in the catalog automatically, with no separate registry edit.
 *
 * @param type the {@code core.connector.type} discriminator; must match {@link Connector#type()}
 * @param displayName human-facing name shown in the admin panel
 * @param description what this integration ingests, shown under the display name
 * @param configSchema the config form's JSON Schema (draft 2020-12) as text
 * @param secretLabel label for the secret input, or {@code null} when the type needs no secret
 */
public record ConnectorDescriptor(
    String type,
    String displayName,
    String description,
    String configSchema,
    @Nullable String secretLabel) {

  public ConnectorDescriptor {
    requireText(type, "type");
    requireText(displayName, "displayName");
    requireText(description, "description");
    requireText(configSchema, "configSchema");
  }

  private static String requireText(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
