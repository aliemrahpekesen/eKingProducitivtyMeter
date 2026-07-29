/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.eip.connectors.bitbucket.BitbucketConnector;
import com.eip.connectors.github.GitHubConnector;
import com.eip.connectors.gitlab.GitLabConnector;
import com.eip.connectors.jenkins.JenkinsConnector;
import com.eip.connectors.jira.JiraConnector;
import com.eip.connectors.simulation.SimulationConnector;
import com.eip.connectors.sonarqube.SonarQubeConnector;
import com.eip.connectors.spi.Connector;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure-Java proof that {@link ConnectorRegistry} resolves every installed connector by type (this
 * behavior used to be exercised indirectly through the deleted static {@code
 * ConnectorTypeCatalog.byType} — DEBT-018 M2b Wave 3E folded the assertion here, next to the class
 * it actually describes) — no Spring context or database needed.
 */
class ConnectorRegistryTest {

  private final ConnectorRegistry registry =
      new ConnectorRegistry(
          List.of(
              new SimulationConnector(),
              new JiraConnector(),
              new BitbucketConnector(),
              new SonarQubeConnector(),
              new GitHubConnector(),
              new GitLabConnector(),
              new JenkinsConnector()));

  @Test
  void resolves_every_installed_connector_by_type() {
    assertThat(registry.byType("simulation")).isPresent();
    assertThat(registry.byType("jira")).isPresent();
    assertThat(registry.byType("bitbucket")).isPresent();
    assertThat(registry.byType("sonarqube")).isPresent();
    assertThat(registry.byType("github")).isPresent();
    assertThat(registry.byType("gitlab")).isPresent();
    assertThat(registry.byType("jenkins")).isPresent();
    assertThat(registry.byType("unknown-source")).isEmpty();
  }

  @Test
  void all_returns_every_installed_connector() {
    assertThat(registry.all())
        .extracting(Connector::type)
        .containsExactlyInAnyOrder(
            "simulation", "jira", "bitbucket", "sonarqube", "github", "gitlab", "jenkins");
  }
}
