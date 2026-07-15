/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.spi;

import static org.assertj.core.api.Assertions.assertThat;

import com.eip.connectors.bitbucket.BitbucketConnector;
import com.eip.connectors.github.GitHubConnector;
import com.eip.connectors.gitlab.GitLabConnector;
import com.eip.connectors.jenkins.JenkinsConnector;
import com.eip.connectors.jira.JiraConnector;
import com.eip.connectors.simulation.SimulationConnector;
import com.eip.connectors.sonarqube.SonarQubeConnector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Module-level proof that every shipped connector's {@link Connector#descriptor()} is internally
 * consistent (DEBT-018, M2b Wave 3E) — this replaces the deleted static {@code
 * ConnectorTypeCatalogTest} now that the seven connectors carry their own admin-panel metadata
 * instead of a hand-maintained list: {@link ConnectorDescriptor#type()} always matches {@link
 * Connector#type()}, every {@link ConnectorDescriptor#configSchema()} parses as JSON Schema
 * draft-2020-12, and every real (non-simulation) type's schema exposes the optional {@code
 * webhookToken} property — never required — while simulation carries none.
 */
class ConnectorDescriptorsTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private static List<Connector> allConnectors() {
    return List.of(
        new SimulationConnector(),
        new JiraConnector(),
        new BitbucketConnector(),
        new SonarQubeConnector(),
        new GitHubConnector(),
        new GitLabConnector(),
        new JenkinsConnector());
  }

  @Test
  void seven_connectors_are_shipped() {
    assertThat(allConnectors()).hasSize(7);
  }

  @Test
  void every_descriptor_type_matches_the_connector_type() {
    for (Connector connector : allConnectors()) {
      assertThat(connector.descriptor().type()).isEqualTo(connector.type());
    }
  }

  @Test
  void every_descriptor_config_schema_parses_as_a_2020_12_object_schema() {
    for (Connector connector : allConnectors()) {
      JsonNode schema = parseOrFail(connector);
      assertThat(schema.path("$schema").asText())
          .isEqualTo("https://json-schema.org/draft/2020-12/schema");
      assertThat(schema.path("type").asText()).isEqualTo("object");
      assertThat(schema.path("properties").isObject()).isTrue();
    }
  }

  @Test
  void every_real_type_config_schema_carries_an_optional_webhook_token() {
    for (Connector connector : allConnectors()) {
      JsonNode schema = parseOrFail(connector);
      if (connector.simulation()) {
        assertThat(schema.path("properties").has("webhookToken"))
            .as("simulation carries no webhookToken property")
            .isFalse();
        continue;
      }
      assertThat(schema.path("properties").has("webhookToken"))
          .as("connector type %s should expose an optional webhookToken", connector.type())
          .isTrue();
      List<String> required = new java.util.ArrayList<>();
      schema.path("required").forEach(n -> required.add(n.asText()));
      assertThat(required).doesNotContain("webhookToken");
    }
  }

  private JsonNode parseOrFail(Connector connector) {
    try {
      return mapper.readTree(connector.descriptor().configSchema());
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new AssertionError("invalid JSON schema for connector type " + connector.type(), e);
    }
  }
}
