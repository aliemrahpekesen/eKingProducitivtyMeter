/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eip.app.tenant.HeaderTenantResolver;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The M2 user journey, end to end, against a WireMock'd Jira and real PostgreSQL (NOBYPASSRLS
 * role): onboard a tenant → register the Jira connector from the admin surface (token
 * envelope-encrypted) → REAL authenticated Test Connection → "Sync now" pulls issues + changelog
 * through the same staging→normalization→correlation→friction pipeline (teams auto-created from the
 * project key) → the dashboard shows friction computed from JIRA data. Replays stay idempotent;
 * unavailable sync types fail honestly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Tag("integration")
class AdminJiraSyncIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  private static final WireMockServer JIRA =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    POSTGRES.start();
    prepareDatabase();
    JIRA.start();
    stubJira();
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "eip_app");
    registry.add("spring.datasource.password", () -> "eip_app_pw");
    registry.add("spring.flyway.enabled", () -> "false");
  }

  private static void prepareDatabase() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute("CREATE ROLE eip_app LOGIN PASSWORD 'eip_app_pw' NOBYPASSRLS");
      for (String schema :
          new String[] {"core", "work", "scm", "cicd", "quality", "analytics", "staging"}) {
        st.execute("GRANT USAGE ON SCHEMA " + schema + " TO eip_app");
        st.execute(
            "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA "
                + schema
                + " TO eip_app");
      }
    } catch (SQLException e) {
      throw new IllegalStateException("failed to prepare test database", e);
    }
  }

  @AfterAll
  static void stop() {
    JIRA.stop();
    POSTGRES.stop();
  }

  @Autowired private MockMvc mvc;

  @Test
  void jira_data_flows_from_admin_registration_to_computed_friction() throws Exception {
    // 1. Onboard a tenant through the platform API.
    String tenant =
        JsonPath.read(
            mvc.perform(
                    post("/api/v1/admin/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Jira Corp\",\"slug\":\"jira-corp\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.id");

    // 2. Register Jira with the WireMock base URL + token (envelope-encrypted at rest).
    String connectorId =
        JsonPath.read(
            mvc.perform(
                    post("/api/v1/admin/connectors")
                        .header(HeaderTenantResolver.HEADER, tenant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"type\":\"jira\",\"name\":\"Corp Jira\",\"config\":{\"baseUrl\":\""
                                + JIRA.baseUrl()
                                + "\",\"email\":\"svc@corp.io\",\"projectKeys\":\"PLAT\"},"
                                + "\"secret\":\"corp-token\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hasSecret").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.id");

    // 3. REAL authenticated Test Connection (hits WireMock's /myself).
    mvc.perform(
            post("/api/v1/admin/connectors/" + connectorId + "/test")
                .header(HeaderTenantResolver.HEADER, tenant))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("OK"));

    // 4. Sync now: issues + changelog staged, teams auto-created from the project key, friction
    // computed — 10 raw records (2 issues + 8 transitions), 1 resolved item correlates.
    mvc.perform(
            post("/api/v1/admin/connectors/" + connectorId + "/sync")
                .header(HeaderTenantResolver.HEADER, tenant))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ingestion.emitted").value(10))
        .andExpect(jsonPath("$.ingestion.inserted").value(10))
        .andExpect(jsonPath("$.teamsComputed").value(1))
        .andExpect(jsonPath("$.itemsCorrelated").value(1));

    // 5. The dashboard now shows friction computed from JIRA data: PLAT-1 = 8h cycle, 2h blocked,
    // 3h review → round(100·5/8) = 63, dominant REVIEW_WAIT.
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/friction/summary")
                .header(HeaderTenantResolver.HEADER, tenant))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teams[0].teamName").value("PLAT"))
        .andExpect(jsonPath("$.teams[0].frictionScore").value(63))
        .andExpect(jsonPath("$.teams[0].dominantCause").value("REVIEW_WAIT"))
        .andExpect(jsonPath("$.teams[0].workItems").value(1));

    // 6. Replay is idempotent: everything unchanged, score identical.
    mvc.perform(
            post("/api/v1/admin/connectors/" + connectorId + "/sync")
                .header(HeaderTenantResolver.HEADER, tenant))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ingestion.unchanged").value(10))
        .andExpect(jsonPath("$.ingestion.inserted").value(0));

    // 7. Types without a shipped sync fail honestly (Bitbucket arrives with M2b).
    String bitbucketId =
        JsonPath.read(
            mvc.perform(
                    post("/api/v1/admin/connectors")
                        .header(HeaderTenantResolver.HEADER, tenant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"type\":\"bitbucket\",\"name\":\"BB\",\"config\":{\"baseUrl\":"
                                + "\"https://bb\",\"workspace\":\"corp\",\"username\":\"svc\"},"
                                + "\"secret\":\"pw\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.id");
    mvc.perform(
            post("/api/v1/admin/connectors/" + bitbucketId + "/sync")
                .header(HeaderTenantResolver.HEADER, tenant))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail", org.hamcrest.Matchers.containsString("DEBT-018")));
  }

  private static void stubJira() {
    JIRA.stubFor(
        get(urlPathEqualTo("/rest/api/3/myself"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"accountType\":\"atlassian\"}")));
    JIRA.stubFor(
        get(urlPathEqualTo("/rest/api/3/search"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(SEARCH_BODY)));
  }

  private static final String SEARCH_BODY =
      """
      {"startAt":0,"maxResults":100,"total":2,"issues":[
        {"id":"10001","key":"PLAT-1",
         "fields":{"summary":"Checkout refactor","project":{"key":"PLAT"},
                   "issuetype":{"name":"Story"},
                   "status":{"name":"Done","statusCategory":{"key":"done"}},
                   "created":"2026-01-05T09:00:00.000+0000",
                   "resolutiondate":"2026-01-05T17:00:00.000+0000"},
         "changelog":{"histories":[
           {"id":"h1","created":"2026-01-05T10:00:00.000+0000","items":[
             {"field":"status","fromString":"To Do","toString":"In Progress"}]},
           {"id":"h2","created":"2026-01-05T11:00:00.000+0000","items":[
             {"field":"status","fromString":"In Progress","toString":"Blocked"}]},
           {"id":"h3","created":"2026-01-05T13:00:00.000+0000","items":[
             {"field":"status","fromString":"Blocked","toString":"In Progress"}]},
           {"id":"h4","created":"2026-01-05T14:00:00.000+0000","items":[
             {"field":"status","fromString":"In Progress","toString":"In Review"}]},
           {"id":"h5","created":"2026-01-05T17:00:00.000+0000","items":[
             {"field":"status","fromString":"In Review","toString":"Done"}]}]}},
        {"id":"10002","key":"PLAT-2",
         "fields":{"summary":"Latency fix","project":{"key":"PLAT"},
                   "issuetype":{"name":"Bug"},
                   "status":{"name":"In Progress","statusCategory":{"key":"indeterminate"}},
                   "created":"2026-01-05T09:00:00.000+0000","resolutiondate":null},
         "changelog":{"histories":[
           {"id":"h6","created":"2026-01-05T10:00:00.000+0000","items":[
             {"field":"status","fromString":"To Do","toString":"In Progress"}]},
           {"id":"h7","created":"2026-01-05T11:00:00.000+0000","items":[
             {"field":"status","fromString":"In Progress","toString":"In Review"}]},
           {"id":"h8","created":"2026-01-05T12:00:00.000+0000","items":[
             {"field":"status","fromString":"In Review","toString":"Done"}]}]}}
      ]}
      """;
}
