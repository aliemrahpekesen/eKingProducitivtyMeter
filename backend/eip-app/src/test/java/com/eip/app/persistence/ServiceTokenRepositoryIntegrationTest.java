/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.eip.app.persistence.ServiceTokenRepository.AuthRecord;
import com.eip.app.persistence.ServiceTokenRepository.ServiceTokenRow;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Module-level proof of {@link ServiceTokenRepository} against real PostgreSQL as the NOBYPASSRLS
 * {@code eip_app} role (mirrors {@code ConnectorCheckpointRepositoryIntegrationTest}'s harness — no
 * Spring context, every component hand-built). {@code core.service_token} carries no RLS (ADR-025),
 * so — unlike most repository ITs in this codebase — no {@code TenantTransactionRunner}/GUC binding
 * is needed here: every method under test filters explicitly by {@code tenant_id}, which this test
 * exercises directly.
 */
@Tag("integration")
class ServiceTokenRepositoryIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  private static final UUID TENANT_A = UUID.randomUUID();
  private static final UUID TENANT_B = UUID.randomUUID();

  private static ServiceTokenRepository repository;

  @BeforeAll
  static void setUp() throws SQLException {
    POSTGRES.start();
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
      st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA core TO eip_app");
      st.execute("INSERT INTO core.tenant (id, name, slug) VALUES ('" + TENANT_A + "', 'A', 'a')");
      st.execute("INSERT INTO core.tenant (id, name, slug) VALUES ('" + TENANT_B + "', 'B', 'b')");
    }

    PGSimpleDataSource app = new PGSimpleDataSource();
    app.setUrl(POSTGRES.getJdbcUrl());
    app.setUser("eip_app");
    app.setPassword("eip_app_pw");
    DataSource dataSource = app;

    repository =
        new ServiceTokenRepository(JdbcClient.create(dataSource), dataSource, UUID::randomUUID);
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void insertAndFindByHash_returnsTheAuthRecord() {
    UUID id =
        repository.insert(
            TENANT_A,
            "ci-token",
            "abcd1234",
            "hash-insert-find",
            "ANALYST",
            List.of(),
            null,
            futureExpiry());

    Optional<AuthRecord> found = repository.findByHash("hash-insert-find");

    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(id);
    assertThat(found.get().tenantId()).isEqualTo(TENANT_A);
    assertThat(found.get().role()).isEqualTo("ANALYST");
    assertThat(found.get().permissionSubset()).isEmpty();
  }

  @Test
  void findByHash_excludesAnExpiredToken() {
    repository.insert(
        TENANT_A,
        "expired",
        "expired1",
        "hash-expired",
        "VIEWER",
        List.of(),
        null,
        Instant.now().minus(1, ChronoUnit.DAYS));

    assertThat(repository.findByHash("hash-expired")).isEmpty();
  }

  @Test
  void findByHash_excludesARevokedToken() {
    UUID id =
        repository.insert(
            TENANT_A,
            "revoked",
            "revoked1",
            "hash-revoked",
            "VIEWER",
            List.of(),
            null,
            futureExpiry());
    assertThat(repository.findByHash("hash-revoked")).isPresent();

    int updated = repository.revokeForTenant(id, TENANT_A);

    assertThat(updated).isEqualTo(1);
    assertThat(repository.findByHash("hash-revoked")).isEmpty();
    // Revoking again is a no-op (already revoked), not a second successful update.
    assertThat(repository.revokeForTenant(id, TENANT_A)).isZero();
  }

  @Test
  void revokeForTenant_neverRevokesAnotherTenantsToken() {
    UUID id =
        repository.insert(
            TENANT_A,
            "cross-tenant",
            "crosst01",
            "hash-cross-tenant",
            "VIEWER",
            List.of(),
            null,
            futureExpiry());

    int updated = repository.revokeForTenant(id, TENANT_B);

    assertThat(updated).isZero();
    assertThat(repository.findByHash("hash-cross-tenant")).isPresent();
  }

  @Test
  void touchLastUsed_stampsTheTimestamp() {
    UUID id =
        repository.insert(
            TENANT_A, "touch", "touch123", "hash-touch", "VIEWER", List.of(), null, futureExpiry());
    assertThat(rowFor(id).lastUsedAt()).isNull();

    repository.touchLastUsed(id);

    assertThat(rowFor(id).lastUsedAt()).isNotNull();
  }

  @Test
  void listForTenant_isScopedToItsOwnTenantOnly() {
    UUID idA =
        repository.insert(
            TENANT_A,
            "a-token",
            "atoken01",
            "hash-list-a",
            "VIEWER",
            List.of(),
            null,
            futureExpiry());
    UUID idB =
        repository.insert(
            TENANT_B,
            "b-token",
            "btoken01",
            "hash-list-b",
            "VIEWER",
            List.of(),
            null,
            futureExpiry());

    assertThat(repository.listForTenant(TENANT_A)).extracting(ServiceTokenRow::id).contains(idA);
    assertThat(repository.listForTenant(TENANT_A))
        .extracting(ServiceTokenRow::id)
        .doesNotContain(idB);
  }

  @Test
  void platformScopedTokens_areListedAndRevokedIndependentlyOfAnyTenant() {
    UUID id =
        repository.insert(
            null,
            "platform-ci",
            "platform",
            "hash-platform",
            "PLATFORM_ADMIN",
            List.of(),
            null,
            futureExpiry());

    assertThat(repository.listPlatformScoped()).extracting(ServiceTokenRow::id).contains(id);
    assertThat(repository.listForTenant(TENANT_A))
        .extracting(ServiceTokenRow::id)
        .doesNotContain(id);

    assertThat(repository.revokePlatformScoped(id)).isEqualTo(1);
    assertThat(repository.findByHash("hash-platform")).isEmpty();
  }

  @Test
  void permissionSubset_roundTripsThroughTheTextArrayColumn() {
    UUID id =
        repository.insert(
            TENANT_A,
            "subset",
            "subset12",
            "hash-subset",
            "ANALYST",
            List.of("DASHBOARD_VIEW", "REPORT_GENERATE"),
            null,
            futureExpiry());

    Optional<AuthRecord> found = repository.findByHash("hash-subset");

    assertThat(found).isPresent();
    assertThat(found.get().permissionSubset())
        .containsExactlyInAnyOrder("DASHBOARD_VIEW", "REPORT_GENERATE");
    assertThat(rowFor(id).permissionSubset())
        .containsExactlyInAnyOrder("DASHBOARD_VIEW", "REPORT_GENERATE");
  }

  private static Instant futureExpiry() {
    return Instant.now().plus(90, ChronoUnit.DAYS);
  }

  private static ServiceTokenRow rowFor(UUID id) {
    return repository.listForTenant(TENANT_A).stream()
        .filter(r -> r.id().equals(id))
        .findFirst()
        .orElseThrow();
  }
}
