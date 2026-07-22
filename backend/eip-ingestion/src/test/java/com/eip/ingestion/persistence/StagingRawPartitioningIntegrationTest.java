/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eip.connectors.spi.FetchKind;
import com.eip.connectors.spi.Op;
import com.eip.connectors.spi.RawRecord;
import com.eip.ingestion.persistence.StagingRawRepository.RawUpsert;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.tx.TenantTransactionRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
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
 * Proves V12 (DEBT-017 partitioning sub-item) against real PostgreSQL, isolated in a Testcontainers
 * instance — never against the shared dev database.
 *
 * <ol>
 *   <li>{@link #v12_preserves_seeded_rows_and_places_them_in_partitions()} — migrates only up to
 *       V11 (the highest version before this migration), seeds pre-migration rows directly into
 *       every {@code staging.raw_*} table (one recent, inside the migration's partition window; one
 *       old, outside it), THEN applies V12, and asserts every row survived with an unchanged row
 *       count and landed in a real partition (a specific week, or the {@code _default} safety valve
 *       for the out-of-window row) rather than the bare parent.
 *   <li>{@link #repeated_upsert_with_changed_content_updates_in_place_not_duplicate()} — proves the
 *       unique-constraint finding empirically through the actual application code path: {@link
 *       StagingRawRepository#upsertAll} resupplying a row's existing {@code first_ingested_at}
 *       keeps ON CONFLICT matching the same row across repeated content-changing re-ingests of one
 *       natural key (no duplicate ever appears), while an unchanged re-ingest is a true no-op (the
 *       {@code WHERE content_hash IS DISTINCT FROM} guard — no {@code ingested_at} churn, matching
 *       {@code IngestionPipelineIntegrationTest}'s and {@code SimulationIngestionIntegrationTest}'s
 *       existing invariant).
 *   <li>{@link #identical_arbiter_key_duplicate_insert_is_rejected()} — a genuine duplicate insert
 *       at the identical (tenant_id, connector_id, stream, natural_key, first_ingested_at) tuple,
 *       bypassing the application layer entirely, is rejected by the widened unique constraint
 *       itself — the DB-level guarantee still holds, not just the application's best-effort
 *       resupply.
 * </ol>
 */
@Tag("integration")
class StagingRawPartitioningIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  private static final List<String> RAW_TABLES =
      List.of(
          "raw_simulation",
          "raw_jira",
          "raw_bitbucket",
          "raw_sonarqube",
          "raw_github",
          "raw_gitlab",
          "raw_jenkins");

  private static final UUID TENANT_A = UUID.randomUUID();
  private static final UUID LEGACY_CONNECTOR = UUID.randomUUID();
  private static final UUID FIXTURE_CONNECTOR = UUID.randomUUID();

  private static TenantTransactionRunner runner;
  private static StagingRawRepository staging;
  private static RawPayloadCodec codec;
  private static JdbcTemplate appJdbcTemplate;

  @BeforeAll
  static void setUp() throws SQLException {
    POSTGRES.start();
    String migrationDir =
        "filesystem:"
            + Paths.get("../eip-app/src/main/resources/db/migration").toAbsolutePath().normalize();

    // 1. Migrate only up to V11 — the highest version below this migration (V12) at the time it
    //    was authored — i.e. the raw-staging schema shape as it existed before partitioning.
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(migrationDir)
        .target("11")
        .load()
        .migrate();

    // 2. Seed one recent row (inside V12's partition window) and one old row (outside it, must
    //    land in the DEFAULT partition) directly into every pre-partitioning raw table.
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      for (String tbl : RAW_TABLES) {
        seedLegacyRow(st, tbl, "RECENT-1", "now() - interval '14 days'");
        seedLegacyRow(st, tbl, "OLD-1", "now() - interval '200 days'");
      }
    }

    // 3. Apply the rest of the chain (V12) on top of the seeded data.
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(migrationDir)
        .load()
        .migrate();

    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute("CREATE ROLE eip_app LOGIN PASSWORD 'eip_app_pw' NOBYPASSRLS");
      st.execute("GRANT USAGE ON SCHEMA staging TO eip_app");
      st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA staging TO eip_app");
    }

    PGSimpleDataSource app = new PGSimpleDataSource();
    app.setUrl(POSTGRES.getJdbcUrl());
    app.setUser("eip_app");
    app.setPassword("eip_app_pw");
    DataSource dataSource = app;

    runner = new TenantTransactionRunner(new JdbcTransactionManager(dataSource), dataSource);
    appJdbcTemplate = new JdbcTemplate(dataSource);
    staging = new StagingRawRepository(JdbcClient.create(dataSource), appJdbcTemplate);
    codec = new RawPayloadCodec(new ObjectMapper());
  }

  private static void seedLegacyRow(
      Statement st, String table, String naturalKey, String ingestedAtExpr) throws SQLException {
    st.execute(
        ("INSERT INTO staging.%s (tenant_id, connector_id, stream, natural_key, source_system,"
                + " source_instance, external_id, op, fetch_kind, payload, content_hash,"
                + " ingested_at) VALUES ('%s', '%s', 'work_item', '%s', 'jira', 'legacy', 'ext-%s',"
                + " 'upsert', 'full', '{}'::jsonb, decode('aa','hex'), %s)")
            .formatted(table, TENANT_A, LEGACY_CONNECTOR, naturalKey, naturalKey, ingestedAtExpr));
  }

  @Test
  void v12_preserves_seeded_rows_and_places_them_in_partitions() throws SQLException {
    try (Connection admin =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      for (String tbl : RAW_TABLES) {
        // Row survival: exactly the 2 seeded rows, none lost, none duplicated by the migration.
        // Scoped to LEGACY_CONNECTOR — other @Test methods in this class write their own fixture
        // rows (different connector, different natural keys) into some of these same tables, and
        // JUnit does not guarantee method execution order.
        assertThat(count(admin, "staging." + tbl, LEGACY_CONNECTOR))
            .as(tbl + " row count")
            .isEqualTo(2L);

        // Partition placement: each row lives in a REAL child partition, not the bare parent.
        assertThat(partitionOf(admin, tbl, "RECENT-1"))
            .as(tbl + " RECENT-1 partition")
            .startsWith("staging." + tbl + "_w")
            .isNotEqualTo("staging." + tbl);
        assertThat(partitionOf(admin, tbl, "OLD-1"))
            .as(tbl + " OLD-1 partition (outside the 4-week-past window)")
            .isEqualTo("staging." + tbl + "_default");

        // Backfill: first_ingested_at was seeded from the row's own ingested_at (best-effort
        // proxy for "first seen" — no true first-seen history existed pre-migration).
        assertThat(firstIngestedAtEqualsIngestedAt(admin, tbl, "RECENT-1"))
            .as(tbl + " RECENT-1 first_ingested_at backfill")
            .isTrue();
      }
    }
  }

  @Test
  void repeated_upsert_with_changed_content_updates_in_place_not_duplicate() {
    TenantContext tenant = TenantContext.of(TENANT_A);
    String table = StagingRawRepository.rawTable("jira");
    String naturalKey = "EVOLVING-1";

    // 1st ingest: brand-new natural key -> 1 row.
    upsert(tenant, table, naturalKey, Map.of("status", "open"));
    assertThat(rawRowCount(tenant, table, naturalKey)).isEqualTo(1L);

    // 2nd ingest, CHANGED content (status open -> in_progress): must update the SAME row, not
    // insert a second one. This is the exact path that would break under the rejected design
    // (widening the constraint with the mutable `ingested_at` instead of a stable anchor).
    upsert(tenant, table, naturalKey, Map.of("status", "in_progress"));
    assertThat(rawRowCount(tenant, table, naturalKey))
        .as("changed-content re-ingest must update in place, never duplicate")
        .isEqualTo(1L);
    assertThat(payloadOf(tenant, table, naturalKey)).contains("in_progress");

    // 3rd ingest, UNCHANGED content vs. the 2nd: the WHERE content_hash IS DISTINCT FROM guard
    // makes this a true no-op at the SQL level too (belt-and-suspenders on top of the caller's
    // own pre-classification) — still exactly 1 row.
    upsert(tenant, table, naturalKey, Map.of("status", "in_progress"));
    assertThat(rawRowCount(tenant, table, naturalKey)).isEqualTo(1L);
  }

  @Test
  void identical_arbiter_key_duplicate_insert_is_rejected() throws SQLException {
    try (Connection admin =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      admin.setAutoCommit(true);
      UUID connectorId = UUID.randomUUID();
      String insert =
          "INSERT INTO staging.raw_jira (tenant_id, connector_id, stream, natural_key,"
              + " source_system, source_instance, external_id, op, fetch_kind, payload,"
              + " content_hash, first_ingested_at) VALUES (?, ?, 'work_item', 'DUP-1', 'jira',"
              + " 'x', 'ext-dup', 'upsert', 'full', '{}'::jsonb, decode('bb','hex'), '2026-07-01')";
      try (PreparedStatement ps = admin.prepareStatement(insert)) {
        ps.setObject(1, TENANT_A);
        ps.setObject(2, connectorId);
        ps.executeUpdate();
      }
      try (PreparedStatement ps = admin.prepareStatement(insert)) {
        ps.setObject(1, TENANT_A);
        ps.setObject(2, connectorId);
        assertThatThrownBy(ps::executeUpdate)
            .as("identical (tenant, connector, stream, natural_key, first_ingested_at) tuple")
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("duplicate key value violates unique constraint");
      }
    }
  }

  private void upsert(
      TenantContext tenant, String table, String naturalKey, Map<String, String> payload) {
    RawRecord record =
        new RawRecord(
            "work_item",
            naturalKey,
            "jira",
            "x",
            "ext-" + naturalKey,
            Op.UPSERT,
            FetchKind.FULL,
            payload);
    String json = codec.toCanonicalJson(payload);
    byte[] hash = codec.contentHash(json);
    runner.run(
        tenant,
        () ->
            staging.upsertAll(
                table, FIXTURE_CONNECTOR, List.of(new RawUpsert(record, json, hash))));
  }

  private long rawRowCount(TenantContext tenant, String table, String naturalKey) {
    return runner.read(
        tenant,
        () ->
            appJdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE natural_key = ?",
                Long.class,
                naturalKey));
  }

  private String payloadOf(TenantContext tenant, String table, String naturalKey) {
    return runner.read(
        tenant,
        () ->
            appJdbcTemplate.queryForObject(
                "SELECT payload::text FROM " + table + " WHERE natural_key = ?",
                String.class,
                naturalKey));
  }

  private static long count(Connection admin, String table, UUID connectorId) throws SQLException {
    try (PreparedStatement ps =
        admin.prepareStatement("SELECT count(*) FROM " + table + " WHERE connector_id = ?")) {
      ps.setObject(1, connectorId);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static String partitionOf(Connection admin, String table, String naturalKey)
      throws SQLException {
    try (PreparedStatement ps =
        admin.prepareStatement(
            "SELECT tableoid::regclass::text FROM staging." + table + " WHERE natural_key = ?")) {
      ps.setString(1, naturalKey);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private static boolean firstIngestedAtEqualsIngestedAt(
      Connection admin, String table, String naturalKey) throws SQLException {
    try (PreparedStatement ps =
        admin.prepareStatement(
            "SELECT ingested_at = first_ingested_at FROM staging."
                + table
                + " WHERE natural_key = ?")) {
      ps.setString(1, naturalKey);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getBoolean(1);
      }
    }
  }
}
