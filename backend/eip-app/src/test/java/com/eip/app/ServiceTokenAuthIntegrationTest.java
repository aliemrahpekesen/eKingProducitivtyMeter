/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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
 * Proves service-token authentication (SecurityModel §3, DEBT-012 residual) end-to-end in {@code
 * header} mode (the app's default {@code eip.security.mode}) — service tokens are a
 * mode-independent third auth path, so this deliberately does NOT run under {@code oidc} (that mode
 * is already exercised for JWT auth by {@code OidcRbacIntegrationTest}; proving the token path
 * works even in {@code header} mode is the point, since {@code header} mode's own implicit
 * principal carries no authentication at all).
 *
 * <p>A tenant-scoped token, once created, authenticates {@code GET /api/v1/session}
 * (DASHBOARD_VIEW-gated) using ONLY {@code Authorization: Bearer <token>} — no {@code X-EIP-Tenant}
 * — and a spoofed {@code X-EIP-Tenant} naming a different tenant is proven ignored, mirroring how
 * {@code OidcRbacIntegrationTest} proves the JWT claim wins over the header.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ServiceTokenAuthIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  private static final UUID TENANT_A = UUID.randomUUID();
  private static final UUID TENANT_B = UUID.randomUUID();

  private static String rawToken = "";
  private static String tokenId = "";

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
      for (String schema : new String[] {"core"}) {
        st.execute("GRANT USAGE ON SCHEMA " + schema + " TO eip_app");
        st.execute(
            "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA "
                + schema
                + " TO eip_app");
      }
      // DEBT-024 Wave 3B: service-token issue/revoke now also write audit.audit_event.
      st.execute("GRANT USAGE ON SCHEMA audit TO eip_app");
      st.execute("GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA audit TO eip_app");
      st.execute(
          "INSERT INTO core.tenant (id, name, slug) VALUES ('"
              + TENANT_A
              + "', 'Tenant A', 'svctok-tenant-a')");
      st.execute(
          "INSERT INTO core.tenant (id, name, slug) VALUES ('"
              + TENANT_B
              + "', 'Tenant B', 'svctok-tenant-b')");
      st.execute(
          "INSERT INTO core.organization (id, tenant_id, name, slug) VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + TENANT_A
              + "', 'Org A', 'svctok-org-a')");
      st.execute(
          "INSERT INTO core.organization (id, tenant_id, name, slug) VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + TENANT_B
              + "', 'Org B', 'svctok-org-b')");
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
  void tenantAdminIssuesATenantScopedAnalystToken() throws Exception {
    String response =
        mvc.perform(
                post("/api/v1/admin/service-tokens")
                    .header(HeaderTenantResolver.HEADER, TENANT_A.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"ci-token\",\"role\":\"ANALYST\",\"expiresInDays\":90}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.token").value(startsWith("eipt_")))
            .andExpect(jsonPath("$.role").value("ANALYST"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    rawToken = JsonPath.read(response, "$.token");
    tokenId = JsonPath.read(response, "$.id");
  }

  @Test
  @Order(2)
  void theTokenAuthenticatesWithNoTenantHeaderAtAllAndResolvesTenantAsBoundData() throws Exception {
    mvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + rawToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(TENANT_A.toString()))
        .andExpect(jsonPath("$.organizationName").value("Org A"));
  }

  @Test
  @Order(3)
  void aSpoofedTenantHeaderAlongsideTheTokenIsIgnored() throws Exception {
    // Bogus X-EIP-Tenant naming Tenant B accompanies the Bearer token: the response must still
    // reflect Tenant A's data — proving the header carries no weight once a service token has
    // authenticated the request (mirrors OidcRbacIntegrationTest's JWT-claim-wins-over-header
    // proof).
    mvc.perform(
            get("/api/v1/session")
                .header("Authorization", "Bearer " + rawToken)
                .header(HeaderTenantResolver.HEADER, TENANT_B.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(TENANT_A.toString()))
        .andExpect(jsonPath("$.organizationName").value("Org A"));
  }

  @Test
  @Order(4)
  void tenantAdminRevokesTheToken() throws Exception {
    mvc.perform(
            delete("/api/v1/admin/service-tokens/{id}", tokenId)
                .header(HeaderTenantResolver.HEADER, TENANT_A.toString()))
        .andExpect(status().isNoContent());
  }

  @Test
  @Order(5)
  void theRevokedTokenNoLongerAuthenticates() throws Exception {
    mvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + rawToken))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.title").value("Unauthenticated"));
  }

  @Test
  @Order(6)
  void anExpiredTokenIsRejected() throws Exception {
    String response =
        mvc.perform(
                post("/api/v1/admin/service-tokens")
                    .header(HeaderTenantResolver.HEADER, TENANT_A.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"expiring-soon\",\"role\":\"VIEWER\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String expiringToken = JsonPath.read(response, "$.token");
    String expiringId = JsonPath.read(response, "$.id");
    backdateExpiry(expiringId);

    mvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + expiringToken))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @Order(7)
  void aTokenBoundToARoleLackingTheRequiredPermissionIsForbidden() throws Exception {
    // SECURITY_AUDITOR's only permission is audit.read (Role.java) — no dashboard.view.
    String response =
        mvc.perform(
                post("/api/v1/admin/service-tokens")
                    .header(HeaderTenantResolver.HEADER, TENANT_A.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"auditor-token\",\"role\":\"SECURITY_AUDITOR\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String auditorToken = JsonPath.read(response, "$.token");

    mvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + auditorToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value("Permission denied"))
        .andExpect(jsonPath("$.detail", containsString("dashboard.view")));
  }

  @Test
  @Order(8)
  void issueAndRevokeEachWriteAnAuditRowWithNoTokenValueLeak() throws Exception {
    // tokenId/rawToken were captured back in Order(1); the token was revoked in Order(4) — by
    // this point in the ordered sequence both lifecycle events have happened exactly once.
    List<String[]> rows = queryAuditEvents(tokenId);
    assertThat(rows)
        .extracting(r -> r[0])
        .containsExactly("auth.token.issued", "auth.token.revoked");
    for (String[] row : rows) {
      String outcome = row[1];
      String detailJson = row[2];
      assertThat(outcome).isEqualTo("SUCCESS");
      JsonNode detail = readJson(detailJson);
      assertThat(detail.get("tokenId").asText()).isEqualTo(tokenId);
      assertThat(detail.get("category").asText()).isEqualTo("auth");
      // Never the raw bearer value or anything derived from it.
      assertThat(detailJson).doesNotContain(rawToken);
    }
  }

  private static JsonNode readJson(String json) {
    try {
      return new ObjectMapper().readTree(json);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("failed to parse audit detail json: " + json, e);
    }
  }

  private static List<String[]> queryAuditEvents(String tokenId) throws SQLException {
    List<String[]> rows = new ArrayList<>();
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT action, outcome, detail::text AS detail_json FROM audit.audit_event"
                    + " WHERE detail->>'tokenId' = ? ORDER BY occurred_at")) {
      ps.setString(1, tokenId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(
              new String[] {
                rs.getString("action"), rs.getString("outcome"), rs.getString("detail_json")
              });
        }
      }
    }
    return rows;
  }

  private static void backdateExpiry(String id) throws SQLException {
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute(
          "UPDATE core.service_token SET expires_at = now() - interval '1 day' WHERE id = '"
              + id
              + "'");
    }
  }
}
