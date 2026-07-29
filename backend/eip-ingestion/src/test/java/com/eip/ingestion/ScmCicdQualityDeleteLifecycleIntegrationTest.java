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
 * Proves the delete lifecycle DEBT-018 item 4 extends to {@code scm.pull_request}, {@code
 * scm.code_review}, {@code cicd.build}, and {@code quality.quality_gate} — mirroring {@code
 * WorkItemDeleteLifecycleIntegrationTest} (DEBT-020 item 3) exactly, but resolving identity by
 * natural key ({@code source_key}) instead of {@code core.external_ref}, since these entities carry
 * no external-ref anchor. For each of the four streams: a staged {@code op='delete'} row sets
 * {@code deleted_at}, the entity then reads back ABSENT from a downstream analytics-style read (the
 * same {@code deleted_at IS NULL} filter {@code FrictionProjectionRepository}/{@code
 * FrictionReadRepository} apply), and a later re-upsert of the same {@code source_key} REVIVES it.
 *
 * <p><b>Honest limitation:</b> no real connector emits {@code op='delete'} for these entity kinds
 * yet — this test stages the delete row directly through {@link StagingRawRepository}, exactly as a
 * future delete-aware connector's sync would.
 */
@Tag("integration")
class ScmCicdQualityDeleteLifecycleIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID CONNECTOR_ID = UUID.randomUUID();

  // A pull request that stays alive for the lifetime of the test — scm.code_review.pull_request_id
  // is NOT NULL + FK-constrained, so the code-review lifecycle needs a permanently-live anchor
  // distinct from the pull-request row whose OWN delete/revive lifecycle is under test below.
  private static final String ANCHOR_PR_KEY = "PR-ANCHOR";

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
          "INSERT INTO core.tenant (id, name, slug) VALUES ('" + TENANT + "', 'SCQ', 'scq')");
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

    // Permanent anchor pull request for the code-review lifecycle test.
    stage(pullRequestRecord(ANCHOR_PR_KEY, Op.UPSERT));
    normalization.normalize(TenantContext.of(TENANT));
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void pull_request_deletes_then_revives() {
    String key = "PR-1";
    stage(pullRequestRecord(key, Op.UPSERT));
    normalization.normalize(TenantContext.of(TENANT));
    assertThat(deletedAt("scm.pull_request", key)).isNull();
    assertThat(liveCount("scm.pull_request", key)).isEqualTo(1L);

    stage(pullRequestRecord(key, Op.DELETE));
    normalization.normalize(TenantContext.of(TENANT));
    assertThat(deletedAt("scm.pull_request", key)).isNotNull();
    assertThat(liveCount("scm.pull_request", key)).isZero();

    stage(pullRequestRecord(key, Op.UPSERT));
    normalization.normalize(TenantContext.of(TENANT));
    assertThat(deletedAt("scm.pull_request", key)).isNull();
    assertThat(liveCount("scm.pull_request", key)).isEqualTo(1L);
  }

  @Test
  void code_review_deletes_then_revives() {
    String key = "REV-1";
    stage(codeReviewRecord(key, Op.UPSERT));
    normalization.normalize(TenantContext.of(TENANT));
    assertThat(deletedAt("scm.code_review", key)).isNull();
    assertThat(liveCount("scm.code_review", key)).isEqualTo(1L);

    stage(codeReviewRecord(key, Op.DELETE));
    normalization.normalize(TenantContext.of(TENANT));
    assertThat(deletedAt("scm.code_review", key)).isNotNull();
    assertThat(liveCount("scm.code_review", key)).isZero();

    stage(codeReviewRecord(key, Op.UPSERT));
    normalization.normalize(TenantContext.of(TENANT));
    assertThat(deletedAt("scm.code_review", key)).isNull();
    assertThat(liveCount("scm.code_review", key)).isEqualTo(1L);
  }

  @Test
  void build_deletes_then_revives() {
    String key = "BUILD-1";
    stage(buildRecord(key, Op.UPSERT));
    normalization.normalize(TenantContext.of(TENANT));
    assertThat(deletedAt("cicd.build", key)).isNull();
    assertThat(liveCount("cicd.build", key)).isEqualTo(1L);

    stage(buildRecord(key, Op.DELETE));
    normalization.normalize(TenantContext.of(TENANT));
    assertThat(deletedAt("cicd.build", key)).isNotNull();
    assertThat(liveCount("cicd.build", key)).isZero();

    stage(buildRecord(key, Op.UPSERT));
    normalization.normalize(TenantContext.of(TENANT));
    assertThat(deletedAt("cicd.build", key)).isNull();
    assertThat(liveCount("cicd.build", key)).isEqualTo(1L);
  }

  @Test
  void quality_gate_deletes_then_revives() {
    String key = "QG-1";
    stage(qualityGateRecord(key, Op.UPSERT));
    normalization.normalize(TenantContext.of(TENANT));
    assertThat(deletedAt("quality.quality_gate", key)).isNull();
    assertThat(liveCount("quality.quality_gate", key)).isEqualTo(1L);

    stage(qualityGateRecord(key, Op.DELETE));
    normalization.normalize(TenantContext.of(TENANT));
    assertThat(deletedAt("quality.quality_gate", key)).isNotNull();
    assertThat(liveCount("quality.quality_gate", key)).isZero();

    stage(qualityGateRecord(key, Op.UPSERT));
    normalization.normalize(TenantContext.of(TENANT));
    assertThat(deletedAt("quality.quality_gate", key)).isNull();
    assertThat(liveCount("quality.quality_gate", key)).isEqualTo(1L);
  }

  private static void stage(RawRecord record) {
    String json = codec.toCanonicalJson(record.payload());
    byte[] hash = codec.contentHash(json);
    runner.run(
        TenantContext.of(TENANT),
        () ->
            staging.upsertAll(
                StagingRawRepository.rawTable("bitbucket"),
                CONNECTOR_ID,
                List.of(new RawUpsert(record, json, hash))));
  }

  private static RawRecord pullRequestRecord(String key, Op op) {
    Map<String, String> payload =
        op == Op.DELETE
            ? Map.of()
            : Map.of(
                "key", key,
                "title", "Delete-lifecycle PR",
                "sourceBranch", "feature/x",
                "status", "OPEN",
                "createdAt", "2026-01-01T00:00:00Z");
    return RawRecord.ofFlat(
        "pull_request", key, "bitbucket", "test", "bitbucket:" + key, op, FetchKind.FULL, payload);
  }

  private static RawRecord codeReviewRecord(String key, Op op) {
    Map<String, String> payload =
        op == Op.DELETE
            ? Map.of()
            : Map.of(
                "pullRequestKey", ANCHOR_PR_KEY,
                "key", key,
                "outcome", "APPROVED",
                "requestedAt", "2026-01-01T00:00:00Z",
                "completedAt", "2026-01-01T01:00:00Z");
    return RawRecord.ofFlat(
        "code_review", key, "bitbucket", "test", "bitbucket:" + key, op, FetchKind.FULL, payload);
  }

  private static RawRecord buildRecord(String key, Op op) {
    Map<String, String> payload =
        op == Op.DELETE
            ? Map.of()
            : Map.of(
                "key", key,
                "status", "SUCCESS",
                "startedAt", "2026-01-01T00:00:00Z",
                "finishedAt", "2026-01-01T00:10:00Z");
    return RawRecord.ofFlat("build", key, "ci", "test", "ci:" + key, op, FetchKind.FULL, payload);
  }

  private static RawRecord qualityGateRecord(String key, Op op) {
    Map<String, String> payload =
        op == Op.DELETE
            ? Map.of()
            : Map.of(
                "key", key,
                "status", "PASSED",
                "evaluatedAt", "2026-01-01T00:00:00Z");
    return RawRecord.ofFlat(
        "quality_gate", key, "sonarqube", "test", "sonarqube:" + key, op, FetchKind.FULL, payload);
  }

  // TenantTransactionRunner's work must not return null (its own contract) — return the
  // never-null Optional from inside the transaction, unwrap to a possibly-null Timestamp out here
  // (mirrors WorkItemDeleteLifecycleIntegrationTest#deletedAt).
  private @Nullable Timestamp deletedAt(String table, String sourceKey) {
    Optional<Timestamp> deletedAt =
        runner.read(
            TenantContext.of(TENANT),
            () ->
                jdbc.sql("SELECT deleted_at FROM " + table + " WHERE source_key = :key")
                    .param("key", sourceKey)
                    .query(Timestamp.class)
                    .optional());
    return deletedAt.orElse(null);
  }

  /** Mirrors the {@code deleted_at IS NULL} filter every eip-analytics read repository applies. */
  private long liveCount(String table, String sourceKey) {
    return runner.read(
        TenantContext.of(TENANT),
        () ->
            jdbc.sql(
                    "SELECT count(*) FROM "
                        + table
                        + " WHERE source_key = :key AND deleted_at IS NULL")
                .param("key", sourceKey)
                .query(Long.class)
                .single());
  }
}
