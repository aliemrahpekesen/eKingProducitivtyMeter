/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eip.ai.api.ExplanationView;
import com.eip.app.tenant.HeaderTenantResolver;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
 * Proves the M6-A AI explanation layer end-to-end (ADR-024) as the header-mode implicit {@code
 * TENANT_ADMIN} principal (RBAC denial for a lesser role belongs in {@code
 * OidcRbacIntegrationTest}, extended alongside this test — header mode always resolves
 * TENANT_ADMIN, so it cannot exercise a 403): policy CRUD (default-disabled read, validation,
 * envelope-encrypted secret storage never read back), explain refused with 409 while disabled, a
 * WireMock'd openai-compatible provider returning a narrative that cites only real numbers (200 +
 * an {@code ai.llm_call_audit} row), and one citing an invented number (502 {@code
 * /problems/ai-rejected}).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "eip.reports.scheduled-enabled=false")
@AutoConfigureMockMvc
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiLayerIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  private static final WireMockServer LLM =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());

  private static String tenant = "";

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    POSTGRES.start();
    prepareDatabase();
    LLM.start();
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
          new String[] {"core", "work", "scm", "cicd", "quality", "analytics", "staging", "ai"}) {
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
    LLM.stop();
    POSTGRES.stop();
  }

  @Autowired private MockMvc mvc;

  @Test
  @Order(1)
  void onboards_a_tenant_and_loads_sample_data() throws Exception {
    tenant =
        JsonPath.read(
            mvc.perform(
                    post("/api/v1/admin/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ai Corp\",\"slug\":\"ai-corp\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.id");

    mvc.perform(post("/api/v1/admin/sample-data").header(HeaderTenantResolver.HEADER, tenant))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teamsComputed").value(3));
  }

  @Test
  @Order(2)
  void a_never_configured_tenant_reads_a_disabled_policy_and_status() throws Exception {
    mvc.perform(get("/api/v1/ai/policy").header(HeaderTenantResolver.HEADER, tenant))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.hasSecret").value(false));

    mvc.perform(get("/api/v1/ai/status").header(HeaderTenantResolver.HEADER, tenant))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false));
  }

  @Test
  @Order(3)
  void explain_is_refused_with_409_while_disabled() throws Exception {
    mvc.perform(
            post("/api/v1/insights/explain")
                .header(HeaderTenantResolver.HEADER, tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"weeks\":12}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("/problems/ai-disabled"))
        .andExpect(jsonPath("$.detail").value("AI explanations are not enabled for this tenant"));
  }

  @Test
  @Order(4)
  void enabling_without_a_provider_is_rejected_then_a_valid_update_stores_the_secret()
      throws Exception {
    mvc.perform(
            put("/api/v1/ai/policy")
                .header(HeaderTenantResolver.HEADER, tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}"))
        .andExpect(status().isBadRequest());

    mvc.perform(
            put("/api/v1/ai/policy")
                .header(HeaderTenantResolver.HEADER, tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"enabled\":true,\"provider\":\"openai-compatible\",\"baseUrl\":\""
                        + LLM.baseUrl()
                        + "\",\"model\":\"gpt-x\",\"secret\":\"sk-test-key\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.provider").value("openai-compatible"))
        .andExpect(jsonPath("$.hasSecret").value(true));

    mvc.perform(get("/api/v1/ai/status").header(HeaderTenantResolver.HEADER, tenant))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true));
  }

  @Test
  @Order(5)
  void a_narrative_citing_only_real_numbers_is_returned_and_audited() throws Exception {
    stubOpenAiCompatible(
        "The team is showing balanced flow characteristics with no unusual patterns this period.");

    mvc.perform(
            post("/api/v1/insights/explain")
                .header(HeaderTenantResolver.HEADER, tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"weeks\":12}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provider").value("openai-compatible"))
        .andExpect(jsonPath("$.disclaimer").value(ExplanationView.DISCLAIMER))
        .andExpect(jsonPath("$.narrative").isNotEmpty());

    LLM.verify(
        postRequestedFor(urlPathEqualTo("/v1/chat/completions"))
            .withHeader("Authorization", equalTo("Bearer sk-test-key")));
    assertThat(latestAuditStatus()).isEqualTo("OK");
  }

  @Test
  @Order(6)
  void a_narrative_citing_an_invented_number_is_rejected_with_502() throws Exception {
    stubOpenAiCompatible("Projected friction could spike to 84217 by year end.");

    mvc.perform(
            post("/api/v1/insights/explain")
                .header(HeaderTenantResolver.HEADER, tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"weeks\":12}"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.type").value("/problems/ai-rejected"))
        .andExpect(
            jsonPath("$.detail")
                .value("the AI narrative failed numeric verification and was discarded"));

    assertThat(latestAuditStatus()).isEqualTo("REJECTED");
  }

  private static void stubOpenAiCompatible(String narrativeText) {
    LLM.resetAll();
    String content = narrativeText.replace("\"", "\\\"");
    LLM.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"model\":\"gpt-x\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""
                            + content
                            + "\"}}]}")));
  }

  private static String latestAuditStatus() throws SQLException {
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT status FROM ai.llm_call_audit ORDER BY created_at DESC LIMIT 1")) {
      assertThat(rs.next()).isTrue();
      return rs.getString(1);
    }
  }
}
