/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eip.app.tenant.HeaderTenantResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
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
 * Proves the first visible slice end-to-end: the app boots as the RLS {@code eip_app} role, and the
 * {@code /api/v1/session} + {@code /api/v1/connectors} endpoints return tenant-scoped data with the
 * tenant resolved from the request and enforced by Row-Level Security — a request for tenant A
 * never sees tenant B's connectors, and a request with no tenant fails closed with RFC 7807.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Tag("integration")
class TenantApiIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  private static final UUID tenantA = UUID.randomUUID();
  private static final UUID tenantB = UUID.randomUUID();

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    POSTGRES.start();
    prepareDatabase();
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "eip_app");
    registry.add("spring.datasource.password", () -> "eip_app_pw");
    registry.add("spring.flyway.enabled", () -> "false"); // migrated below as the superuser
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
      st.execute("GRANT USAGE ON SCHEMA core TO eip_app");
      st.execute("GRANT SELECT ON ALL TABLES IN SCHEMA core TO eip_app");
      // Seed as the superuser (bypasses RLS — setup only).
      st.execute(insertTenant(tenantA, "Tenant A", "tenant-a"));
      st.execute(insertTenant(tenantB, "Tenant B", "tenant-b"));
      st.execute(insertOrg(tenantA, "Org A", "org-a"));
      st.execute(insertOrg(tenantB, "Org B", "org-b"));
      // Tenant A gets three connectors (deterministic name order A < B < C) to exercise paging;
      // tenant B gets one, so cross-tenant isolation is a strong discriminator (3 vs 1, not 1 vs
      // 1).
      st.execute(insertConnector(tenantA, "jira", "A Jira (simulation)"));
      st.execute(insertConnector(tenantA, "bitbucket", "B Bitbucket (simulation)"));
      st.execute(insertConnector(tenantA, "sonarqube", "C Sonar (simulation)"));
      st.execute(insertConnector(tenantB, "bitbucket", "Bitbucket B (simulation)"));
    } catch (SQLException e) {
      throw new IllegalStateException("failed to prepare test database", e);
    }
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Autowired private MockMvc mvc;

  @Test
  void connectors_endpoint_returns_only_the_requesting_tenants_connectors() throws Exception {
    // Tenant A sees exactly its three connectors, ordered by name, inside the PageView envelope.
    mvc.perform(get("/api/v1/connectors").header(HeaderTenantResolver.HEADER, tenantA.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(3))
        .andExpect(jsonPath("$.items[0].name").value("A Jira (simulation)"))
        .andExpect(jsonPath("$.items[0].type").value("jira"))
        .andExpect(jsonPath("$.items[0].simulation").value(true))
        .andExpect(jsonPath("$.items[2].name").value("C Sonar (simulation)"))
        .andExpect(jsonPath("$.hasMore").value(false))
        .andExpect(jsonPath("$.nextCursor").doesNotExist());

    // Tenant B sees only its own — RLS-off would surface all four rows.
    mvc.perform(get("/api/v1/connectors").header(HeaderTenantResolver.HEADER, tenantB.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].name").value("Bitbucket B (simulation)"));
  }

  @Test
  void connectors_endpoint_paginates_with_an_opaque_cursor() throws Exception {
    // Page 1: the first two of tenant A's three connectors, with more to come.
    String page1 =
        mvc.perform(
                get("/api/v1/connectors")
                    .param("limit", "2")
                    .header(HeaderTenantResolver.HEADER, tenantA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].name").value("A Jira (simulation)"))
            .andExpect(jsonPath("$.items[1].name").value("B Bitbucket (simulation)"))
            .andExpect(jsonPath("$.hasMore").value(true))
            .andExpect(jsonPath("$.nextCursor").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String cursor = JsonPath.read(page1, "$.nextCursor");

    // Page 2: the remaining connector, no further pages, cursor omitted.
    mvc.perform(
            get("/api/v1/connectors")
                .param("limit", "2")
                .param("cursor", cursor)
                .header(HeaderTenantResolver.HEADER, tenantA.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].name").value("C Sonar (simulation)"))
        .andExpect(jsonPath("$.hasMore").value(false))
        .andExpect(jsonPath("$.nextCursor").doesNotExist());
  }

  @Test
  void connectors_endpoint_rejects_a_malformed_cursor() throws Exception {
    mvc.perform(
            get("/api/v1/connectors")
                .param("cursor", "!!!not-base64!!!")
                .header(HeaderTenantResolver.HEADER, tenantA.toString()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Invalid cursor"));
  }

  @Test
  void session_endpoint_returns_the_resolved_tenant_and_org() throws Exception {
    mvc.perform(get("/api/v1/session").header(HeaderTenantResolver.HEADER, tenantA.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(tenantA.toString()))
        .andExpect(jsonPath("$.organizationName").value("Org A"));
  }

  @Test
  void missing_tenant_fails_closed_with_problem_json() throws Exception {
    mvc.perform(get("/api/v1/connectors"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Tenant required"));
  }

  @Test
  void openapi_contract_is_generated_for_the_v1_surface() throws Exception {
    String contract =
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/v1/connectors']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/session']").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    exportContractIfRequested(contract);
  }

  /**
   * Writes the committed OpenAPI snapshot ({@code openapi/eip-openapi-v1.json}) when {@code
   * EIP_OPENAPI_EXPORT} is set. Off in CI, so the test never mutates the tree during a normal
   * build; regenerate the snapshot by running with the env var set. The full additive-only OpenAPI
   * diff gate is wired in TASK-0011.
   */
  private static void exportContractIfRequested(String contract) throws Exception {
    if (System.getenv("EIP_OPENAPI_EXPORT") == null) {
      return;
    }
    ObjectMapper mapper = new ObjectMapper();
    Object tree = mapper.readValue(contract, Object.class);
    Path target = Path.of("openapi", "eip-openapi-v1.json");
    Files.createDirectories(target.getParent());
    mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), tree);
  }

  // --- seed SQL (superuser, RLS-bypassed) ----------------------------------

  private static String insertTenant(UUID id, String name, String slug) {
    return "INSERT INTO core.tenant (id, name, slug) VALUES ('"
        + id
        + "', '"
        + name
        + "', '"
        + slug
        + "')";
  }

  private static String insertOrg(UUID tenantId, String name, String slug) {
    return "INSERT INTO core.organization (id, tenant_id, name, slug) VALUES ('"
        + UUID.randomUUID()
        + "', '"
        + tenantId
        + "', '"
        + name
        + "', '"
        + slug
        + "')";
  }

  private static String insertConnector(UUID tenantId, String type, String name) {
    return "INSERT INTO core.connector (id, tenant_id, type, name, status, simulation) VALUES ('"
        + UUID.randomUUID()
        + "', '"
        + tenantId
        + "', '"
        + type
        + "', '"
        + name
        + "', 'REGISTERED', true)";
  }
}
