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
import java.util.Map;
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

  @Test
  void validate_fails_when_a_required_setting_is_missing() {
    for (Connector connector : allConnectors()) {
      List<String> required = requiredFields(connector);
      if (required.isEmpty()) {
        continue; // simulation: nothing required
      }
      ValidationResult result = connector.validate(new ConnectorConfig(Map.of(), null));
      assertThat(result.valid()).as("connector type %s", connector.type()).isFalse();
      assertThat(result.errors())
          .as("connector type %s", connector.type())
          .anySatisfy(error -> assertThat(error).contains(required.get(0)));
    }
  }

  @Test
  void validate_fails_when_a_required_setting_is_blank() {
    for (Connector connector : allConnectors()) {
      List<String> required = requiredFields(connector);
      if (required.isEmpty()) {
        continue;
      }
      java.util.Map<String, String> settings =
          new java.util.LinkedHashMap<>(realisticSettings(connector));
      settings.put(required.get(0), "   "); // blank, not merely absent
      ValidationResult result =
          connector.validate(new ConnectorConfig(settings, realisticSecret(connector)));
      assertThat(result.valid()).as("connector type %s", connector.type()).isFalse();
    }
  }

  @Test
  void validate_fails_when_a_required_secret_is_missing() {
    for (Connector connector : allConnectors()) {
      if (connector.descriptor().secretLabel() == null) {
        continue; // this type needs no secret
      }
      ValidationResult result =
          connector.validate(new ConnectorConfig(realisticSettings(connector), null));
      assertThat(result.valid()).as("connector type %s", connector.type()).isFalse();
      assertThat(result.errors())
          .as("connector type %s", connector.type())
          .anySatisfy(error -> assertThat(error).contains(connector.descriptor().secretLabel()));
    }
  }

  @Test
  void validate_passes_for_a_realistic_complete_config_per_connector() {
    // Regression net (DEBT-018 item 1): every real connector's OWN descriptor validates a
    // realistic complete config as ok() — proves the generic default reader stays in sync with
    // each connector's actual required-field set as schemas evolve.
    for (Connector connector : allConnectors()) {
      ValidationResult result =
          connector.validate(
              new ConnectorConfig(realisticSettings(connector), realisticSecret(connector)));
      assertThat(result.valid())
          .as("connector type %s: %s", connector.type(), result.errors())
          .isTrue();
      assertThat(result.errors()).isEmpty();
    }
  }

  /** Returns the descriptor's {@code required} field names, in schema-declared order. */
  private List<String> requiredFields(Connector connector) {
    List<String> required = new java.util.ArrayList<>();
    parseOrFail(connector).path("required").forEach(n -> required.add(n.asText()));
    return required;
  }

  /** A realistic, complete non-secret settings map for one connector type. */
  private Map<String, String> realisticSettings(Connector connector) {
    return switch (connector.type()) {
      case "simulation" -> Map.of();
      case "jira" -> Map.of("baseUrl", "https://acme.atlassian.net", "email", "svc@acme.io");
      case "bitbucket" ->
          Map.of(
              "baseUrl", "https://api.bitbucket.org",
              "username", "svc-acme",
              "workspace", "acme");
      case "sonarqube" -> Map.of("baseUrl", "https://sonar.acme.internal");
      case "github" -> Map.of("org", "acme-corp");
      case "gitlab" -> Map.of("baseUrl", "https://gitlab.acme.internal", "group", "acme");
      case "jenkins" -> Map.of("baseUrl", "https://ci.acme.internal", "username", "svc-acme");
      default ->
          throw new AssertionError("no realistic fixture for connector type " + connector.type());
    };
  }

  /** A realistic secret value for one connector type, or {@code null} when none is required. */
  private @org.jspecify.annotations.Nullable String realisticSecret(Connector connector) {
    return connector.descriptor().secretLabel() == null ? null : "s3cr3t-token";
  }

  private JsonNode parseOrFail(Connector connector) {
    try {
      return mapper.readTree(connector.descriptor().configSchema());
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new AssertionError("invalid JSON schema for connector type " + connector.type(), e);
    }
  }
}
