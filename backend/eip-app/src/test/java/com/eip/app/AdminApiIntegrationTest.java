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
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.secrets.SecretsService;
import com.eip.tenancy.tx.TenantTransactionRunner;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
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
 * Proves the M1 admin panel backend end-to-end as the NOBYPASSRLS {@code eip_app} role, starting
 * from an EMPTY database: register a tenant through the API, build its organisation structure,
 * register a Jira connector whose token is envelope-encrypted at rest (and never returned), observe
 * honest connection tests (simulation OK, Jira NOT_AVAILABLE until M2), tenant isolation on the
 * admin surface, and the one-click sample-data action computing real friction (Platform 91).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminApiIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  private static final String JIRA_TOKEN = "super-secret-jira-token-123";

  private static String tenantA = "";
  private static String tenantB = "";
  private static String jiraConnectorId = "";

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
  @Autowired private SecretsService secrets;
  @Autowired private TenantTransactionRunner runner;

  @Test
  @Order(1)
  void onboards_two_tenants_through_the_platform_api() throws Exception {
    tenantA =
        JsonPath.read(
            mvc.perform(
                    post("/api/v1/admin/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme\",\"slug\":\"acme\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("acme"))
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.id");
    tenantB =
        JsonPath.read(
            mvc.perform(
                    post("/api/v1/admin/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Globex\",\"slug\":\"globex\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.id");

    mvc.perform(get("/api/v1/admin/tenants"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].slug", org.hamcrest.Matchers.hasItems("acme", "globex")));

    // Duplicate slug + invalid slug fail closed with problem+json.
    mvc.perform(
            post("/api/v1/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Dup\",\"slug\":\"acme\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Bad\",\"slug\":\"UPPER CASE\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @Order(2)
  void builds_the_org_structure_tenant_scoped() throws Exception {
    String orgId =
        JsonPath.read(
            mvc.perform(
                    post("/api/v1/admin/organizations")
                        .header(HeaderTenantResolver.HEADER, tenantA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme Corp\",\"slug\":\"acme-corp\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.id");
    String buId =
        JsonPath.read(
            mvc.perform(
                    post("/api/v1/admin/business-units")
                        .header(HeaderTenantResolver.HEADER, tenantA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationId\":\"" + orgId + "\",\"name\":\"Engineering\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.id");
    mvc.perform(
            post("/api/v1/admin/teams")
                .header(HeaderTenantResolver.HEADER, tenantA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessUnitId\":\"" + buId + "\",\"name\":\"Core Team\"}"))
        .andExpect(status().isCreated());

    mvc.perform(get("/api/v1/admin/structure").header(HeaderTenantResolver.HEADER, tenantA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("Acme Corp"))
        .andExpect(jsonPath("$[0].businessUnits[0].teams[0].name").value("Core Team"));

    // Tenant B sees none of it (RLS).
    mvc.perform(get("/api/v1/admin/structure").header(HeaderTenantResolver.HEADER, tenantB))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  @Order(3)
  void registers_a_jira_connector_with_an_envelope_encrypted_secret() throws Exception {
    mvc.perform(get("/api/v1/admin/connector-types"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.type=='jira')].syncAvailable").value(false))
        .andExpect(jsonPath("$[?(@.type=='simulation')].syncAvailable").value(true));

    String body =
        mvc.perform(
                post("/api/v1/admin/connectors")
                    .header(HeaderTenantResolver.HEADER, tenantA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"type\":\"jira\",\"name\":\"Acme Jira\",\"config\":{\"baseUrl\":"
                            + "\"https://acme.atlassian.net\",\"email\":\"svc@acme.io\"},"
                            + "\"secret\":\""
                            + JIRA_TOKEN
                            + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.hasSecret").value(true))
            .andExpect(jsonPath("$.status").value("CONFIGURED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    jiraConnectorId = JsonPath.read(body, "$.id");
    assertThat(body).doesNotContain(JIRA_TOKEN); // secret never leaves the server

    // Missing required config + missing secret fail closed.
    mvc.perform(
            post("/api/v1/admin/connectors")
                .header(HeaderTenantResolver.HEADER, tenantA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"jira\",\"name\":\"No URL\",\"config\":{},\"secret\":\"x\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/admin/connectors")
                .header(HeaderTenantResolver.HEADER, tenantA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"type\":\"jira\",\"name\":\"No Token\",\"config\":{\"baseUrl\":\"https://x\","
                        + "\"email\":\"a@b.c\"}}"))
        .andExpect(status().isBadRequest());

    // At rest: ciphertext only — the plaintext token appears nowhere in core.secret.
    try (Connection su =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = su.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT ciphertext, algo FROM core.secret ORDER BY created_at DESC")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString("algo")).isEqualTo("AES-256-GCM");
      assertThat(new String(rs.getBytes("ciphertext"), StandardCharsets.ISO_8859_1))
          .doesNotContain(JIRA_TOKEN);
    }
  }

  @Test
  @Order(4)
  void secret_reveals_in_process_and_stays_tenant_isolated() throws Exception {
    // Fetch the secret id as superuser (setup introspection only).
    UUID secretId;
    try (Connection su =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = su.createStatement();
        ResultSet rs = st.executeQuery("SELECT id FROM core.secret ORDER BY created_at DESC")) {
      assertThat(rs.next()).isTrue();
      secretId = rs.getObject(1, UUID.class);
    }
    String revealed =
        runner.call(TenantContext.of(UUID.fromString(tenantA)), () -> secrets.reveal(secretId));
    assertThat(revealed).isEqualTo(JIRA_TOKEN);

    // Tenant B cannot see tenant A's connectors (admin list under RLS).
    mvc.perform(get("/api/v1/admin/connectors").header(HeaderTenantResolver.HEADER, tenantB))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(get("/api/v1/admin/connectors").header(HeaderTenantResolver.HEADER, tenantA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("Acme Jira"))
        .andExpect(jsonPath("$[0].hasSecret").value(true));
  }

  @Test
  @Order(5)
  void connection_tests_are_honest_and_status_toggles() throws Exception {
    mvc.perform(
            post("/api/v1/admin/connectors/" + jiraConnectorId + "/test")
                .header(HeaderTenantResolver.HEADER, tenantA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("NOT_AVAILABLE"));

    String simBody =
        mvc.perform(
                post("/api/v1/admin/connectors")
                    .header(HeaderTenantResolver.HEADER, tenantA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"type\":\"simulation\",\"name\":\"Sample Source\",\"config\":{}}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String simId = JsonPath.read(simBody, "$.id");
    mvc.perform(
            post("/api/v1/admin/connectors/" + simId + "/test")
                .header(HeaderTenantResolver.HEADER, tenantA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("OK"));

    mvc.perform(
            post("/api/v1/admin/connectors/" + jiraConnectorId + "/status")
                .header(HeaderTenantResolver.HEADER, tenantA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DISABLED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DISABLED"));
  }

  @Test
  @Order(6)
  void one_click_sample_data_computes_real_friction_for_a_fresh_tenant() throws Exception {
    mvc.perform(post("/api/v1/admin/sample-data").header(HeaderTenantResolver.HEADER, tenantA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teamsComputed").value(3))
        .andExpect(jsonPath("$.itemsCorrelated").value(9));

    mvc.perform(get("/api/v1/friction/summary").header(HeaderTenantResolver.HEADER, tenantA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teams[0].teamName").value("Platform"))
        .andExpect(jsonPath("$.teams[0].frictionScore").value(91));

    // Tenant B remains fully isolated: no computed friction.
    mvc.perform(get("/api/v1/friction/summary").header(HeaderTenantResolver.HEADER, tenantB))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teamsReporting").value(0));
  }
}
