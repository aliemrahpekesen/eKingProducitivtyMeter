/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.application;

import com.eip.ingestion.api.ManageConnectorsUseCase.ConnectorTypeView;
import java.util.List;
import java.util.Optional;

/**
 * The v0.1 connector-type catalog (static until ConnectorFramework SPI descriptors land —
 * DEBT-018). JSON Schemas drive the admin panel's config forms; {@code syncAvailable} keeps the
 * panel honest about which integrations actually ingest in this release.
 */
public final class ConnectorTypeCatalog {

  private static final List<ConnectorTypeView> TYPES =
      List.of(
          new ConnectorTypeView(
              "simulation",
              "Simulation Source",
              "Deterministic built-in dataset (3 teams, work items, PRs, builds, quality gates)"
                  + " — demos and pipeline verification without external systems.",
              """
              {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
               "title":"Simulation","properties":{},"required":[]}
              """,
              null,
              true),
          new ConnectorTypeView(
              "jira",
              "Atlassian Jira",
              "Work items, sprints and workflow transitions from Jira Cloud/Data Center.",
              """
              {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
               "title":"Jira","properties":{
                 "baseUrl":{"type":"string","format":"uri","title":"Base URL",
                            "description":"e.g. https://your-org.atlassian.net"},
                 "email":{"type":"string","title":"Service account e-mail",
                          "description":"Integration (service) account for API auth only — EIP never surfaces individual activity (NFR-071)"},
                 "projectKeys":{"type":"string","title":"Project keys",
                                "description":"Comma-separated, e.g. PLAT,PAY"}},
               "required":["baseUrl","email"]}
              """,
              "API token",
              false),
          new ConnectorTypeView(
              "bitbucket",
              "Bitbucket",
              "Repositories, pull requests, reviews and commits from Bitbucket Cloud/Server.",
              """
              {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
               "title":"Bitbucket","properties":{
                 "baseUrl":{"type":"string","format":"uri","title":"Base URL"},
                 "workspace":{"type":"string","title":"Workspace / project"}},
               "required":["baseUrl","workspace"]}
              """,
              "App password / token",
              false),
          new ConnectorTypeView(
              "sonarqube",
              "SonarQube",
              "Quality gates, issues and coverage metrics from SonarQube.",
              """
              {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
               "title":"SonarQube","properties":{
                 "baseUrl":{"type":"string","format":"uri","title":"Base URL"}},
               "required":["baseUrl"]}
              """,
              "User token",
              false));

  private ConnectorTypeCatalog() {}

  /**
   * Returns the full catalog.
   *
   * @return all connector types
   */
  public static List<ConnectorTypeView> all() {
    return TYPES;
  }

  /**
   * Looks a type up by its discriminator.
   *
   * @param type the {@code core.connector.type} value
   * @return the catalog entry, if known
   */
  public static Optional<ConnectorTypeView> byType(String type) {
    return TYPES.stream().filter(t -> t.type().equals(type)).findFirst();
  }
}
