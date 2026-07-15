/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eip.app.security.EipTenantClaimValidator;
import com.eip.app.tenant.HeaderTenantResolver;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import rbacfixture.DenyByDefaultTestController;

/**
 * Proves M5 Wave S1a's {@code oidc} mode end-to-end via {@code
 * SecurityMockMvcRequestPostProcessors.jwt()} — NO real Keycloak: unauthenticated requests fail
 * closed (401 problem+json), the tenant is selected from the validated JWT's {@code eip_tenant}
 * claim (never the {@code X-EIP-Tenant} header, even when one is supplied), and deny-by-default
 * RBAC (SecurityModel §4, FR-122) is enforced per endpoint via the same {@code
 * PermissionEnforcementInterceptor} the header-mode ITs already exercise.
 *
 * <p>{@code eip.security.mode=oidc} and a dummy (unreachable) issuer are set as test properties;
 * the {@code oidc}-mode {@code JwtDecoder} bean is never dereferenced by any test here (see {@code
 * LazyJwtDecoder}), since {@code jwt()} authenticates each request directly, bypassing real token
 * decoding/validation entirely.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "eip.security.mode=oidc",
      "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://idp.test/realms/eip",
      "eip.reports.scheduled-enabled=false"
    })
@AutoConfigureMockMvc
@Import(DenyByDefaultTestController.class)
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OidcRbacIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  private static final UUID tenantA = UUID.randomUUID();
  // Never inserted into core.tenant — a decoy id proving the X-EIP-Tenant header is ignored.
  private static final UUID bogusOtherTenant = UUID.randomUUID();

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
      // Seeded as the superuser (bypasses RLS — setup only); sample-data (called via the API,
      // JWT-authenticated below) builds the rest of tenantA's structure + computed friction.
      st.execute(
          "INSERT INTO core.tenant (id, name, slug) VALUES ('"
              + tenantA
              + "', 'Oidc Test Tenant', 'oidc-test-tenant')");
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
  @Order(1)
  void unauthenticated_request_fails_closed_with_problem_json() throws Exception {
    mvc.perform(get("/api/v1/session"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Unauthenticated"))
        .andExpect(jsonPath("$.type").value("/problems/unauthenticated"));
  }

  @Test
  @Order(2)
  void auth_discovery_reports_oidc_mode_without_any_token() throws Exception {
    mvc.perform(get("/api/v1/session/auth"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("OIDC"))
        .andExpect(jsonPath("$.issuer").value("https://idp.test/realms/eip"))
        .andExpect(jsonPath("$.clientId").value("eip-frontend"));
  }

  @Test
  @Order(3)
  void a_jwt_missing_the_tenant_claim_is_unauthenticated() throws Exception {
    mvc.perform(
            get("/api/v1/session")
                .with(jwt().jwt(roles("TENANT_ADMIN")))) // no eip_tenant claim at all
        .andExpect(status().isUnauthorized());
  }

  @Test
  @Order(4)
  void loads_sample_data_for_the_seeded_tenant_via_a_tenant_admin_jwt() throws Exception {
    mvc.perform(
            post("/api/v1/admin/sample-data")
                .with(jwt().jwt(rolesAndTenant("TENANT_ADMIN", tenantA))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teamsComputed").value(3));
  }

  @Test
  @Order(5)
  void viewer_can_view_dashboards_but_not_generate_reports() throws Exception {
    mvc.perform(get("/api/v1/friction/summary").with(jwt().jwt(rolesAndTenant("VIEWER", tenantA))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teamsReporting").value(3));

    mvc.perform(
            post("/api/v1/reports")
                .with(jwt().jwt(rolesAndTenant("VIEWER", tenantA)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"EXEC_SUMMARY\",\"weeks\":12}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value("Permission denied"))
        .andExpect(jsonPath("$.detail", containsString("report.generate")));
  }

  @Test
  @Order(6)
  void analyst_can_generate_reports() throws Exception {
    mvc.perform(
            post("/api/v1/reports")
                .with(jwt().jwt(rolesAndTenant("ANALYST", tenantA)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"EXEC_SUMMARY\",\"weeks\":12}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.type").value("EXEC_SUMMARY"));
  }

  @Test
  @Order(7)
  void tenant_is_selected_from_the_jwt_claim_never_the_header() throws Exception {
    // A JWT for tenantA + a bogus X-EIP-Tenant header naming a different (nonexistent) tenant:
    // the response must reflect tenantA's real, non-empty data — proving the header was ignored.
    // If the header had been honored, this would 401/empty against the never-seeded bogus tenant.
    mvc.perform(
            get("/api/v1/friction/summary")
                .with(jwt().jwt(rolesAndTenant("TENANT_ADMIN", tenantA)))
                .header(HeaderTenantResolver.HEADER, bogusOtherTenant.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teamsReporting").value(3))
        .andExpect(jsonPath("$.teams[0].teamName").value("Platform"));
  }

  @Test
  @Order(8)
  void an_endpoint_declaring_no_permission_is_denied_by_default() throws Exception {
    mvc.perform(
            get("/api/v1/test-only/unannotated")
                .with(jwt().jwt(rolesAndTenant("TENANT_ADMIN", tenantA))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value("Permission denied"))
        .andExpect(jsonPath("$.detail", containsString("declares no permission")));
  }

  private static Consumer<Jwt.Builder> roles(String... roleNames) {
    return builder -> builder.claim("realm_access", Map.of("roles", List.of(roleNames)));
  }

  private static Consumer<Jwt.Builder> rolesAndTenant(String role, UUID tenantId) {
    return builder ->
        builder
            .claim("realm_access", Map.of("roles", List.of(role)))
            .claim(EipTenantClaimValidator.TENANT_CLAIM, tenantId.toString());
  }
}
