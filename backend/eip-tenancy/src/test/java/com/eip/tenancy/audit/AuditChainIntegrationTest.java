/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.eip.core.domain.DefaultUuidV7Generator;
import com.eip.tenancy.api.ManageTenantsUseCase.TenantView;
import com.eip.tenancy.application.TenantAdminService;
import com.eip.tenancy.audit.api.AuditActorType;
import com.eip.tenancy.audit.api.AuditCategory;
import com.eip.tenancy.audit.api.AuditChainVerifierUseCase.VerifySweepResult;
import com.eip.tenancy.audit.api.AuditChainerUseCase.ChainSweepResult;
import com.eip.tenancy.audit.api.AuditEvent;
import com.eip.tenancy.audit.api.AuditOutcome;
import com.eip.tenancy.audit.application.AuditChainVerifier;
import com.eip.tenancy.audit.application.AuditChainer;
import com.eip.tenancy.audit.application.AuditRecordCanonicalizer;
import com.eip.tenancy.audit.application.AuditService;
import com.eip.tenancy.audit.persistence.AuditEventRepository;
import com.eip.tenancy.audit.persistence.AuditEventRepository.ChainedRow;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.context.TenantContextHolder;
import com.eip.tenancy.persistence.TenantAdminRepository;
import com.eip.tenancy.tx.TenantTransactionRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Map;
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
 * Module-level proof of the audit write path + hash chainer + chain verifier against real
 * PostgreSQL as the NOBYPASSRLS {@code eip_app} role (mirrors {@code TenancyAdminIntegrationTest}'s
 * harness: no Spring context, plain constructor wiring, real Flyway migrations incl. V13).
 */
@Tag("integration")
class AuditChainIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  private static TenantAdminService tenantAdmin;
  private static TenantTransactionRunner runner;
  private static AuditEventRepository repository;
  private static AuditRecordCanonicalizer canonicalizer;
  private static AuditService auditService;
  private static AuditChainer chainer;
  private static AuditChainVerifier verifier;
  private static SimpleMeterRegistry registry;

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
    runner = new TenantTransactionRunner(new JdbcTransactionManager(app), app);
    JdbcClient jdbc = JdbcClient.create(app);
    tenantAdmin = new TenantAdminService(new TenantAdminRepository(jdbc));
    ObjectMapper mapper = new ObjectMapper();
    repository =
        new AuditEventRepository(jdbc, app, mapper, new DefaultUuidV7Generator(Clock.systemUTC()));
    canonicalizer = new AuditRecordCanonicalizer(mapper);
    registry = new SimpleMeterRegistry();
    auditService = new AuditService(repository, Tracer.NOOP, registry);
    chainer = new AuditChainer(tenantAdmin, runner, repository, canonicalizer, registry, 500);
    verifier =
        new AuditChainVerifier(tenantAdmin, runner, repository, canonicalizer, registry, 50_000);
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
  void chainer_assigns_prev_hash_and_hash_in_order_with_tenant_specific_genesis() {
    TenantView tenant = tenantAdmin.create("ChainCo", "chainco");
    writeEvents(tenant.id(), 5);

    ChainSweepResult sweep = chainer.chainPendingRows();
    assertThat(sweep.tenantsWithWork()).isEqualTo(1);
    assertThat(sweep.rowsChained()).isEqualTo(5);

    List<ChainedRow> chained =
        runner.call(TenantContext.of(tenant.id()), () -> repository.findChainedOrdered(100));
    assertThat(chained).hasSize(5);
    assertThat(
            runner.call(TenantContext.of(tenant.id()), () -> repository.findUnchainedOrdered(100)))
        .isEmpty();

    // Genesis: the first row's prev_hash is this tenant's fixed genesis anchor, not an all-zero or
    // shared constant.
    assertThat(chained.get(0).prevHash()).isEqualTo(canonicalizer.genesisHash(tenant.id()));
    // Chain links: row K's prev_hash is row K-1's hash.
    for (int i = 1; i < chained.size(); i++) {
      assertThat(chained.get(i).prevHash()).isEqualTo(chained.get(i - 1).hash());
    }
    // Every stored hash matches independent recomputation from the row's own fields.
    byte[] expectedPrev = canonicalizer.genesisHash(tenant.id());
    for (ChainedRow row : chained) {
      assertThat(row.hash())
          .isEqualTo(canonicalizer.computeHash(expectedPrev, tenant.id(), row.fields()));
      expectedPrev = row.hash();
    }
  }

  @Test
  void verifier_passes_a_clean_chain_and_flags_tampering() throws SQLException {
    TenantView tenant = tenantAdmin.create("VerifyCo", "verifyco");
    writeEvents(tenant.id(), 4);
    chainer.chainPendingRows();

    double failuresBefore = counterValue("audit-verify");
    VerifySweepResult clean = verifier.verifyChains();
    assertThat(clean.tenantsVerified()).isGreaterThanOrEqualTo(1);
    assertThat(clean.tenantsTampered()).isZero();
    assertThat(counterValue("audit-verify")).isEqualTo(failuresBefore);

    // Tamper: mutate a chained row's action directly, bypassing the app entirely (proves the
    // verifier catches tampering done straight against the table, not just through the app).
    List<ChainedRow> chained =
        runner.call(TenantContext.of(tenant.id()), () -> repository.findChainedOrdered(100));
    UUID tamperedId = chained.get(1).id();
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute(
          "UPDATE audit.audit_event SET action = 'tampered.action' WHERE id = '"
              + tamperedId
              + "'");
    }

    VerifySweepResult afterTamper = verifier.verifyChains();
    assertThat(afterTamper.tenantsTampered()).isEqualTo(1);
    assertThat(counterValue("audit-verify")).isEqualTo(failuresBefore + 1);
  }

  @Test
  void chains_are_independent_across_tenants() {
    TenantView tenantA = tenantAdmin.create("IsoCoA", "isocoa");
    TenantView tenantB = tenantAdmin.create("IsoCoB", "isocob");
    writeEvents(tenantA.id(), 3);
    writeEvents(tenantB.id(), 2);

    ChainSweepResult sweep = chainer.chainPendingRows();
    assertThat(sweep.rowsChained()).isEqualTo(5);

    List<ChainedRow> chainedA =
        runner.call(TenantContext.of(tenantA.id()), () -> repository.findChainedOrdered(100));
    List<ChainedRow> chainedB =
        runner.call(TenantContext.of(tenantB.id()), () -> repository.findChainedOrdered(100));
    assertThat(chainedA).hasSize(3);
    assertThat(chainedB).hasSize(2);

    // Each tenant's chain starts at its OWN genesis, not the other tenant's.
    assertThat(chainedA.get(0).prevHash()).isEqualTo(canonicalizer.genesisHash(tenantA.id()));
    assertThat(chainedB.get(0).prevHash()).isEqualTo(canonicalizer.genesisHash(tenantB.id()));
    assertThat(chainedA.get(0).prevHash()).isNotEqualTo(chainedB.get(0).prevHash());

    // Both tenants' chains verify clean and independently.
    VerifySweepResult verifyResult = verifier.verifyChains();
    assertThat(verifyResult.tenantsTampered()).isZero();
  }

  private static void writeEvents(UUID tenantId, int count) {
    for (int i = 0; i < count; i++) {
      UUID actorMemberId = UUID.randomUUID();
      int sequence = i;
      runner.run(
          TenantContext.of(tenantId),
          () ->
              auditService.record(
                  new AuditEvent(
                      AuditCategory.AUTH,
                      "auth.login.success",
                      AuditOutcome.SUCCESS,
                      AuditActorType.USER,
                      actorMemberId,
                      Map.of("target", Map.of("type", "session", "id", "s-" + sequence)))));
    }
  }

  private static double counterValue(String job) {
    Counter counter = registry.find("eip.job.failures").tag("job", job).counter();
    return counter == null ? 0 : counter.count();
  }
}
