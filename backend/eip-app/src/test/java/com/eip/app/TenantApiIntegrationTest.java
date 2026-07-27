/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
      for (String schema : new String[] {"core", "analytics", "work", "scm", "cicd", "quality"}) {
        st.execute("GRANT USAGE ON SCHEMA " + schema + " TO eip_app");
        st.execute("GRANT SELECT ON ALL TABLES IN SCHEMA " + schema + " TO eip_app");
      }
      // Seed as the superuser (bypasses RLS — setup only).
      st.execute(insertTenant(tenantA, "Tenant A", "tenant-a"));
      st.execute(insertTenant(tenantB, "Tenant B", "tenant-b"));
      UUID orgA = UUID.randomUUID();
      UUID orgB = UUID.randomUUID();
      st.execute(insertOrg(orgA, tenantA, "Org A", "org-a"));
      st.execute(insertOrg(orgB, tenantB, "Org B", "org-b"));
      // Tenant A gets three connectors (deterministic name order A < B < C) to exercise paging;
      // tenant B gets one, so cross-tenant isolation is a strong discriminator (3 vs 1, not 1 vs
      // 1). created_at is set in the REVERSE of name order (A newest, C oldest) so a
      // `sort=createdAt`
      // request proves it actually reorders results rather than coincidentally matching name order.
      st.execute(insertConnector(tenantA, "jira", "A Jira (simulation)", "2024-01-03T00:00:00Z"));
      st.execute(
          insertConnector(
              tenantA, "bitbucket", "B Bitbucket (simulation)", "2024-01-02T00:00:00Z"));
      st.execute(
          insertConnector(tenantA, "sonarqube", "C Sonar (simulation)", "2024-01-01T00:00:00Z"));
      st.execute(insertConnector(tenantB, "bitbucket", "Bitbucket B (simulation)"));

      // Engineering Friction: each tenant gets its own business unit + friction definition + teams.
      UUID buA = UUID.randomUUID();
      UUID buB = UUID.randomUUID();
      st.execute(insertBusinessUnit(buA, tenantA, orgA, "Eng A"));
      st.execute(insertBusinessUnit(buB, tenantB, orgB, "Eng B"));
      st.execute(insertFrictionDefinition(tenantA));
      st.execute(insertFrictionDefinition(tenantB));
      // Computed-shape read model seeded directly (this test covers the API read path + RLS; the
      // pipeline's compute correctness is proven by FrictionPipelineIntegrationTest). Tenant A
      // worst-first: Platform(91) > Payments(56) > Web(50); tenant B has only Ops(42).
      seedTeamFriction(st, tenantA, buA, "Platform", 91, "REVIEW_WAIT");
      seedTeamFriction(st, tenantA, buA, "Payments", 56, "REVIEW_WAIT");
      seedTeamFriction(st, tenantA, buA, "Web", 50, "REVIEW_WAIT");
      seedTeamFriction(st, tenantB, buB, "Ops", 42, "BLOCKED");
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
  void connectors_endpoint_honors_every_whitelisted_sort_value() throws Exception {
    // Default (sort omitted) and explicit "name" both order ascending by name: A, B, C.
    mvc.perform(get("/api/v1/connectors").header(HeaderTenantResolver.HEADER, tenantA.toString()))
        .andExpect(jsonPath("$.items[0].name").value("A Jira (simulation)"))
        .andExpect(jsonPath("$.items[2].name").value("C Sonar (simulation)"));
    mvc.perform(
            get("/api/v1/connectors")
                .param("sort", "name")
                .header(HeaderTenantResolver.HEADER, tenantA.toString()))
        .andExpect(jsonPath("$.items[0].name").value("A Jira (simulation)"))
        .andExpect(jsonPath("$.items[2].name").value("C Sonar (simulation)"));

    // -name: descending by name, C, B, A.
    mvc.perform(
            get("/api/v1/connectors")
                .param("sort", "-name")
                .header(HeaderTenantResolver.HEADER, tenantA.toString()))
        .andExpect(jsonPath("$.items[0].name").value("C Sonar (simulation)"))
        .andExpect(jsonPath("$.items[2].name").value("A Jira (simulation)"));

    // createdAt: ascending by created_at, which is the REVERSE of name order (seed data) — C, B, A.
    mvc.perform(
            get("/api/v1/connectors")
                .param("sort", "createdAt")
                .header(HeaderTenantResolver.HEADER, tenantA.toString()))
        .andExpect(jsonPath("$.items[0].name").value("C Sonar (simulation)"))
        .andExpect(jsonPath("$.items[2].name").value("A Jira (simulation)"));

    // -createdAt: descending by created_at, so back to name order — A, B, C.
    mvc.perform(
            get("/api/v1/connectors")
                .param("sort", "-createdAt")
                .header(HeaderTenantResolver.HEADER, tenantA.toString()))
        .andExpect(jsonPath("$.items[0].name").value("A Jira (simulation)"))
        .andExpect(jsonPath("$.items[2].name").value("C Sonar (simulation)"));
  }

  @Test
  void connectors_endpoint_rejects_an_unknown_sort_value() throws Exception {
    mvc.perform(
            get("/api/v1/connectors")
                .param("sort", "bogus")
                .header(HeaderTenantResolver.HEADER, tenantA.toString()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value(containsString("bogus")))
        .andExpect(jsonPath("$.detail").value(containsString("createdAt")));
  }

  @Test
  void connectors_endpoint_rejects_a_cursor_whose_sort_does_not_match_the_request()
      throws Exception {
    String page1 =
        mvc.perform(
                get("/api/v1/connectors")
                    .param("limit", "2")
                    .param("sort", "-name")
                    .header(HeaderTenantResolver.HEADER, tenantA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasMore").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String cursor = JsonPath.read(page1, "$.nextCursor");

    // The cursor was issued under sort=-name; requesting page 2 with a different sort is a 400.
    mvc.perform(
            get("/api/v1/connectors")
                .param("limit", "2")
                .param("cursor", cursor)
                .param("sort", "name")
                .header(HeaderTenantResolver.HEADER, tenantA.toString()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    // The same cursor with the ORIGINAL sort still works.
    mvc.perform(
            get("/api/v1/connectors")
                .param("limit", "2")
                .param("cursor", cursor)
                .param("sort", "-name")
                .header(HeaderTenantResolver.HEADER, tenantA.toString()))
        .andExpect(status().isOk());
  }

  @Test
  void session_endpoint_returns_the_resolved_tenant_org_and_effective_permissions()
      throws Exception {
    // Header mode resolves the implicit TENANT_ADMIN principal, so effectivePermissions carries
    // that role's wire ids — sorted ascending (deterministic contract), never the enum names.
    mvc.perform(get("/api/v1/session").header(HeaderTenantResolver.HEADER, tenantA.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(tenantA.toString()))
        .andExpect(jsonPath("$.organizationName").value("Org A"))
        .andExpect(jsonPath("$.effectivePermissions").isArray())
        .andExpect(jsonPath("$.effectivePermissions[0]").value("ai.agent.invoke")) // sorted first
        .andExpect(jsonPath("$.effectivePermissions", hasItem("tenant.manage")))
        .andExpect(jsonPath("$.effectivePermissions", hasItem("dashboard.view")))
        .andExpect(
            jsonPath("$.effectivePermissions", not(hasItem("connector.secret.reveal")))); // opt-in
  }

  @Test
  void missing_tenant_fails_closed_with_problem_json() throws Exception {
    mvc.perform(get("/api/v1/connectors"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Tenant required"));
  }

  @Test
  void friction_summary_is_tenant_isolated_and_computed() throws Exception {
    // Tenant A: three teams, worst-first, with computed scores + component breakdown + dominant
    // cause + version; the metric definition (caveats + gaming risks) is surfaced. RLS-off would
    // leak tenant B's "Ops" team into this list.
    mvc.perform(
            get("/api/v1/friction/summary").header(HeaderTenantResolver.HEADER, tenantA.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teamsReporting").value(3))
        .andExpect(jsonPath("$.metricVersion").value("engineering_friction_v0.1"))
        .andExpect(jsonPath("$.simulation").value(true))
        .andExpect(jsonPath("$.computedAt").isNotEmpty())
        .andExpect(jsonPath("$.metric.key").value("engineering_friction"))
        .andExpect(jsonPath("$.metric.grain").value("team"))
        .andExpect(jsonPath("$.metric.caveats").isNotEmpty())
        .andExpect(jsonPath("$.metric.gamingRisks").isNotEmpty())
        .andExpect(jsonPath("$.metric.inputs.signals").isArray())
        .andExpect(jsonPath("$.teams[0].teamName").value("Platform"))
        .andExpect(jsonPath("$.teams[0].frictionScore").value(91))
        .andExpect(jsonPath("$.teams[0].dominantCause").value("REVIEW_WAIT"))
        .andExpect(jsonPath("$.teams[0].workItems").value(3))
        .andExpect(jsonPath("$.teams[0].reviewWaitPct").value(57))
        .andExpect(jsonPath("$.teams[1].teamName").value("Payments"))
        .andExpect(jsonPath("$.teams[1].frictionScore").value(56))
        .andExpect(jsonPath("$.teams[2].teamName").value("Web"))
        .andExpect(jsonPath("$.teams[2].frictionScore").value(50))
        .andExpect(jsonPath("$.teams[*].teamName", hasItem("Platform")))
        .andExpect(jsonPath("$.teams[*].teamName", not(hasItem("Ops"))));

    // Tenant B sees only its own team.
    mvc.perform(
            get("/api/v1/friction/summary").header(HeaderTenantResolver.HEADER, tenantB.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teamsReporting").value(1))
        .andExpect(jsonPath("$.teams[0].teamName").value("Ops"))
        .andExpect(jsonPath("$.teams[0].frictionScore").value(42))
        .andExpect(jsonPath("$.teams[0].dominantCause").value("BLOCKED"));
  }

  @Test
  void friction_evidence_endpoint_is_tenant_scoped_and_empty_without_correlation()
      throws Exception {
    // A team with no correlation evidence returns 200 with an empty item list + the metric version
    // (the pipeline-backed content is proven by FrictionPipelineIntegrationTest). Exercises the
    // controller routing + evidence service query path under RLS.
    mvc.perform(
            get("/api/v1/friction/teams/{teamId}/evidence", UUID.randomUUID())
                .header(HeaderTenantResolver.HEADER, tenantA.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.metricVersion").value("engineering_friction_v0.1"))
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  @Test
  void openapi_contract_is_generated_for_the_v1_surface() throws Exception {
    String contract =
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/v1/connectors']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/session']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/friction/summary']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/friction/teams/{teamId}/evidence']").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    exportContractIfRequested(contract);
  }

  @Test
  void openapi_contract_carries_the_x_eip_permission_extension_on_gated_operations_only()
      throws Exception {
    // /api/v1/connectors (GET) carries @RequiresPermission(Permission.DASHBOARD_VIEW) — the
    // extension must be a JSON array (any-of semantics, even for a single permission) of the
    // permission's stable wireId(), never the Java enum name.
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/v1/connectors'].get.x-eip-permission").isArray())
        .andExpect(jsonPath("$.paths['/api/v1/connectors'].get.x-eip-permission.length()").value(1))
        .andExpect(
            jsonPath("$.paths['/api/v1/connectors'].get.x-eip-permission[0]")
                .value("dashboard.view"))
        // /api/v1/session/auth (GET) is @PermissionExempt — no permission gates it, so no
        // extension is added at all (not an empty array — genuinely absent).
        .andExpect(jsonPath("$.paths['/api/v1/session/auth'].get.x-eip-permission").doesNotExist());
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

  // --- observability (TASK-0012) -------------------------------------------

  @Test
  void actuator_health_info_and_prometheus_are_exposed() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
    mvc.perform(get("/actuator/info")).andExpect(status().isOk());
    mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
  }

  @Test
  void every_problem_json_carries_the_trace_id() throws Exception {
    // Missing tenant -> 401 problem+json with the active span's traceId (walkable to trace/logs).
    mvc.perform(get("/api/v1/connectors"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.traceId").value(matchesPattern("[0-9a-f]+")));

    // Malformed cursor -> 400 problem+json, also carries traceId.
    mvc.perform(
            get("/api/v1/connectors")
                .param("cursor", "!!!")
                .header(HeaderTenantResolver.HEADER, tenantA.toString()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.traceId").value(matchesPattern("[0-9a-f]+")));
  }

  @Test
  void tenant_id_mdc_does_not_leak_after_the_request() throws Exception {
    MDC.clear();
    mvc.perform(get("/api/v1/session").header(HeaderTenantResolver.HEADER, tenantA.toString()))
        .andExpect(status().isOk());
    // The filter's finally must have removed it — a pooled thread must not carry a tenant forward.
    assertThat(MDC.get("tenantId")).isNull();
  }

  @Test
  void api_metrics_use_eip_naming_with_a_tenant_safe_tag_set() throws Exception {
    // One measured request for a known tenant, then scrape.
    mvc.perform(get("/api/v1/session").header(HeaderTenantResolver.HEADER, tenantA.toString()))
        .andExpect(status().isOk());

    String prometheus =
        mvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(prometheus).contains("eip_api_requests_inflight");
    // The RED metric line for the exercised route carries the safe tag set together (bound, not
    // just co-present anywhere in the scrape).
    assertThat(prometheus.lines())
        .anyMatch(
            l ->
                l.contains("eip_api_request_duration_seconds")
                    && l.contains("route=\"/api/v1/session\"")
                    && l.contains("tenant_present=\"true\"")
                    && l.contains("method=\"GET\"")
                    && l.contains("deployable=\"eip-app\""));
    // Histogram buckets exist so p95/SLO can be computed (ObservabilityModel §3).
    assertThat(prometheus).contains("eip_api_request_duration_seconds_bucket");
    // The tenant id must NEVER appear as a metric label (unbounded cardinality; ObservabilityModel
    // §3).
    assertThat(prometheus).doesNotContain(tenantA.toString());
  }

  @Test
  void api_request_log_carries_the_correlation_and_context_fields() throws Exception {
    Logger accessLog = (Logger) LoggerFactory.getLogger("com.eip.app.observability.access");
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    accessLog.addAppender(appender);
    try {
      mvc.perform(get("/api/v1/session").header(HeaderTenantResolver.HEADER, tenantA.toString()))
          .andExpect(status().isOk());
    } finally {
      accessLog.detachAppender(appender);
    }
    ILoggingEvent event =
        appender.list.stream()
            .filter(e -> "api.request".equals(e.getMessage()))
            .reduce((first, second) -> second)
            .orElseThrow();
    // The structured log line carries the correlation key (tenantId + traceId) and bounded context.
    assertThat(event.getMDCPropertyMap())
        .containsEntry("method", "GET")
        .containsEntry("route", "/api/v1/session")
        .containsEntry("status", "200")
        .containsEntry("tenantId", tenantA.toString())
        .containsKey("durationMs")
        .containsKey("traceId");
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

  private static String insertOrg(UUID id, UUID tenantId, String name, String slug) {
    return "INSERT INTO core.organization (id, tenant_id, name, slug) VALUES ('"
        + id
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

  /**
   * Same as {@link #insertConnector(UUID, String, String)}, with an explicit {@code created_at}.
   */
  private static String insertConnector(UUID tenantId, String type, String name, String createdAt) {
    return "INSERT INTO core.connector (id, tenant_id, type, name, status, simulation, created_at) "
        + "VALUES ('"
        + UUID.randomUUID()
        + "', '"
        + tenantId
        + "', '"
        + type
        + "', '"
        + name
        + "', 'REGISTERED', true, '"
        + createdAt
        + "')";
  }

  private static String insertBusinessUnit(UUID id, UUID tenantId, UUID orgId, String name) {
    return "INSERT INTO core.business_unit (id, tenant_id, organization_id, name) VALUES ('"
        + id
        + "', '"
        + tenantId
        + "', '"
        + orgId
        + "', '"
        + name
        + "')";
  }

  private static String insertFrictionDefinition(UUID tenantId) {
    return "INSERT INTO analytics.metric_definition "
        + "(id, tenant_id, metric_key, name, purpose, formula, inputs, grain, caveats, gaming_risks) VALUES ('"
        + UUID.randomUUID()
        + "', '"
        + tenantId
        + "', 'engineering_friction', 'Engineering Friction (v0.1, experimental)', "
        + "'Where a team''s delivery time is lost.', "
        + "'friction = round(min(100, 100*waitingRatio + 30*reworkPerItem))', "
        + "'{\"signals\":[\"work_item_transitions\",\"blocked_time\",\"review_wait_time\",\"rework_count\"],\"grain\":\"team\"}'::jsonb, "
        + "'team', 'EXPERIMENTAL v0.1; team-level only.', 'Skipping reviews or splitting items understates it.')";
  }

  /** Seeds a team plus its computed friction read-model row (superuser; RLS-bypassed). */
  private static void seedTeamFriction(
      Statement st, UUID tenantId, UUID buId, String name, int score, String dominantCause)
      throws SQLException {
    UUID teamId = UUID.randomUUID();
    st.execute(
        "INSERT INTO core.team (id, tenant_id, business_unit_id, name, type) VALUES ('"
            + teamId
            + "', '"
            + tenantId
            + "', '"
            + buId
            + "', '"
            + name
            + "', 'STREAM_ALIGNED')");
    // Representative component values (Platform-shaped): flow_efficiency 0.1414, blocked 0.2424,
    // review-wait 0.5657 -> reviewWaitPct 57.
    st.execute(
        "INSERT INTO analytics.rm_team_friction_current "
            + "(id, tenant_id, team_id, metric_version, work_items, total_cycle_sec, active_sec, "
            + "waiting_sec, blocked_sec, review_wait_sec, rework_count, flow_efficiency, "
            + "blocked_ratio, review_wait_ratio, friction_score, dominant_cause) VALUES ('"
            + UUID.randomUUID()
            + "', '"
            + tenantId
            + "', '"
            + teamId
            + "', 'engineering_friction_v0.1', 3, 356400, 50400, 288000, 86400, 201600, 1, "
            + "0.1414, 0.2424, 0.5657, "
            + score
            + ", '"
            + dominantCause
            + "')");
  }
}
