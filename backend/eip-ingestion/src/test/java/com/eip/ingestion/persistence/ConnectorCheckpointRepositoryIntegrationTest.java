/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.tx.TenantTransactionRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
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
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Module-level proof of {@link ConnectorCheckpointRepository} against real PostgreSQL as the
 * NOBYPASSRLS {@code eip_app} role (mirrors {@code OutboxIntegrationTest}'s harness — no Spring
 * context, every component hand-built): a first read finds no checkpoint, an incremental upsert
 * makes it findable with the advanced cursor and stamps {@code last_incremental_sync_at} (not
 * {@code last_full_sync_at}), and a connector whose sync failed before reaching the checkpoint
 * upsert (the caller simply never calls it) leaves the prior cursor untouched.
 */
@Tag("integration")
class ConnectorCheckpointRepositoryIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  private static final UUID TENANT_A = UUID.randomUUID();

  private static TenantTransactionRunner runner;
  private static ConnectorCheckpointRepository checkpoints;
  private static UUID connectorId;

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
      st.execute("INSERT INTO core.tenant (id, name, slug) VALUES ('" + TENANT_A + "', 'A', 'a')");

      connectorId = UUID.randomUUID();
      st.execute(
          "INSERT INTO core.connector (id, tenant_id, type, name, config, status, simulation)"
              + " VALUES ('"
              + connectorId
              + "', '"
              + TENANT_A
              + "', 'jira', 'Corp Jira', '{}'::jsonb, 'ACTIVE', false)");
    }

    PGSimpleDataSource app = new PGSimpleDataSource();
    app.setUrl(POSTGRES.getJdbcUrl());
    app.setUser("eip_app");
    app.setPassword("eip_app_pw");
    DataSource dataSource = app;

    runner = new TenantTransactionRunner(new JdbcTransactionManager(dataSource), dataSource);
    JdbcClient jdbc = JdbcClient.create(dataSource);
    checkpoints = new ConnectorCheckpointRepository(jdbc, new ObjectMapper());
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void checkpoint_upsert_lifecycle_advances_only_on_success() throws SQLException {
    TenantContext tenant = TenantContext.of(TENANT_A);
    String stream = ConnectorCheckpointRepository.DEFAULT_STREAM;

    // 1) No checkpoint yet: find returns empty (first/full sync).
    Optional<Map<String, String>> initial =
        runner.read(tenant, () -> checkpoints.find(connectorId, stream));
    assertThat(initial).isEmpty();

    // 2) A full sync's checkpoint upsert stamps last_full_sync_at.
    String firstCursor = Instant.parse("2026-01-05T09:00:00Z").toString();
    runner.run(
        tenant,
        () -> checkpoints.upsert(connectorId, stream, Map.of("updatedSince", firstCursor), true));
    assertThat(runner.read(tenant, () -> checkpoints.find(connectorId, stream)))
        .contains(Map.of("updatedSince", firstCursor));
    assertThat(lastSyncTimestamps(connectorId))
        .containsEntry("full", true)
        .containsEntry("incremental", false);

    // 3) An incremental sync's checkpoint upsert advances the cursor and stamps
    // last_incremental_sync_at instead.
    String secondCursor = Instant.parse("2026-01-05T10:00:00Z").toString();
    runner.run(
        tenant,
        () -> checkpoints.upsert(connectorId, stream, Map.of("updatedSince", secondCursor), false));
    assertThat(runner.read(tenant, () -> checkpoints.find(connectorId, stream)))
        .contains(Map.of("updatedSince", secondCursor));
    assertThat(lastSyncTimestamps(connectorId))
        .containsEntry("full", true)
        .containsEntry("incremental", true);

    // 4) A "failed sync" simply never calls upsert (RealConnectorSyncService's contract: the
    // checkpoint upsert lives in the post-fetch transaction, unreached on a source failure) — the
    // cursor is left exactly as the last successful advance.
    assertThat(runner.read(tenant, () -> checkpoints.find(connectorId, stream)))
        .contains(Map.of("updatedSince", secondCursor));
  }

  private static Map<String, Boolean> lastSyncTimestamps(UUID connectorId) throws SQLException {
    try (Connection su =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = su.createStatement();
        var rs =
            st.executeQuery(
                "SELECT last_full_sync_at IS NOT NULL AS full, last_incremental_sync_at IS NOT"
                    + " NULL AS incremental FROM core.connector_checkpoint WHERE connector_id = '"
                    + connectorId
                    + "'")) {
      assertThat(rs.next()).isTrue();
      return Map.of("full", rs.getBoolean("full"), "incremental", rs.getBoolean("incremental"));
    }
  }
}
