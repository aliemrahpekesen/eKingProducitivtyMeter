/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eip.core.domain.DefaultUuidV7Generator;
import com.eip.core.error.ValidationException;
import com.eip.tenancy.api.ManageOrgStructureUseCase.OrganizationView;
import com.eip.tenancy.api.ManageTenantsUseCase.TenantView;
import com.eip.tenancy.application.OrgStructureService;
import com.eip.tenancy.application.TenantAdminService;
import com.eip.tenancy.audit.application.AuditService;
import com.eip.tenancy.audit.persistence.AuditEventRepository;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.context.TenantContextHolder;
import com.eip.tenancy.persistence.OrgStructureRepository;
import com.eip.tenancy.persistence.TenantAdminRepository;
import com.eip.tenancy.secrets.EnvelopeCipher;
import com.eip.tenancy.secrets.SecretsService;
import com.eip.tenancy.tx.TenantTransactionRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
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
import javax.sql.DataSource;
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
 * Module-level proof of the tenancy admin + secrets slice against real PostgreSQL as the
 * NOBYPASSRLS {@code eip_app} role: platform tenant onboarding with slug validation, tenant-scoped
 * structure creation with RLS isolation, and envelope-encrypted secret storage (ciphertext at rest,
 * in-process reveal, cross-tenant invisibility). No Spring context — plain constructor wiring. Also
 * proves DEBT-024 Wave 3B's {@code tenant.created}/{@code secret.revealed} audit retrofit, using
 * the same real {@code AuditService} Wave 3A shipped (mirrors {@code AuditChainIntegrationTest}'s
 * own construction, in a different file so that Wave 3A package stays untouched).
 */
@Tag("integration")
class TenancyAdminIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  private static final ObjectMapper JSON = new ObjectMapper();

  private static TenantAdminService tenants;
  private static OrgStructureService structure;
  private static SecretsService secrets;
  private static TenantTransactionRunner runner;

  @BeforeAll
  static void setUp() throws SQLException {
    POSTGRES.start();
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(
            "filesystem:"
                + Paths.get("../eip-app/src/main/resources/db/migration")
                    .toAbsolutePath()
                    .normalize())
        .load()
        .migrate();
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute("CREATE ROLE eip_app LOGIN PASSWORD 'eip_app_pw' NOBYPASSRLS");
      st.execute("GRANT USAGE ON SCHEMA core TO eip_app");
      st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA core TO eip_app");
      st.execute("GRANT USAGE ON SCHEMA audit TO eip_app");
      st.execute("GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA audit TO eip_app");
    }
    PGSimpleDataSource app = new PGSimpleDataSource();
    app.setUrl(POSTGRES.getJdbcUrl());
    app.setUser("eip_app");
    app.setPassword("eip_app_pw");
    DataSource dataSource = app;
    runner = new TenantTransactionRunner(new JdbcTransactionManager(dataSource), dataSource);
    JdbcClient jdbc = JdbcClient.create(dataSource);
    AuditEventRepository auditRepository =
        new AuditEventRepository(
            jdbc, dataSource, JSON, new DefaultUuidV7Generator(Clock.systemUTC()));
    AuditService auditService =
        new AuditService(auditRepository, Tracer.NOOP, new SimpleMeterRegistry());
    tenants = new TenantAdminService(new TenantAdminRepository(jdbc), runner, auditService);
    structure = new OrgStructureService(runner, new OrgStructureRepository(jdbc));
    secrets = new SecretsService(jdbc, EnvelopeCipher.newDek(), 1, auditService);
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
  void onboards_tenants_with_slug_validation_and_builds_isolated_structure() {
    TenantView a = tenants.create("Acme", "Acme"); // slug normalizes to lowercase
    TenantView b = tenants.create("Globex", "globex");
    assertThat(a.slug()).isEqualTo("acme");
    assertThat(tenants.list()).extracting(TenantView::slug).contains("acme", "globex");
    assertThatThrownBy(() -> tenants.create("Dup", "acme")).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> tenants.create("Bad", "not a slug!"))
        .isInstanceOf(ValidationException.class);

    TenantContextHolder.set(TenantContext.of(a.id()));
    UUID org = structure.createOrganization("Acme Corp", "acme-corp");
    UUID bu = structure.createBusinessUnit(org, "Engineering");
    structure.createTeam(bu, "Platform");
    List<OrganizationView> treeA = structure.structure();
    assertThat(treeA).hasSize(1);
    assertThat(treeA.get(0).businessUnits().get(0).teams()).hasSize(1);

    // Tenant B sees nothing of tenant A's structure (RLS).
    TenantContextHolder.set(TenantContext.of(b.id()));
    assertThat(structure.structure()).isEmpty();
  }

  @Test
  void secrets_are_ciphertext_at_rest_reveal_in_process_and_tenant_isolated() throws SQLException {
    TenantView owner = tenants.create("SecretCo", "secretco");
    TenantView other = tenants.create("OtherCo", "otherco");
    String token = "jira-token-cok-gizli-99";

    UUID secretId =
        runner.call(TenantContext.of(owner.id()), () -> secrets.store("connector:jira", token));

    // At rest: only ciphertext.
    try (Connection su =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = su.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT ciphertext FROM core.secret WHERE id = '" + secretId + "'")) {
      assertThat(rs.next()).isTrue();
      assertThat(new String(rs.getBytes(1), StandardCharsets.ISO_8859_1)).doesNotContain(token);
    }

    // In-process reveal round-trips under the owner...
    assertThat(runner.call(TenantContext.of(owner.id()), () -> secrets.reveal(secretId)))
        .isEqualTo(token);
    // ...and is invisible under any other tenant (RLS: no row -> loud failure, no leak).
    assertThatThrownBy(
            () -> runner.call(TenantContext.of(other.id()), () -> secrets.reveal(secretId)))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void tenant_creation_writes_exactly_one_tenant_created_audit_row() throws SQLException {
    TenantView created = tenants.create("AuditableCo", "auditableco");

    List<AuditRow> rows = queryAuditEvents(created.id(), "tenant.created");
    assertThat(rows).hasSize(1);
    AuditRow row = rows.get(0);
    assertThat(row.outcome()).isEqualTo("SUCCESS");
    JsonNode detail = readDetail(row);
    assertThat(detail.get("tenantId").asText()).isEqualTo(created.id().toString());
    assertThat(detail.get("slug").asText()).isEqualTo("auditableco");
    assertThat(detail.get("category").asText()).isEqualTo("admin");
    assertThat(detail.get("actorType").asText()).isEqualTo("SYSTEM");
  }

  @Test
  void secret_reveal_writes_a_secret_revealed_audit_row_with_no_plaintext_leak()
      throws SQLException {
    TenantView owner = tenants.create("AuditRevealCo", "auditrevealco");
    String plaintext = "connector-secret-plaintext-value";
    UUID secretId =
        runner.call(TenantContext.of(owner.id()), () -> secrets.store("connector:jira", plaintext));

    // Called from a READ-WRITE ambient transaction (tx.call): the audit INSERT can succeed.
    String revealed = runner.call(TenantContext.of(owner.id()), () -> secrets.reveal(secretId));
    assertThat(revealed).isEqualTo(plaintext);

    List<AuditRow> rows = queryAuditEvents(owner.id(), "secret.revealed");
    assertThat(rows).hasSize(1);
    AuditRow row = rows.get(0);
    assertThat(row.outcome()).isEqualTo("SUCCESS");
    JsonNode detail = readDetail(row);
    assertThat(detail.get("secretId").asText()).isEqualTo(secretId.toString());
    assertThat(detail.get("category").asText()).isEqualTo("secrets");
    assertThat(detail.get("actorType").asText()).isEqualTo("WORKER");
    // The plaintext value itself must never appear in the audit trail.
    assertThat(row.detailJson()).doesNotContain(plaintext);
  }

  @Test
  void secret_reveal_from_a_read_only_ambient_transaction_still_succeeds_but_writes_no_audit_row()
      throws SQLException {
    // reveal() never opens its own transaction (SecretsService javadoc) — it relies entirely on
    // the caller's already-active one for both the row SELECT and the secret.revealed audit
    // INSERT. RealConnectorSyncService — reveal()'s only production caller — resolves the secret
    // inside TenantTransactionRunner#readCurrent (READ-ONLY), reproduced here with runner.read.
    // Postgres rejects the audit INSERT under a read-only transaction; AuditService swallows that
    // failure (WARN log + eip.audit.write.failures counter) rather than breaking the caller, so
    // reveal() itself must still succeed even though no audit row is ever written. This pins down
    // a real, verified gap flagged in this wave's final report: a follow-up should switch that
    // call site to a read-write transaction so the audit row actually lands in production.
    TenantView owner = tenants.create("AuditRevealRoCo", "auditrevealroco");
    String plaintext = "another-connector-secret";
    UUID secretId =
        runner.call(TenantContext.of(owner.id()), () -> secrets.store("connector:jira", plaintext));

    String revealed = runner.read(TenantContext.of(owner.id()), () -> secrets.reveal(secretId));
    assertThat(revealed).isEqualTo(plaintext);

    assertThat(queryAuditEvents(owner.id(), "secret.revealed")).isEmpty();
  }

  private static List<AuditRow> queryAuditEvents(UUID tenantId, String action) throws SQLException {
    List<AuditRow> rows = new ArrayList<>();
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT outcome, detail::text AS detail_json FROM audit.audit_event"
                    + " WHERE tenant_id = ? AND action = ?")) {
      ps.setObject(1, tenantId);
      ps.setString(2, action);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(new AuditRow(rs.getString("outcome"), rs.getString("detail_json")));
        }
      }
    }
    return rows;
  }

  private static JsonNode readDetail(AuditRow row) {
    try {
      return JSON.readTree(row.detailJson());
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("failed to parse audit detail json: " + row.detailJson(), e);
    }
  }

  private record AuditRow(String outcome, String detailJson) {}
}
