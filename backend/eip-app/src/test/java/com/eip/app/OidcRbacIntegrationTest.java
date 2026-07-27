/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eip.app.security.EipTenantClaimValidator;
import com.eip.app.tenant.HeaderTenantResolver;
import com.jayway.jsonpath.JsonPath;
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

  /**
   * M6-A (ADR-024): {@code ai.agent.invoke} RBAC denial. Header mode always resolves an implicit
   * {@code TENANT_ADMIN} principal (see {@code AiLayerIntegrationTest}, which covers the AI layer's
   * happy paths), so a genuine 403 for a lesser role can only be exercised here, against a real
   * validated-JWT role.
   */
  @Test
  @Order(9)
  void viewer_cannot_invoke_the_ai_explanation_layer() throws Exception {
    mvc.perform(
            post("/api/v1/insights/explain")
                .with(jwt().jwt(rolesAndTenant("VIEWER", tenantA)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"weeks\":12}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value("Permission denied"))
        .andExpect(jsonPath("$.detail", containsString("ai.agent.invoke")));
  }

  /**
   * SecurityModel §4 layer 2's own illustrative example, proven end-to-end over the real {@code
   * eip-analytics} beans (unit coverage over the decorators/filter themselves lives in {@code
   * TeamScopedFrictionQueriesTest}/{@code EipPrincipalFilterTest}): an {@code ENGINEERING_MANAGER}
   * scoped to one team sees only that team in the friction summary, with a corrected {@code
   * teamsReporting} count.
   */
  @Test
  @Order(10)
  void engineering_manager_scoped_to_one_team_sees_only_that_team() throws Exception {
    String scopedTeamId = firstTeamId();

    mvc.perform(
            get("/api/v1/friction/summary")
                .with(
                    jwt()
                        .jwt(
                            rolesTenantAndTeams(
                                "ENGINEERING_MANAGER", tenantA, List.of(scopedTeamId)))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teamsReporting").value(1))
        .andExpect(jsonPath("$.teams.length()").value(1))
        .andExpect(jsonPath("$.teams[0].teamId").value(scopedTeamId));
  }

  /**
   * Per {@code EipPrincipalFilter}'s documented decision (SecurityModel §4's "manager-scope
   * templates" wording): {@code eip_teams} only narrows a manager-scope role; MEMBER presenting the
   * same claim has it ignored and sees the tenant's full team list.
   */
  @Test
  @Order(11)
  void member_role_with_the_teams_claim_has_it_ignored() throws Exception {
    String scopedTeamId = firstTeamId();

    mvc.perform(
            get("/api/v1/friction/summary")
                .with(jwt().jwt(rolesTenantAndTeams("MEMBER", tenantA, List.of(scopedTeamId)))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teamsReporting").value(3));
  }

  @Test
  @Order(12)
  void engineering_manager_evidence_drill_down_is_denied_for_an_out_of_scope_team()
      throws Exception {
    String scopedTeamId = firstTeamId();
    String otherTeamId =
        JsonPath.read(
            mvc.perform(
                    get("/api/v1/friction/summary")
                        .with(jwt().jwt(rolesAndTenant("TENANT_ADMIN", tenantA))))
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.teams[1].teamId");

    mvc.perform(
            get("/api/v1/friction/teams/" + otherTeamId + "/evidence")
                .with(
                    jwt()
                        .jwt(
                            rolesTenantAndTeams(
                                "ENGINEERING_MANAGER", tenantA, List.of(scopedTeamId)))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value("Permission denied"));
  }

  @Test
  @Order(13)
  void engineering_manager_evidence_drill_down_is_allowed_for_the_scoped_team() throws Exception {
    String scopedTeamId = firstTeamId();

    mvc.perform(
            get("/api/v1/friction/teams/" + scopedTeamId + "/evidence")
                .with(
                    jwt()
                        .jwt(
                            rolesTenantAndTeams(
                                "ENGINEERING_MANAGER", tenantA, List.of(scopedTeamId)))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teamId").value(scopedTeamId));
  }

  /**
   * Tenant-editable role-permission overrides (SecurityModel §4, DEBT-012 residual part 2,
   * ADR-026): a {@code TENANT_ADMIN} both revokes {@code ANALYST}'s default {@code report.generate}
   * permission and grants it {@code audit.read} (a permission the role's default set does NOT
   * include) in one batch — proving both directions of "adds/revokes a permission correctly" — and
   * the revoke takes effect immediately for a subsequent {@code ANALYST} JWT: the same endpoint
   * {@code analyst_can_generate_reports} (Order 6) proved was ALLOWED before any override existed.
   */
  @Test
  @Order(14)
  void tenant_admin_grants_and_revokes_analyst_permissions_and_it_takes_effect_immediately()
      throws Exception {
    mvc.perform(
            put("/api/v1/admin/roles/ANALYST/permissions")
                .with(jwt().jwt(rolesAndTenant("TENANT_ADMIN", tenantA)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"overrides\":["
                        + "{\"permission\":\"REPORT_GENERATE\",\"granted\":false},"
                        + "{\"permission\":\"AUDIT_READ\",\"granted\":true}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("ANALYST"))
        .andExpect(jsonPath("$.effectivePermissions", not(hasItem("report.generate"))))
        .andExpect(jsonPath("$.effectivePermissions", hasItem("audit.read")))
        .andExpect(jsonPath("$.effectivePermissions", hasItem("dashboard.view")));

    mvc.perform(
            post("/api/v1/reports")
                .with(jwt().jwt(rolesAndTenant("ANALYST", tenantA)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"EXEC_SUMMARY\",\"weeks\":12}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value("Permission denied"))
        .andExpect(jsonPath("$.detail", containsString("report.generate")));
  }

  @Test
  @Order(15)
  void get_reflects_the_effective_set_and_stored_overrides() throws Exception {
    // overrides are returned sorted by permission name: AUDIT_READ before REPORT_GENERATE.
    mvc.perform(
            get("/api/v1/admin/roles/ANALYST/permissions")
                .with(jwt().jwt(rolesAndTenant("TENANT_ADMIN", tenantA))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.effectivePermissions", not(hasItem("report.generate"))))
        .andExpect(jsonPath("$.effectivePermissions", hasItem("audit.read")))
        .andExpect(jsonPath("$.overrides[0].permission").value("AUDIT_READ"))
        .andExpect(jsonPath("$.overrides[0].granted").value(true))
        .andExpect(jsonPath("$.overrides[1].permission").value("REPORT_GENERATE"))
        .andExpect(jsonPath("$.overrides[1].granted").value(false));
  }

  @Test
  @Order(16)
  void self_lockout_guard_rejects_revoking_tenant_admins_own_user_manage() throws Exception {
    mvc.perform(
            put("/api/v1/admin/roles/TENANT_ADMIN/permissions")
                .with(jwt().jwt(rolesAndTenant("TENANT_ADMIN", tenantA)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"overrides\":[{\"permission\":\"USER_MANAGE\",\"granted\":false}]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Validation failed"))
        .andExpect(jsonPath("$.detail", containsString("self-lockout")));
  }

  /**
   * DEBT-012 residual (frontend half): {@code GET /api/v1/session} exposes the caller's effective
   * permission set to the SPA (driving the {@code <Can>} gate). Ordered AFTER Order 14 applied a
   * tenant override to {@code ANALYST} (revoke {@code report.generate}, grant {@code audit.read}),
   * so this proves the endpoint surfaces the SAME override-adjusted set the interceptor enforces —
   * not merely the role's static defaults — sorted ascending by wire id.
   */
  @Test
  @Order(17)
  void session_exposes_the_callers_effective_permissions_including_tenant_overrides()
      throws Exception {
    mvc.perform(get("/api/v1/session").with(jwt().jwt(rolesAndTenant("ANALYST", tenantA))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(tenantA.toString()))
        .andExpect(jsonPath("$.effectivePermissions").isArray())
        .andExpect(jsonPath("$.effectivePermissions[0]").value("ai.agent.invoke")) // sorted first
        .andExpect(jsonPath("$.effectivePermissions", hasItem("audit.read"))) // granted by override
        .andExpect(jsonPath("$.effectivePermissions", hasItem("dashboard.view")))
        .andExpect(jsonPath("$.effectivePermissions", not(hasItem("report.generate")))); // revoked
  }

  /** The tenant's worst-first team list is deterministic across calls (same computed data). */
  private String firstTeamId() throws Exception {
    return JsonPath.read(
        mvc.perform(
                get("/api/v1/friction/summary")
                    .with(jwt().jwt(rolesAndTenant("TENANT_ADMIN", tenantA))))
            .andReturn()
            .getResponse()
            .getContentAsString(),
        "$.teams[0].teamId");
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

  private static Consumer<Jwt.Builder> rolesTenantAndTeams(
      String role, UUID tenantId, List<String> teamIds) {
    return builder ->
        builder
            .claim("realm_access", Map.of("roles", List.of(role)))
            .claim(EipTenantClaimValidator.TENANT_CLAIM, tenantId.toString())
            .claim("eip_teams", teamIds);
  }
}
