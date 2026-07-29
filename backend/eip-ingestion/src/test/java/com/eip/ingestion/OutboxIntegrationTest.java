/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.eip.connectors.simulation.SimulationConnector;
import com.eip.core.domain.DefaultUuidV7Generator;
import com.eip.core.domain.EntityType;
import com.eip.core.domain.UuidV7Generator;
import com.eip.core.events.EventEnvelope;
import com.eip.core.events.SchemaVersion;
import com.eip.core.events.WorkItemUpserted;
import com.eip.ingestion.api.DomainEventPublisher;
import com.eip.ingestion.api.RelayOutboxUseCase.OutboxRelayResult;
import com.eip.ingestion.application.ConnectorRegistry;
import com.eip.ingestion.application.NormalizationService;
import com.eip.ingestion.application.OutboxRelayService;
import com.eip.ingestion.application.SimulationIngestionService;
import com.eip.ingestion.persistence.CanonicalWriteRepository;
import com.eip.ingestion.persistence.ConnectorRegistryRepository;
import com.eip.ingestion.persistence.OutboxRepository;
import com.eip.ingestion.persistence.RawPayloadCodec;
import com.eip.ingestion.persistence.StagingRawRepository;
import com.eip.tenancy.api.ManageTenantsUseCase;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.tx.TenantTransactionRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * Module-level proof of the transactional outbox → relay spine (DEBT-017, BackendPlan §6) against
 * real PostgreSQL as the NOBYPASSRLS {@code eip_app} role: normalization writes one outbox row per
 * changed work item in the same transaction as the canonical upsert (NormalizationService's
 * documented emission semantics — see its class javadoc), the relay publishes and marks rows
 * published, a byte-identical re-normalization emits no duplicate rows, and a failing publisher
 * drives a row through capped retries to the dead-lettered state. No Spring context: every
 * component is constructed directly with a fake {@link DomainEventPublisher} and a fake {@link
 * ManageTenantsUseCase} (the real Kafka adapter and tenant directory are eip-app concerns).
 */
@Tag("integration")
class OutboxIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  private static final UUID TENANT_A = UUID.randomUUID();

  private static SimulationIngestionService ingestion;
  private static NormalizationService normalization;
  private static OutboxRepository outbox;
  private static TenantTransactionRunner runner;
  private static JdbcClient jdbc;
  private static UuidV7Generator uuidGenerator;
  private static FakePublisher publisher;

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
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    StagingRawRepository staging = new StagingRawRepository(jdbc, jdbcTemplate);
    outbox = new OutboxRepository(jdbc, mapper);
    uuidGenerator = new DefaultUuidV7Generator(Clock.systemUTC());
    ingestion =
        new SimulationIngestionService(
            runner,
            new ConnectorRegistry(java.util.List.of(new SimulationConnector())),
            new ConnectorRegistryRepository(jdbc),
            staging,
            new RawPayloadCodec(mapper));
    normalization =
        new NormalizationService(
            runner,
            staging,
            new CanonicalWriteRepository(jdbc, jdbcTemplate),
            mapper,
            outbox,
            uuidGenerator);
    publisher = new FakePublisher();
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void relays_and_dead_letters_the_outbox_under_rls() throws Exception {
    TenantContext tenantA = TenantContext.of(TENANT_A);
    OutboxRelayService relay =
        new OutboxRelayService(
            runner,
            outbox,
            publisher,
            new FakeTenants(List.of(new ManageTenantsUseCase.TenantView(TENANT_A, "A", "a"))));

    // 1) Ingest + normalize: one outbox row per (newly inserted) work item, same transaction.
    ingestion.ingest(tenantA);
    normalization.normalize(tenantA);
    assertThat(outboxCount(TENANT_A)).isEqualTo(9L);

    List<RawOutboxRow> rows = rawOutboxRows(TENANT_A);
    assertThat(rows).hasSize(9);
    for (RawOutboxRow row : rows) {
      assertThat(row.topic()).isEqualTo("eip.domain.workitem");
      assertThat(row.partitionKey()).startsWith(TENANT_A + ":");
      assertThat(UUID.fromString(row.eventId())).isNotNull(); // eventId is a UUID
      JsonNode envelope = new ObjectMapper().readTree(row.envelopeJson());
      assertThat(fieldNames(envelope))
          .containsExactlyInAnyOrder(
              "eventId",
              "tenantId",
              "source",
              "entityType",
              "entityId",
              "eventType",
              "occurredAt",
              "ingestedAt",
              "schemaVersion",
              "payload",
              "traceparent");
      assertThat(envelope.get("entityType").asText()).isEqualTo("WORK_ITEM");
      assertThat(envelope.get("eventType").asText()).isEqualTo("upserted");
    }

    // 2) Idempotent re-normalization of byte-identical data: zero new outbox rows.
    normalization.normalize(tenantA);
    assertThat(outboxCount(TENANT_A)).isEqualTo(9L);

    // 3) relayOnce publishes every unpublished row and marks it published.
    OutboxRelayResult first = relay.relayOnce(100);
    assertThat(first).isEqualTo(new OutboxRelayResult(9, 0, 0));
    assertThat(publisher.published()).hasSize(9);
    assertThat(publishedCount(TENANT_A)).isEqualTo(9L);

    // A second pass finds nothing left to publish.
    OutboxRelayResult second = relay.relayOnce(100);
    assertThat(second).isEqualTo(new OutboxRelayResult(0, 0, 0));

    // 4) A failing publisher increments attempts; after MAX_ATTEMPTS the row is dead-lettered and
    // excluded from the batch.
    UUID failingEventId = uuidGenerator.generate();
    UUID failingEntityId = UUID.randomUUID();
    runner.run(
        tenantA,
        () ->
            outbox.insert(
                new EventEnvelope(
                    failingEventId,
                    TENANT_A,
                    "ingestion",
                    EntityType.WORK_ITEM,
                    failingEntityId,
                    "upserted",
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.now(),
                    SchemaVersion.of(1, 0),
                    new WorkItemUpserted(failingEntityId, "DONE"),
                    null),
                "eip.domain.workitem",
                TENANT_A + ":" + failingEntityId));

    publisher.throwing = true;
    for (int attempt = 1; attempt <= OutboxRepository.MAX_ATTEMPTS - 1; attempt++) {
      OutboxRelayResult result = relay.relayOnce(100);
      assertThat(result).as("attempt %d", attempt).isEqualTo(new OutboxRelayResult(0, 1, 0));
    }
    OutboxRelayResult tenthAttempt = relay.relayOnce(100);
    assertThat(tenthAttempt).isEqualTo(new OutboxRelayResult(0, 0, 1));

    assertThat(runner.read(tenantA, () -> outbox.countDeadLettered())).isEqualTo(1L);
    // Dead-lettered rows are excluded from the batch — an 11th call finds nothing to do.
    assertThat(relay.relayOnce(100)).isEqualTo(new OutboxRelayResult(0, 0, 0));
  }

  /**
   * Regression test: a real Bitbucket OPEN pull request omits {@code mergedAt} (and, like this
   * synthetic payload, may omit {@code team}/{@code workItemKey} too) entirely rather than sending
   * a null/empty value. Before this fix, {@code NormalizationService} called {@code
   * p.timestamp("mergedAt")} unconditionally, so {@code Instant.parse("")} threw and normalization
   * failed for any tenant with an open PR staged. {@code scm.pull_request.merged_at} is nullable
   * (V3), so the fix is purely in the mapping: {@code p.timestampOrNull("mergedAt")}.
   */
  @Test
  void normalizes_an_open_pull_request_with_no_merged_at_key() {
    UUID tenantId = UUID.randomUUID();
    TenantContext tenant = TenantContext.of(tenantId);
    String naturalKey = "PR-OPEN-1";

    runner.run(
        tenant,
        () ->
            jdbc.sql(
                    """
                    INSERT INTO staging.raw_bitbucket
                      (tenant_id, connector_id, stream, natural_key, source_system,
                       source_instance, external_id, payload, content_hash)
                    VALUES (current_setting('app.tenant_id')::uuid, :connectorId, 'pull_request',
                            :naturalKey, 'bitbucket', 'bb-1', :externalId, :payload::jsonb,
                            :contentHash)
                    """)
                .param("connectorId", UUID.randomUUID())
                .param("naturalKey", naturalKey)
                .param("externalId", naturalKey)
                .param(
                    "payload",
                    "{\"key\":\""
                        + naturalKey
                        + "\",\"title\":\"Open PR\",\"sourceBranch\":\"feature/x\","
                        + "\"status\":\"OPEN\",\"createdAt\":\"2026-01-01T00:00:00Z\"}")
                .param("contentHash", new byte[] {0})
                .update());

    normalization.normalize(tenant); // must not throw (regression: Instant.parse("") on mergedAt)

    Boolean mergedAtIsNull =
        runner.read(
            tenant,
            () ->
                jdbc.sql("SELECT merged_at IS NULL FROM scm.pull_request WHERE source_key = :key")
                    .param("key", naturalKey)
                    .query(Boolean.class)
                    .single());
    assertThat(mergedAtIsNull).isTrue();
  }

  private static Set<String> fieldNames(JsonNode node) {
    Set<String> names = new java.util.LinkedHashSet<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private long outboxCount(UUID tenant) {
    return runner.read(
        TenantContext.of(tenant),
        () -> jdbc.sql("SELECT count(*) FROM core.event_outbox").query(Long.class).single());
  }

  private long publishedCount(UUID tenant) {
    return runner.read(
        TenantContext.of(tenant),
        () ->
            jdbc.sql("SELECT count(*) FROM core.event_outbox WHERE published_at IS NOT NULL")
                .query(Long.class)
                .single());
  }

  private List<RawOutboxRow> rawOutboxRows(UUID tenant) {
    return runner.read(
        TenantContext.of(tenant),
        () ->
            jdbc.sql("SELECT event_id, topic, partition_key, envelope::text FROM core.event_outbox")
                .query(
                    (rs, rowNum) ->
                        new RawOutboxRow(
                            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .list());
  }

  private record RawOutboxRow(
      String eventId, String topic, String partitionKey, String envelopeJson) {}

  /** In-memory {@link DomainEventPublisher} double; can be flipped to always throw. */
  private static final class FakePublisher implements DomainEventPublisher {
    private final List<String> published = new CopyOnWriteArrayList<>();
    private volatile boolean throwing;

    @Override
    public void publish(String topic, String partitionKey, String envelopeJson) {
      if (throwing) {
        throw new IllegalStateException("simulated publish failure");
      }
      published.add(topic + "|" + partitionKey);
    }

    List<String> published() {
      return published;
    }
  }

  /** Fixed-list {@link ManageTenantsUseCase} double — the relay only needs {@code list()}. */
  private record FakeTenants(List<ManageTenantsUseCase.TenantView> views)
      implements ManageTenantsUseCase {

    @Override
    public TenantView create(String name, String slug) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<TenantView> list() {
      return views;
    }
  }
}
