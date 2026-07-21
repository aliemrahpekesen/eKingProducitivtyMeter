/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.eip.connectors.bitbucket.BitbucketConnector;
import com.eip.ingestion.api.SyncMode;
import com.eip.ingestion.application.ConnectorRegistry;
import com.eip.ingestion.application.RealConnectorSyncService;
import com.eip.ingestion.persistence.ConnectorAdminRepository;
import com.eip.ingestion.persistence.ConnectorCheckpointRepository;
import com.eip.ingestion.persistence.RawPayloadCodec;
import com.eip.ingestion.persistence.StagingRawRepository;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.context.TenantContextHolder;
import com.eip.tenancy.secrets.EnvelopeCipher;
import com.eip.tenancy.secrets.SecretsService;
import com.eip.tenancy.tx.TenantTransactionRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves the per-connector incremental-capability flag (DEBT-018 item 3) against real PostgreSQL
 * and a WireMock'd Bitbucket: an {@code AUTO}-mode sync with an existing checkpoint still runs a
 * FULL sync (never {@code INCREMENTAL}) for a connector whose {@link
 * com.eip.connectors.spi.Connector#incrementalSupported()} is {@code false} — Bitbucket never reads
 * {@link com.eip.connectors.spi.SyncContext#cursor()}, so before this fix the checkpoint's
 * bookkeeping mislabeled the run as incremental (stamping {@code last_incremental_sync_at}) even
 * though the connector fetched (and fetch-kind-tagged) everything as FULL.
 */
@Tag("integration")
class RealConnectorSyncServiceIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  private static final WireMockServer BITBUCKET =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());

  private static final UUID TENANT = UUID.randomUUID();

  private static RealConnectorSyncService syncService;
  private static TenantTransactionRunner runner;
  private static JdbcClient jdbc;
  private static UUID connectorId;

  @BeforeAll
  static void setUp() throws SQLException {
    BITBUCKET.start();
    BITBUCKET.stubFor(
        get(urlPathEqualTo("/2.0/repositories/acme"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"values\":[{\"slug\":\"svc\"}]}")));
    BITBUCKET.stubFor(
        get(urlPathEqualTo("/2.0/repositories/acme/svc/pullrequests"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"values\":[{\"id\":1,\"title\":\"t\","
                            + "\"source\":{\"branch\":{\"name\":\"b\"}},\"state\":\"OPEN\","
                            + "\"created_on\":\"2026-01-01T00:00:00+00:00\"}]}")));
    BITBUCKET.stubFor(
        get(urlPathEqualTo("/2.0/repositories/acme/svc/pullrequests/1/activity"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"values\":[]}")));

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
      for (String schema : new String[] {"core", "staging"}) {
        st.execute("GRANT USAGE ON SCHEMA " + schema + " TO eip_app");
        st.execute(
            "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA "
                + schema
                + " TO eip_app");
      }
      st.execute(
          "INSERT INTO core.tenant (id, name, slug) VALUES ('" + TENANT + "', 'Acme', 'acme')");
    }

    PGSimpleDataSource app = new PGSimpleDataSource();
    app.setUrl(POSTGRES.getJdbcUrl());
    app.setUser("eip_app");
    app.setPassword("eip_app_pw");
    DataSource dataSource = app;

    runner = new TenantTransactionRunner(new JdbcTransactionManager(dataSource), dataSource);
    jdbc = JdbcClient.create(dataSource);
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    ObjectMapper mapper = new ObjectMapper();

    ConnectorAdminRepository connectors = new ConnectorAdminRepository(jdbc, mapper);
    SecretsService secrets = new SecretsService(jdbc, EnvelopeCipher.newDek(), 1);
    TenantContextHolder.set(TenantContext.of(TENANT));
    connectorId =
        runner.callCurrent(
            () -> {
              UUID secretId = secrets.store("connector:bitbucket:test", "app-password-1");
              return connectors
                  .insert(
                      "bitbucket",
                      "Acme Bitbucket",
                      Map.of(
                          "baseUrl", BITBUCKET.baseUrl(),
                          "username", "svc-acme",
                          "workspace", "acme"),
                      secretId,
                      false)
                  .id();
            });
    TenantContextHolder.clear();

    syncService =
        new RealConnectorSyncService(
            runner,
            connectors,
            new ConnectorRegistry(java.util.List.of(new BitbucketConnector())),
            secrets,
            new StagingRawRepository(jdbc, jdbcTemplate),
            new RawPayloadCodec(mapper),
            new ConnectorCheckpointRepository(jdbc, mapper));
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
    BITBUCKET.stop();
  }

  @AfterEach
  void clearHolder() {
    TenantContextHolder.clear();
  }

  @Test
  void auto_mode_stays_full_for_a_connector_that_does_not_support_incremental()
      throws SQLException {
    TenantContextHolder.set(TenantContext.of(TENANT));

    // 1) First AUTO sync: no checkpoint yet, so it is full regardless of the flag — baseline.
    syncService.sync(connectorId, SyncMode.AUTO);
    assertThat(lastSyncTimestamps())
        .containsEntry("full", true)
        .containsEntry("incremental", false);
    assertThat(fetchKindsStaged()).containsExactly("full");

    // 2) Second AUTO sync: a checkpoint now exists, but Bitbucket#incrementalSupported() is false
    // (it never reads SyncContext#cursor()) — DEBT-018 item 3 forces this to stay FULL instead of
    // silently flipping to "incremental" bookkeeping for a run that fetched everything anyway.
    syncService.sync(connectorId, SyncMode.AUTO);
    assertThat(lastSyncTimestamps())
        .as("a non-incremental-capable connector never stamps last_incremental_sync_at")
        .containsEntry("full", true)
        .containsEntry("incremental", false);
    assertThat(fetchKindsStaged()).containsExactly("full");
  }

  // Every query below runs through TenantTransactionRunner (never bare jdbc calls): both target
  // tables are RLS-FORCED and the tenant_isolation policy evaluates
  // current_setting('app.tenant_id')
  // — unset outside a TenantTransactionRunner-managed transaction, which fails the query outright.
  private java.util.Map<String, Boolean> lastSyncTimestamps() {
    return runner.read(
        TenantContext.of(TENANT),
        () ->
            jdbc.sql(
                    "SELECT last_full_sync_at IS NOT NULL AS full, last_incremental_sync_at IS NOT"
                        + " NULL AS incremental FROM core.connector_checkpoint WHERE connector_id ="
                        + " :id")
                .param("id", connectorId)
                .query(
                    (rs, rowNum) ->
                        Map.of(
                            "full", rs.getBoolean("full"),
                            "incremental", rs.getBoolean("incremental")))
                .single());
  }

  private java.util.List<String> fetchKindsStaged() {
    return runner.read(
        TenantContext.of(TENANT),
        () ->
            jdbc.sql(
                    "SELECT DISTINCT fetch_kind FROM staging.raw_bitbucket WHERE connector_id = :id"
                        + " ORDER BY fetch_kind")
                .param("id", connectorId)
                .query(String.class)
                .list());
  }
}
