/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.eip.app.application.RolePermissionOverrideService.OverrideCommand;
import com.eip.app.application.RolePermissionOverrideService.RolePermissionsView;
import com.eip.app.persistence.RolePermissionOverrideRepository;
import com.eip.core.domain.DefaultUuidV7Generator;
import com.eip.tenancy.audit.application.AuditService;
import com.eip.tenancy.audit.persistence.AuditEventRepository;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.context.TenantContextHolder;
import com.eip.tenancy.rbac.Permission;
import com.eip.tenancy.rbac.Role;
import com.eip.tenancy.tx.TenantTransactionRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Module-level proof of {@link RolePermissionOverrideService}'s DEBT-024 Wave 3B audit retrofit
 * against real PostgreSQL as the NOBYPASSRLS {@code eip_app} role (mirrors {@code
 * TenancyAdminIntegrationTest}'s harness: no Spring context, plain constructor wiring, real Flyway
 * migrations). {@code applyOverrides} already runs inside its own tenant-bound READ-WRITE
 * transaction ({@code TenantTransactionRunner#callCurrent}), so the audit write joins that same
 * transaction directly — no extra wrapping needed (unlike {@code ServiceTokenService}/{@code
 * TenantAdminService}, which have no ambient transaction of their own).
 */
@Tag("integration")
class RolePermissionOverrideAuditIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  private static UUID tenantId;
  private static RolePermissionOverrideService service;

  @BeforeAll
  static void setUp() throws SQLException {
    POSTGRES.start();
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
    tenantId = UUID.randomUUID();
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute("CREATE ROLE eip_app LOGIN PASSWORD 'eip_app_pw' NOBYPASSRLS");
      st.execute("GRANT USAGE ON SCHEMA core TO eip_app");
      st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA core TO eip_app");
      st.execute("GRANT USAGE ON SCHEMA audit TO eip_app");
      st.execute("GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA audit TO eip_app");
      st.execute(
          "INSERT INTO core.tenant (id, name, slug) VALUES ('"
              + tenantId
              + "', 'Role Override Co', 'roleoverrideco')");
    }
    PGSimpleDataSource app = new PGSimpleDataSource();
    app.setUrl(POSTGRES.getJdbcUrl());
    app.setUser("eip_app");
    app.setPassword("eip_app_pw");
    TenantTransactionRunner runner =
        new TenantTransactionRunner(new JdbcTransactionManager(app), app);
    JdbcClient jdbc = JdbcClient.create(app);
    ObjectMapper mapper = new ObjectMapper();
    AuditEventRepository auditRepository =
        new AuditEventRepository(jdbc, app, mapper, new DefaultUuidV7Generator(Clock.systemUTC()));
    AuditService auditService =
        new AuditService(auditRepository, Tracer.NOOP, new SimpleMeterRegistry());
    service =
        new RolePermissionOverrideService(
            runner, new RolePermissionOverrideRepository(jdbc), auditService);
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @AfterEach
  void clearHolder() {
    TenantContextHolder.clear();
  }

  @Test
  void applyingAnOverrideWritesARolePermissionOverriddenAuditRow() throws SQLException {
    TenantContextHolder.set(TenantContext.of(tenantId));

    // ANALYST does not hold AUDIT_READ by default (Role.java) — granting it is a real change.
    RolePermissionsView view =
        service.applyOverrides(
            Role.ANALYST.name(), List.of(new OverrideCommand(Permission.AUDIT_READ.name(), true)));
    assertThat(view.effectivePermissions()).contains(Permission.AUDIT_READ.wireId());

    List<String> detailJsons = queryDetailJsons("role.permission.overridden");
    assertThat(detailJsons).hasSize(1);
    JsonNode detail = readJson(detailJsons.get(0));
    assertThat(detail.get("role").asText()).isEqualTo("ANALYST");
    assertThat(detail.get("category").asText()).isEqualTo("admin");
    assertThat(detail.get("actorType").asText()).isEqualTo("SYSTEM");
    JsonNode change = detail.get("changes").get(0);
    assertThat(change.get("permission").asText()).isEqualTo(Permission.AUDIT_READ.wireId());
    assertThat(change.get("granted").asBoolean()).isTrue();
  }

  private static JsonNode readJson(String json) {
    try {
      return new ObjectMapper().readTree(json);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("failed to parse audit detail json: " + json, e);
    }
  }

  private static List<String> queryDetailJsons(String action) throws SQLException {
    List<String> rows = new ArrayList<>();
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT detail::text FROM audit.audit_event WHERE tenant_id = ? AND action = ?")) {
      ps.setObject(1, tenantId);
      ps.setString(2, action);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(rs.getString(1));
        }
      }
    }
    return rows;
  }
}
