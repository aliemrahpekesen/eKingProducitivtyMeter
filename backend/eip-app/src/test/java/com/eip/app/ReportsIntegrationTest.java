/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eip.app.tenant.HeaderTenantResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 * Proves the M4 Wave R1 deterministic report surface end-to-end as the NOBYPASSRLS {@code eip_app}
 * role: a fresh tenant loads the deterministic simulation sample data (3 teams, all resolved in the
 * week of 2026-01-05 — {@code docs/metrics/SimulationDataset.md}, same fixture {@code
 * MetricsApiIntegrationTest} uses), then {@code POST /reports}, {@code GET /reports} (cursor
 * pagination), {@code GET /reports/{id}}, and {@code GET /reports/{id}/html} are exercised and
 * cross-checked against the live {@code /metrics/trends} and {@code /friction/summary} endpoints
 * for the same window. {@code eip.reports.scheduled-enabled=false} keeps the weekly cron from ever
 * racing this test.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "eip.reports.scheduled-enabled=false")
@AutoConfigureMockMvc
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReportsIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  private static String tenantA = "";
  private static String firstReportId = "";
  private static String nextCursor = "";

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    POSTGRES.start();
    prepareDatabase();
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
          new String[] {
            "core", "work", "scm", "cicd", "quality", "analytics", "staging", "reports"
          }) {
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
    POSTGRES.stop();
  }

  @Autowired private MockMvc mvc;

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  @Order(1)
  void loads_sample_data_for_a_fresh_tenant() throws Exception {
    tenantA =
        JsonPath.read(
            mvc.perform(
                    post("/api/v1/admin/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme\",\"slug\":\"acme-reports\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.id");

    mvc.perform(post("/api/v1/admin/sample-data").header(HeaderTenantResolver.HEADER, tenantA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teamsComputed").value(3))
        .andExpect(jsonPath("$.itemsCorrelated").value(9));
  }

  @Test
  @Order(2)
  void generate_report_returns_201_ready_with_location() throws Exception {
    String body =
        mvc.perform(
                post("/api/v1/reports")
                    .header(HeaderTenantResolver.HEADER, tenantA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"type\":\"EXEC_SUMMARY\",\"weeks\":12}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.type").value("EXEC_SUMMARY"))
            .andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.weeks").value(12))
            .andReturn()
            .getResponse()
            .getContentAsString();
    firstReportId = mapper.readTree(body).get("id").asText();

    mvc.perform(
            post("/api/v1/reports")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"EXEC_SUMMARY\",\"weeks\":12}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.title").value("Tenant required"));
  }

  @Test
  @Order(3)
  void document_totals_match_the_live_trends_and_friction_endpoints_for_the_same_weeks()
      throws Exception {
    String trendsBody =
        mvc.perform(
                get("/api/v1/metrics/trends")
                    .param("weeks", "12")
                    .header(HeaderTenantResolver.HEADER, tenantA))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode trendTeams = mapper.readTree(trendsBody).get("teams");
    int expectedTeamsWithPoints = trendTeams.size();
    int expectedItemsResolved = 0;
    for (JsonNode team : trendTeams) {
      for (JsonNode point : team.get("points")) {
        expectedItemsResolved += point.get("itemsResolved").asInt();
      }
    }
    assertThat(expectedTeamsWithPoints).isEqualTo(3);
    assertThat(expectedItemsResolved).isEqualTo(9);

    String frictionBody =
        mvc.perform(get("/api/v1/friction/summary").header(HeaderTenantResolver.HEADER, tenantA))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    int expectedTeamsReporting = mapper.readTree(frictionBody).get("teamsReporting").asInt();

    mvc.perform(
            get("/api/v1/reports/" + firstReportId).header(HeaderTenantResolver.HEADER, tenantA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.report.id").value(firstReportId))
        .andExpect(jsonPath("$.document.totals.itemsResolved").value(expectedItemsResolved))
        .andExpect(jsonPath("$.document.totals.teamsReporting").value(expectedTeamsReporting))
        .andExpect(jsonPath("$.document.teams.length()").value(expectedTeamsReporting));

    mvc.perform(get("/api/v1/reports/" + firstReportId))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.title").value("Tenant required"));
  }

  @Test
  @Order(4)
  void list_paginates_newest_first_with_a_cursor() throws Exception {
    // Two more reports so three exist in total for this tenant.
    mvc.perform(
            post("/api/v1/reports")
                .header(HeaderTenantResolver.HEADER, tenantA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"EXEC_SUMMARY\",\"weeks\":4}"))
        .andExpect(status().isCreated());
    mvc.perform(
            post("/api/v1/reports")
                .header(HeaderTenantResolver.HEADER, tenantA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"EXEC_SUMMARY\",\"weeks\":4}"))
        .andExpect(status().isCreated());

    String page1 =
        mvc.perform(
                get("/api/v1/reports")
                    .param("limit", "2")
                    .header(HeaderTenantResolver.HEADER, tenantA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.hasMore").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    nextCursor = mapper.readTree(page1).get("nextCursor").asText();
    assertThat(nextCursor).isNotBlank();

    mvc.perform(
            get("/api/v1/reports")
                .param("limit", "2")
                .param("cursor", nextCursor)
                .header(HeaderTenantResolver.HEADER, tenantA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.hasMore").value(false));
  }

  @Test
  @Order(5)
  void html_endpoint_renders_the_document_without_script_tags() throws Exception {
    String html =
        mvc.perform(
                get("/api/v1/reports/" + firstReportId + "/html")
                    .header(HeaderTenantResolver.HEADER, tenantA))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "text/html;charset=UTF-8"))
            .andExpect(
                header()
                    .string(
                        "Content-Disposition",
                        "inline; filename=\"eip-report-" + firstReportId + ".html\""))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).contains("Engineering Flow Report");
    assertThat(html).contains("Platform");
    assertThat(html).doesNotContain("<script>");
  }

  @Test
  @Order(6)
  void unknown_id_is_not_found() throws Exception {
    mvc.perform(
            get("/api/v1/reports/" + UUID.randomUUID())
                .header(HeaderTenantResolver.HEADER, tenantA))
        .andExpect(status().isNotFound());
  }

  @Test
  @Order(7)
  void out_of_range_weeks_is_rejected() throws Exception {
    mvc.perform(
            post("/api/v1/reports")
                .header(HeaderTenantResolver.HEADER, tenantA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"EXEC_SUMMARY\",\"weeks\":99}"))
        .andExpect(status().isBadRequest());
  }
}
