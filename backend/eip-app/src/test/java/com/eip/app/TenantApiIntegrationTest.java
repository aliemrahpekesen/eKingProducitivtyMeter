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
      st.execute(insertConnector(tenantA, "jira", "Jira A (simulation)"));
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
    mvc.perform(get("/api/v1/connectors").header(HeaderTenantResolver.HEADER, tenantA.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].name").value("Jira A (simulation)"))
        .andExpect(jsonPath("$[0].type").value("jira"))
        .andExpect(jsonPath("$[0].simulation").value(true));

    mvc.perform(get("/api/v1/connectors").header(HeaderTenantResolver.HEADER, tenantB.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].name").value("Bitbucket B (simulation)"));
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
