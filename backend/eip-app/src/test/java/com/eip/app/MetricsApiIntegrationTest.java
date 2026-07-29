/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
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
 * Proves the M3 metrics + insights read surface end-to-end as the NOBYPASSRLS {@code eip_app} role:
 * a fresh tenant loads the deterministic simulation sample data (3 teams, Platform/Payments/Web,
 * all resolved in the week of 2026-01-05 — {@code docs/metrics/SimulationDataset.md}), then {@code
 * /metrics/trends}, {@code /insights/recommendations}, and {@code /metrics/in-flight} are asserted
 * against the documented golden numbers ({@code docs/metrics/EngineeringFriction.md}: Platform
 * frictionScore=91, reviewWaitPct=round(100*56/99)=57). A missing tenant header fails closed with
 * RFC 7807 on every endpoint.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MetricsApiIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  private static String tenantA = "";

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
                        .content("{\"name\":\"Acme\",\"slug\":\"acme-metrics\"}"))
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
  void trends_returns_three_teams_with_platforms_single_resolved_week() throws Exception {
    String body =
        mvc.perform(
                get("/api/v1/metrics/trends")
                    .param("weeks", "12")
                    .header(HeaderTenantResolver.HEADER, tenantA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rangeWeeks").value(12))
            .andExpect(jsonPath("$.teams.length()").value(3))
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode platform =
        Objects.requireNonNull(
            teamNamed(mapper.readTree(body).get("teams"), "Platform"), "Platform team missing");
    JsonNode points = platform.get("points");
    assertThat(points).hasSize(1);
    JsonNode point = points.get(0);
    assertThat(point.get("weekStart").asText()).isEqualTo("2026-01-05");
    assertThat(point.get("itemsResolved").asInt()).isEqualTo(3);
    assertThat(point.get("frictionScore").asInt()).isEqualTo(91);

    // Missing tenant header fails closed with RFC 7807 on every M3 endpoint.
    mvc.perform(get("/api/v1/metrics/trends"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.title").value("Tenant required"));
  }

  @Test
  @Order(3)
  void in_flight_is_empty_because_the_sample_dataset_is_fully_resolved() throws Exception {
    mvc.perform(get("/api/v1/metrics/in-flight").header(HeaderTenantResolver.HEADER, tenantA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));

    mvc.perform(get("/api/v1/metrics/in-flight"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.title").value("Tenant required"));
  }

  @Test
  @Order(4)
  void recommendations_fire_critical_review_wait_for_platform() throws Exception {
    String body =
        mvc.perform(
                get("/api/v1/insights/recommendations")
                    .header(HeaderTenantResolver.HEADER, tenantA))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode teams = mapper.readTree(body);
    assertThat(teams).hasSize(3);
    JsonNode platform =
        Objects.requireNonNull(teamNamed(teams, "Platform"), "Platform team missing");

    JsonNode reviewWait =
        Objects.requireNonNull(
            recommendationCoded(platform.get("recommendations"), "R-REVIEW-WAIT"),
            "R-REVIEW-WAIT missing for Platform");
    assertThat(reviewWait.get("severity").asText()).isEqualTo("CRITICAL");
    List<String> metricRefs = new ArrayList<>();
    reviewWait.get("metricRefs").forEach(n -> metricRefs.add(n.asText()));
    assertThat(metricRefs).contains("reviewWaitPct=57");

    mvc.perform(get("/api/v1/insights/recommendations"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.title").value("Tenant required"));
  }

  private static @Nullable JsonNode teamNamed(JsonNode teams, String name) {
    for (JsonNode team : teams) {
      if (name.equals(team.get("teamName").asText())) {
        return team;
      }
    }
    return null;
  }

  private static @Nullable JsonNode recommendationCoded(JsonNode recommendations, String code) {
    for (JsonNode rec : recommendations) {
      if (code.equals(rec.get("code").asText())) {
        return rec;
      }
    }
    return null;
  }
}
