/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.eip.ingestion.api.ManageConnectorsUseCase.ConnectorTypeView;
import com.eip.ingestion.persistence.StagingRawRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Proves the connector-type catalog stays internally consistent as sources are added (M2b Wave 2D):
 * every entry's JSON Schema parses, every real (non-simulation) type carries the optional {@code
 * webhookToken} property, and every type's raw staging table resolves through {@link
 * StagingRawRepository#rawTable(String)}.
 */
class ConnectorTypeCatalogTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void catalog_lists_seven_types_with_valid_json_schemas() {
    List<ConnectorTypeView> types = ConnectorTypeCatalog.all();
    assertThat(types)
        .extracting(ConnectorTypeView::type)
        .containsExactlyInAnyOrder(
            "simulation", "jira", "bitbucket", "sonarqube", "github", "gitlab", "jenkins");

    for (ConnectorTypeView type : types) {
      JsonNode schema = parseOrFail(type);
      assertThat(schema.path("$schema").asText())
          .isEqualTo("https://json-schema.org/draft/2020-12/schema");
      assertThat(schema.path("type").asText()).isEqualTo("object");
      assertThat(schema.path("properties").isObject()).isTrue();
    }
  }

  @Test
  void every_real_type_config_schema_carries_an_optional_webhook_token() {
    for (ConnectorTypeView type : ConnectorTypeCatalog.all()) {
      JsonNode schema = parseOrFail(type);
      if ("simulation".equals(type.type())) {
        assertThat(schema.path("properties").has("webhookToken")).isFalse();
        continue;
      }
      assertThat(schema.path("properties").has("webhookToken"))
          .as("connector type %s should expose an optional webhookToken", type.type())
          .isTrue();
      List<String> required = new java.util.ArrayList<>();
      schema.path("required").forEach(n -> required.add(n.asText()));
      assertThat(required).doesNotContain("webhookToken");
    }
  }

  @Test
  void raw_table_resolves_for_every_real_connector_including_the_second_wave() {
    assertThat(StagingRawRepository.rawTable("github")).isEqualTo("staging.raw_github");
    assertThat(StagingRawRepository.rawTable("gitlab")).isEqualTo("staging.raw_gitlab");
    assertThat(StagingRawRepository.rawTable("jenkins")).isEqualTo("staging.raw_jenkins");

    assertThat(StagingRawRepository.rawTables())
        .containsExactlyInAnyOrderElementsOf(
            Set.of(
                "staging.raw_simulation",
                "staging.raw_jira",
                "staging.raw_bitbucket",
                "staging.raw_sonarqube",
                "staging.raw_github",
                "staging.raw_gitlab",
                "staging.raw_jenkins"));
  }

  @Test
  void by_type_resolves_the_second_wave_connectors() {
    assertThat(ConnectorTypeCatalog.byType("github")).isPresent();
    assertThat(ConnectorTypeCatalog.byType("gitlab")).isPresent();
    assertThat(ConnectorTypeCatalog.byType("jenkins")).isPresent();
    assertThat(ConnectorTypeCatalog.byType("unknown-source")).isEmpty();
  }

  private JsonNode parseOrFail(ConnectorTypeView type) {
    try {
      return mapper.readTree(type.configSchema());
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new AssertionError("invalid JSON schema for connector type " + type.type(), e);
    }
  }
}
