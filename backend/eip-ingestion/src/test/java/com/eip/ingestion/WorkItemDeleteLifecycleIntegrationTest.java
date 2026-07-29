/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.eip.connectors.spi.FetchKind;
import com.eip.connectors.spi.Op;
import com.eip.connectors.spi.RawRecord;
import com.eip.core.domain.DefaultUuidV7Generator;
import com.eip.ingestion.application.NormalizationService;
import com.eip.ingestion.persistence.CanonicalWriteRepository;
import com.eip.ingestion.persistence.OutboxRepository;
import com.eip.ingestion.persistence.RawPayloadCodec;
import com.eip.ingestion.persistence.StagingRawRepository;
import com.eip.ingestion.persistence.StagingRawRepository.RawUpsert;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.tx.TenantTransactionRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
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
 * Proves the work-item delete lifecycle against real PostgreSQL (DEBT-020 item 3, M2b Wave 3E):
 * normalizing a staged {@code op='delete'} row resolves the canonical id through the same
 * external-ref identity path an upsert uses and sets {@code work.work_item.deleted_at} (a
 * bookkeeping "now" — no source reports a deletion instant); a deleted item then drops out of an
 * analytics-style live query ({@code deleted_at IS NULL}, exactly the filter {@code
 * InFlightRepository}/{@code TrendRepository}/{@code FrictionProjectionRepository}/{@code
 * FrictionReadRepository} apply); and re-upserting the same identity REVIVES it — {@code
 * deleted_at} clears, the row counts as changed, and a new {@code upserted} outbox row is emitted
 * (no outbox event is emitted for the deletion itself; v0.1's event-type vocabulary stays {@code
 * upserted} only).
 *
 * <p><b>Honest limitation:</b> no real connector emits {@code op='delete'} yet — this test stages
 * the delete row directly through {@link StagingRawRepository}, exactly as a future delete-aware
 * connector's sync would, rather than driving it through any actual source integration.
 */
@Tag("integration")
class WorkItemDeleteLifecycleIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID CONNECTOR_ID = UUID.randomUUID();
  private static final String NATURAL_KEY = "DEL-1";
  private static final String EXTERNAL_ID = "jira:del-1";

  private static NormalizationService normalization;
  private static StagingRawRepository staging;
  private static RawPayloadCodec codec;
  private static TenantTransactionRunner runner;
  private static JdbcClient jdbc;

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
      for (String schema : new String[] {"core", "work", "scm", "cicd", "quality", "staging"}) {
        st.execute("GRANT USAGE ON SCHEMA " + schema + " TO eip_app");
        st.execute(
            "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA "
                + schema
                + " TO eip_app");
      }
      st.execute(
          "INSERT INTO core.tenant (id, name, slug) VALUES ('" + TENANT + "', 'Delete', 'del')");
      UUID org = UUID.randomUUID();
      UUID bu = UUID.randomUUID();
      st.execute(
          "INSERT INTO core.organization (id, tenant_id, name, slug) VALUES ('"
              + org
              + "', '"
              + TENANT
              + "', 'Org', 'org')");
      st.execute(
          "INSERT INTO core.business_unit (id, tenant_id, organization_id, name) VALUES ('"
              + bu
              + "', '"
              + TENANT
              + "', '"
              + org
              + "', 'Eng')");
      st.execute(
          "INSERT INTO core.team (id, tenant_id, business_unit_id, name, type) VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + TENANT
              + "', '"
              + bu
              + "', 'Platform', 'STREAM_ALIGNED')");
    }

    PGSimpleDataSource app = new PGSimpleDataSource();
    app.setUrl(POSTGRES.getJdbcUrl());
    app.setUser("eip_app");
    app.setPassword("eip_app_pw");
    DataSource dataSource = app;

    runner = new TenantTransactionRunner(new JdbcTransactionManager(dataSource), dataSource);
    jdbc = JdbcClient.create(dataSource);
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    staging = new StagingRawRepository(jdbc, jdbcTemplate);
    codec = new RawPayloadCodec(mapper);
    normalization =
        new NormalizationService(
            runner,
            staging,
            new CanonicalWriteRepository(jdbc, jdbcTemplate),
            mapper,
            new OutboxRepository(jdbc, mapper),
            new DefaultUuidV7Generator(Clock.systemUTC()));
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void deletes_then_revives_a_work_item() {
    TenantContext tenant = TenantContext.of(TENANT);

    // 1. Stage + normalize an upsert: the item exists, undeleted, visible, one outbox row.
    stage(upsertRecord());
    normalization.normalize(tenant);
    assertThat(deletedAt()).isNull();
    assertThat(visibleInLiveQuery()).isTrue();
    assertThat(outboxCount()).isEqualTo(1L);

    // 2. Stage + normalize a synthetic delete for the SAME identity (external_id): deleted_at set,
    // the item drops out of an analytics-style live query, no new outbox row for the deletion.
    stage(deleteRecord());
    normalization.normalize(tenant);
    assertThat(deletedAt()).isNotNull();
    assertThat(visibleInLiveQuery()).isFalse();
    assertThat(outboxCount()).isEqualTo(1L);

    // 3. Re-upsert the same identity: revival — deleted_at clears, counts as changed, so a NEW
    // outbox row is emitted (upsertWorkItems' IS DISTINCT FROM guard includes deleted_at).
    stage(upsertRecord());
    normalization.normalize(tenant);
    assertThat(deletedAt()).isNull();
    assertThat(visibleInLiveQuery()).isTrue();
    assertThat(outboxCount()).isEqualTo(2L);
  }

  private void stage(RawRecord record) {
    String json = codec.toCanonicalJson(record.payload());
    byte[] hash = codec.contentHash(json);
    runner.run(
        TenantContext.of(TENANT),
        () ->
            staging.upsertAll(
                StagingRawRepository.rawTable("simulation"),
                CONNECTOR_ID,
                List.of(new RawUpsert(record, json, hash))));
  }

  private static RawRecord upsertRecord() {
    return RawRecord.ofFlat(
        "work_item",
        NATURAL_KEY,
        "jira",
        "test",
        EXTERNAL_ID,
        Op.UPSERT,
        FetchKind.FULL,
        Map.of(
            "key", NATURAL_KEY,
            "team", "Platform",
            "type", "story",
            "title", "Deletable item",
            "status", "TODO",
            "createdAt", "2026-01-01T00:00:00Z"));
  }

  private static RawRecord deleteRecord() {
    // A delete assertion carries no field content; its payload is deliberately different from the
    // upsert's above so the staging upsert's content-hash guard (computed from payload only)
    // actually lets the op column flip from 'upsert' to 'delete' on the same natural key.
    return RawRecord.ofFlat(
        "work_item", NATURAL_KEY, "jira", "test", EXTERNAL_ID, Op.DELETE, FetchKind.FULL, Map.of());
  }

  private @Nullable Timestamp deletedAt() {
    // TenantTransactionRunner's work must not return null (its own contract) — return the never-
    // null Optional from inside the transaction, unwrap to a possibly-null Timestamp out here.
    Optional<Timestamp> deletedAt =
        runner.read(
            TenantContext.of(TENANT),
            () ->
                jdbc.sql(
                        "SELECT wi.deleted_at FROM work.work_item wi"
                            + " JOIN core.external_ref er"
                            + "   ON er.entity_type = 'WORK_ITEM' AND er.entity_id = wi.id"
                            + " WHERE er.external_id = :externalId")
                    .param("externalId", EXTERNAL_ID)
                    .query(Timestamp.class)
                    .optional());
    return deletedAt.orElse(null);
  }

  /** Mirrors the "ignore deleted items" filter every eip-analytics read repository applies. */
  private boolean visibleInLiveQuery() {
    long count =
        runner.read(
            TenantContext.of(TENANT),
            () ->
                jdbc.sql(
                        "SELECT count(*) FROM work.work_item wi"
                            + " JOIN core.external_ref er"
                            + "   ON er.entity_type = 'WORK_ITEM' AND er.entity_id = wi.id"
                            + " WHERE er.external_id = :externalId AND wi.deleted_at IS NULL")
                    .param("externalId", EXTERNAL_ID)
                    .query(Long.class)
                    .single());
    return count > 0;
  }

  private long outboxCount() {
    return runner.read(
        TenantContext.of(TENANT),
        () -> jdbc.sql("SELECT count(*) FROM core.event_outbox").query(Long.class).single());
  }
}
