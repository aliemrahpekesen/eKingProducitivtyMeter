/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.application;

import com.eip.connectors.simulation.SimulationConnector;
import com.eip.connectors.spi.Connector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the deterministic simulation connector as the active source (v0.1: the only source).
 * Toggled by {@code eip.simulation.enabled} (default on) so an operator can boot without any
 * connector; the connector itself stays framework-independent pure Java — only its wiring is
 * Spring.
 */
@Configuration(proxyBeanMethods = false)
public class SimulationSourceConfiguration {

  /**
   * The simulation source connector.
   *
   * @return the connector
   */
  @Bean
  @ConditionalOnProperty(
      name = "eip.simulation.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public Connector simulationConnector() {
    return new SimulationConnector();
  }
}
