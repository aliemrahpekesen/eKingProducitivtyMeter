/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.application;

import com.eip.connectors.bitbucket.BitbucketConnector;
import com.eip.connectors.jira.JiraConnector;
import com.eip.connectors.simulation.SimulationConnector;
import com.eip.connectors.sonarqube.SonarQubeConnector;
import com.eip.connectors.spi.Connector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the installed connector implementations. The connectors themselves stay
 * framework-independent pure Java — only their wiring is Spring. The simulation source is toggled
 * by {@code eip.simulation.enabled} (default on); the real connectors are always installed: Jira,
 * Bitbucket and SonarQube all probe and sync for real (M2/M2b). Remaining DEBT-018 scope: a
 * descriptor-driven catalog and additional connector types.
 */
@Configuration(proxyBeanMethods = false)
public class SimulationSourceConfiguration {

  /**
   * The deterministic simulation source connector.
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

  /**
   * The real Jira connector (M2: authenticated probe + paginated full sync).
   *
   * @return the connector
   */
  @Bean
  public Connector jiraConnector() {
    return new JiraConnector();
  }

  /**
   * The real Bitbucket connector (M2b: authenticated probe + paginated full sync).
   *
   * @return the connector
   */
  @Bean
  public Connector bitbucketConnector() {
    return new BitbucketConnector();
  }

  /**
   * The real SonarQube connector (M2b: authenticated probe + paginated full sync).
   *
   * @return the connector
   */
  @Bean
  public Connector sonarqubeConnector() {
    return new SonarQubeConnector();
  }
}
