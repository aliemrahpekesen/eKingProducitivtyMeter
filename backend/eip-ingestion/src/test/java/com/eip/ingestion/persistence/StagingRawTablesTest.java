/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pure-Java proof that {@link StagingRawRepository}'s whitelisted raw-table map resolves every
 * shipped connector type — moved out of the deleted static {@code ConnectorTypeCatalogTest}
 * (DEBT-018 M2b Wave 3E): this behavior belongs to {@link StagingRawRepository} itself, not the
 * (now descriptor-driven) connector-type catalog, so it lives here next to the class it proves. No
 * database needed — {@code rawTable}/{@code rawTables} are pure lookups over a constant map.
 */
class StagingRawTablesTest {

  @Test
  void raw_table_resolves_for_every_shipped_connector() {
    assertThat(StagingRawRepository.rawTable("simulation")).isEqualTo("staging.raw_simulation");
    assertThat(StagingRawRepository.rawTable("jira")).isEqualTo("staging.raw_jira");
    assertThat(StagingRawRepository.rawTable("bitbucket")).isEqualTo("staging.raw_bitbucket");
    assertThat(StagingRawRepository.rawTable("sonarqube")).isEqualTo("staging.raw_sonarqube");
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
  void raw_table_rejects_an_unknown_connector_type() {
    assertThatThrownBy(() -> StagingRawRepository.rawTable("unknown-source"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
