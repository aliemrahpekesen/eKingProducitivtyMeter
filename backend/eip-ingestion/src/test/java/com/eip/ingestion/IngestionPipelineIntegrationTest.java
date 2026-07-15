/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.eip.connectors.simulation.SimulationConnector;
import com.eip.ingestion.api.IngestionResult;
import com.eip.ingestion.application.ConnectorRegistry;
import com.eip.ingestion.application.NormalizationService;
import com.eip.ingestion.application.SimulationIngestionService;
import com.eip.ingestion.persistence.CanonicalWriteRepository;
import com.eip.ingestion.persistence.ConnectorRegistryRepository;
import com.eip.ingestion.persistence.RawPayloadCodec;
import com.eip.ingestion.persistence.StagingRawRepository;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.tx.TenantTransactionRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
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
 * Module-level proof of the ingestion slice against real PostgreSQL as the NOBYPASSRLS {@code
 * eip_app} role (schema migrated with the app's Flyway scripts — the single schema source): the
 * simulation connector's records stage set-based and content-hash idempotent, normalization
 * produces the canonical model + external-ref anchors idempotently, and both tiers are
 * RLS-isolated. No Spring context: the services are constructed directly, proving they are plain
 * constructor-injected components.
 */
@Tag("integration")
class IngestionPipelineIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  private static final UUID TENANT_A = UUID.randomUUID();
  private static final UUID TENANT_B = UUID.randomUUID();

  private static SimulationIngestionService ingestion;
  private static NormalizationService normalization;
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
      st.execute("INSERT INTO core.tenant (id, name, slug) VALUES ('" + TENANT_A + "', 'A', 'a')");
      st.execute("INSERT INTO core.tenant (id, name, slug) VALUES ('" + TENANT_B + "', 'B', 'b')");
      UUID org = UUID.randomUUID();
      UUID bu = UUID.randomUUID();
      st.execute(
          "INSERT INTO core.organization (id, tenant_id, name, slug) VALUES ('"
              + org
              + "', '"
              + TENANT_A
              + "', 'Org', 'org')");
      st.execute(
          "INSERT INTO core.business_unit (id, tenant_id, organization_id, name) VALUES ('"
              + bu
              + "', '"
              + TENANT_A
              + "', '"
              + org
              + "', 'Eng')");
      for (String team : new String[] {"Platform", "Payments", "Web"}) {
        st.execute(
            "INSERT INTO core.team (id, tenant_id, business_unit_id, name, type) VALUES ('"
                + UUID.randomUUID()
                + "', '"
                + TENANT_A
                + "', '"
                + bu
                + "', '"
                + team
                + "', 'STREAM_ALIGNED')");
      }
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
    StagingRawRepository staging = new StagingRawRepository(jdbc, jdbcTemplate);
    ingestion =
        new SimulationIngestionService(
            runner,
            new ConnectorRegistry(java.util.List.of(new SimulationConnector())),
            new ConnectorRegistryRepository(jdbc),
            staging,
            new RawPayloadCodec(mapper));
    normalization =
        new NormalizationService(
            runner, staging, new CanonicalWriteRepository(jdbc, jdbcTemplate), mapper);
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void stages_and_normalizes_idempotently_under_rls() {
    TenantContext tenantA = TenantContext.of(TENANT_A);

    IngestionResult first = ingestion.ingest(tenantA);
    assertThat(first).isEqualTo(new IngestionResult(79, 79, 0, 0));
    assertThat(count(TENANT_A, "staging.raw_simulation")).isEqualTo(79L);
    long stampBefore = maxIngestedAt(TENANT_A);

    // Replay: byte-identical dataset -> all unchanged, no churn.
    IngestionResult second = ingestion.ingest(tenantA);
    assertThat(second).isEqualTo(new IngestionResult(79, 0, 0, 79));
    assertThat(count(TENANT_A, "staging.raw_simulation")).isEqualTo(79L);
    assertThat(maxIngestedAt(TENANT_A)).isEqualTo(stampBefore);

    // Normalize twice: canonical counts identical (set-based upserts on natural keys).
    normalization.normalize(tenantA);
    normalization.normalize(tenantA);
    assertThat(count(TENANT_A, "work.work_item")).isEqualTo(9L);
    assertThat(count(TENANT_A, "work.work_item_transition")).isEqualTo(33L);
    assertThat(count(TENANT_A, "scm.pull_request")).isEqualTo(9L);
    assertThat(count(TENANT_A, "scm.code_review")).isEqualTo(10L);
    assertThat(count(TENANT_A, "cicd.build")).isEqualTo(9L);
    assertThat(count(TENANT_A, "quality.quality_gate")).isEqualTo(9L);
    assertThat(count(TENANT_A, "core.external_ref")).isEqualTo(9L);

    // Every canonical work item resolved its team through the external-ref anchor.
    assertThat(
            runner.read(
                TenantContext.of(TENANT_A),
                () ->
                    jdbc.sql("SELECT count(*) FROM work.work_item WHERE team_id IS NOT NULL")
                        .query(Long.class)
                        .single()))
        .isEqualTo(9L);

    // Tenant isolation at both tiers.
    assertThat(count(TENANT_B, "staging.raw_simulation")).isZero();
    assertThat(count(TENANT_B, "work.work_item")).isZero();
  }

  private long count(UUID tenant, String table) {
    return runner.read(
        TenantContext.of(tenant),
        () -> jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single());
  }

  private long maxIngestedAt(UUID tenant) {
    return runner.read(
        TenantContext.of(tenant),
        () ->
            jdbc.sql(
                    "SELECT coalesce(extract(epoch from max(ingested_at))::bigint, 0)"
                        + " FROM staging.raw_simulation")
                .query(Long.class)
                .single());
  }
}
