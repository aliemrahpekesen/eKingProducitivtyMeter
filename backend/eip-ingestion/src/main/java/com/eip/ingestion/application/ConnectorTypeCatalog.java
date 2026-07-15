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
                                "description":"Comma-separated, e.g. PLAT,PAY"},
                 "webhookToken":{"type":"string","title":"Webhook token (optional)",
                                 "description":"Optional shared token that authorizes webhook-triggered syncs (X-EIP-Webhook-Token header)"}},
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
                 "baseUrl":{"type":"string","format":"uri","title":"Base URL",
                            "description":"e.g. https://api.bitbucket.org"},
                 "username":{"type":"string","title":"Service account username"},
                 "workspace":{"type":"string","title":"Workspace / project"},
                 "webhookToken":{"type":"string","title":"Webhook token (optional)",
                                 "description":"Optional shared token that authorizes webhook-triggered syncs (X-EIP-Webhook-Token header)"}},
               "required":["baseUrl","username","workspace"]}
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
                 "baseUrl":{"type":"string","format":"uri","title":"Base URL"},
                 "webhookToken":{"type":"string","title":"Webhook token (optional)",
                                 "description":"Optional shared token that authorizes webhook-triggered syncs (X-EIP-Webhook-Token header)"}},
               "required":["baseUrl"]}
              """,
              "User token",
              false),
          new ConnectorTypeView(
              "github",
              "GitHub",
              "Repositories, pull requests, reviews, issues and Actions builds from GitHub.",
              """
              {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
               "title":"GitHub","properties":{
                 "baseUrl":{"type":"string","format":"uri","title":"Base URL",
                            "description":"e.g. https://api.github.com (GitHub Enterprise Server: your own API host)"},
                 "org":{"type":"string","title":"Organization"},
                 "webhookToken":{"type":"string","title":"Webhook token (optional)",
                                 "description":"Optional shared token that authorizes webhook-triggered syncs (X-EIP-Webhook-Token header)"}},
               "required":["org"]}
              """,
              "Personal access token",
              false),
          new ConnectorTypeView(
              "gitlab",
              "GitLab",
              "Group projects, merge requests and CI pipelines from GitLab.",
              """
              {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
               "title":"GitLab","properties":{
                 "baseUrl":{"type":"string","format":"uri","title":"Base URL",
                            "description":"e.g. https://gitlab.example.com"},
                 "group":{"type":"string","title":"Group (or subgroup) path"},
                 "webhookToken":{"type":"string","title":"Webhook token (optional)",
                                 "description":"Optional shared token that authorizes webhook-triggered syncs (X-EIP-Webhook-Token header)"}},
               "required":["baseUrl","group"]}
              """,
              "Personal access token",
              false),
          new ConnectorTypeView(
              "jenkins",
              "Jenkins",
              "Jobs and build results from a Jenkins controller.",
              """
              {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
               "title":"Jenkins","properties":{
                 "baseUrl":{"type":"string","format":"uri","title":"Base URL",
                            "description":"e.g. https://ci.example.com"},
                 "username":{"type":"string","title":"Service account username"},
                 "webhookToken":{"type":"string","title":"Webhook token (optional)",
                                 "description":"Optional shared token that authorizes webhook-triggered syncs (X-EIP-Webhook-Token header)"}},
               "required":["baseUrl","username"]}
              """,
              "API token",
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
