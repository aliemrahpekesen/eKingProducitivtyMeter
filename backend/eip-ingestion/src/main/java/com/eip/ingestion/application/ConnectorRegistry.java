/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.application;

import com.eip.connectors.spi.Connector;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** The installed connector implementations, keyed by type (fed by Spring's List injection). */
@Component
public class ConnectorRegistry {

  private final Map<String, Connector> byType;

  public ConnectorRegistry(List<Connector> connectors) {
    this.byType =
        connectors.stream()
            .collect(Collectors.toUnmodifiableMap(Connector::type, Function.identity()));
  }

  /**
   * Looks up the implementation for a connector type.
   *
   * @param type the {@code core.connector.type} discriminator
   * @return the connector, if installed
   */
  public Optional<Connector> byType(String type) {
    return Optional.ofNullable(byType.get(type));
  }

  /**
   * Returns every installed connector implementation (unordered) — the descriptor-driven admin
   * catalog's source of truth (DEBT-018): a type appears in the catalog if and only if it is
   * installed here.
   *
   * @return the installed connectors
   */
  public List<Connector> all() {
    return List.copyOf(byType.values());
  }
}
