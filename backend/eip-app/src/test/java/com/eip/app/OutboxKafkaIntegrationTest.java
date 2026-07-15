/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.eip.app.application.RunFrictionPipelineUseCase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end proof of the outbox → Kafka → consumer spine (DEBT-017, BackendPlan §6) against real
 * PostgreSQL <em>and</em> a real Kafka broker (KRaft, Testcontainers): the friction pipeline's
 * canonical write emits outbox rows, the scheduled relay publishes them, the real
 * {@code @KafkaListener} consumer receives them and records {@code core.processed_events},
 * redelivering an already-processed event is deduped (no double processing), and a malformed
 * message is routed to the DLQ topic rather than blocking or infinitely redelivering.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Tag("integration")
class OutboxKafkaIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
  private static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

  private static final UUID TENANT_A = UUID.randomUUID();
  private static final String TOPIC = "eip.domain.workitem";
  private static final String DLQ_TOPIC = "eip.domain.workitem.dlq";
  private static final String CONSUMER_GROUP = "eip.analytics.recompute";

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    POSTGRES.start();
    KAFKA.start();
    prepareDatabase();
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "eip_app");
    registry.add("spring.datasource.password", () -> "eip_app_pw");
    registry.add("spring.flyway.enabled", () -> "false");
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("eip.events.enabled", () -> "true");
    registry.add("eip.events.relay-delay-ms", () -> "200");
  }

  private static void prepareDatabase() {
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
      for (String schema :
          new String[] {"core", "work", "scm", "cicd", "quality", "analytics", "staging"}) {
        st.execute("GRANT USAGE ON SCHEMA " + schema + " TO eip_app");
        st.execute(
            "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA "
                + schema
                + " TO eip_app");
      }
      st.execute(
          "INSERT INTO core.tenant (id, name, slug) VALUES ('" + TENANT_A + "', 'A', 'tenant-a')");
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
    } catch (SQLException e) {
      throw new IllegalStateException("failed to prepare test database", e);
    }
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
    KAFKA.stop();
  }

  @Autowired private RunFrictionPipelineUseCase pipeline;

  @Test
  void outbox_rows_publish_through_kafka_and_the_real_consumer_dedups() {
    pipeline.run(TENANT_A);

    // The scheduled relay (200ms) publishes every outbox row.
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(publishedCount()).isEqualTo(9L));

    // The real @KafkaListener consumes every event and records it (dedup ledger). Wait for the
    // consumer to fully converge (all 9, not just "at least one") before the redelivery check
    // below — otherwise a still-catching-up consumer processing the remaining distinct events
    // would be indistinguishable from a dedup failure.
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(processedEventsCount()).isEqualTo(9L));

    // Redeliver an event the consumer has ALREADY recorded: must not double-process.
    UUID processedEventId = anyProcessedEventId();
    String envelopeJson = envelopeJsonFor(processedEventId);
    long stableCount = processedEventsCount();
    sendRaw(TOPIC, TENANT_A + ":replay", envelopeJson);

    await()
        .pollDelay(Duration.ofSeconds(3))
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(processedEventsCount()).isEqualTo(stableCount));
  }

  @Test
  void malformed_messages_are_routed_to_the_dlq_topic() {
    sendRaw(TOPIC, "malformed", "not-json-at-all");

    List<String> dlqValues = consume(DLQ_TOPIC, 1, Duration.ofSeconds(25));
    assertThat(dlqValues).contains("not-json-at-all");
  }

  private void sendRaw(String topic, String key, String value) {
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
      producer.send(new ProducerRecord<>(topic, key, value)).get(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("failed to send test message to " + topic, e);
    }
  }

  private List<String> consume(String topic, int minCount, Duration timeout) {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    List<String> values = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(topic));
      long deadlineNanos = System.nanoTime() + timeout.toNanos();
      while (values.size() < minCount && System.nanoTime() < deadlineNanos) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
        records.forEach(r -> values.add(r.value()));
      }
    }
    return values;
  }

  private long publishedCount() {
    return scalarAsSuperuser(
        "SELECT count(*) FROM core.event_outbox WHERE tenant_id = '"
            + TENANT_A
            + "' AND published_at IS NOT NULL");
  }

  private long processedEventsCount() {
    return scalarAsSuperuser(
        "SELECT count(*) FROM core.processed_events WHERE tenant_id = '"
            + TENANT_A
            + "' AND consumer_group = '"
            + CONSUMER_GROUP
            + "'");
  }

  private UUID anyProcessedEventId() {
    try (Connection su =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = su.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT event_id FROM core.processed_events WHERE tenant_id = '"
                    + TENANT_A
                    + "' AND consumer_group = '"
                    + CONSUMER_GROUP
                    + "' LIMIT 1")) {
      assertThat(rs.next()).isTrue();
      return rs.getObject(1, UUID.class);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private String envelopeJsonFor(UUID eventId) {
    try (Connection su =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = su.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT envelope::text FROM core.event_outbox WHERE event_id = '"
                    + eventId
                    + "'")) {
      assertThat(rs.next()).isTrue();
      return rs.getString(1);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private long scalarAsSuperuser(String sql) {
    try (Connection su =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = su.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
