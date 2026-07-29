/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.persistence;

import com.eip.core.events.EventEnvelope;
import com.eip.ingestion.api.IngestionException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Transactional-outbox adapter over {@code core.event_outbox} (BackendPlan §6; ADR-017). Every
 * insert runs on the caller's tenant-bound transaction (the same transaction as the canonical write
 * it accompanies); the relay reads with {@code FOR UPDATE SKIP LOCKED} so concurrent relay passes
 * never double-claim a row.
 *
 * <p>Columns are the V1 baseline shape exactly: {@code event_id} (PK, UUIDv7), {@code tenant_id},
 * {@code topic}, {@code partition_key}, {@code envelope} (jsonb — the serialized 11-field {@link
 * EventEnvelope}), {@code occurred_at} (row bookkeeping, {@code DEFAULT now()} — relay ordering
 * only, distinct from the envelope's own business {@code occurredAt}/{@code ingestedAt} fields),
 * {@code published_at} (null until relayed), {@code attempts} (int, {@code DEFAULT 0}). There is no
 * {@code status} column, so dead-letter state is derived: a row is dead-lettered once {@code
 * attempts >= MAX_ATTEMPTS} and {@code published_at IS NULL} — computed by {@link
 * #selectUnpublishedBatch(int)} (excludes dead rows) and {@link #countDeadLettered()}
 * (observability).
 */
@Repository
public class OutboxRepository {

  /**
   * Attempts ceiling past which an unpublished row is considered dead-lettered (BackendPlan §6).
   */
  public static final int MAX_ATTEMPTS = 10;

  private final JdbcClient jdbc;
  private final ObjectMapper mapper;

  /**
   * Creates the repository.
   *
   * @param jdbc the tenant-bound JDBC client
   * @param mapper the shared {@link ObjectMapper} used to serialize the envelope to jsonb
   */
  public OutboxRepository(JdbcClient jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  /** One unpublished outbox row read back for relay. */
  public record OutboxRow(UUID eventId, String topic, String partitionKey, String envelopeJson) {}

  /**
   * Inserts one outbox row on the caller's active transaction.
   *
   * @param envelope the canonical event envelope
   * @param topic the destination topic
   * @param partitionKey the partition/ordering key
   */
  public void insert(EventEnvelope envelope, String topic, String partitionKey) {
    jdbc.sql(
            """
            INSERT INTO core.event_outbox (event_id, tenant_id, topic, partition_key, envelope)
            VALUES (:eventId, current_setting('app.tenant_id')::uuid, :topic, :partitionKey,
                    :envelope::jsonb)
            """)
        .param("eventId", envelope.eventId())
        .param("topic", topic)
        .param("partitionKey", partitionKey)
        .param("envelope", writeJson(envelope))
        .update();
  }

  /**
   * Selects up to {@code batchSize} unpublished, non-dead-lettered rows for the caller's tenant,
   * locking them ({@code FOR UPDATE SKIP LOCKED}) so a concurrent relay pass skips them instead of
   * blocking.
   *
   * @param batchSize the maximum rows to select
   * @return the batch, oldest first
   */
  public List<OutboxRow> selectUnpublishedBatch(int batchSize) {
    return jdbc.sql(
            """
            SELECT event_id, topic, partition_key, envelope::text AS envelope_json
            FROM core.event_outbox
            WHERE published_at IS NULL AND attempts < :maxAttempts
            ORDER BY occurred_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """)
        .param("maxAttempts", MAX_ATTEMPTS)
        .param("batchSize", batchSize)
        .query(
            (rs, rowNum) ->
                new OutboxRow(
                    rs.getObject("event_id", UUID.class),
                    rs.getString("topic"),
                    rs.getString("partition_key"),
                    rs.getString("envelope_json")))
        .list();
  }

  /**
   * Marks a row published (successful relay).
   *
   * @param eventId the row's event id
   */
  public void markPublished(UUID eventId) {
    jdbc.sql("UPDATE core.event_outbox SET published_at = now() WHERE event_id = :eventId")
        .param("eventId", eventId)
        .update();
  }

  /**
   * Records a failed publish attempt.
   *
   * @param eventId the row's event id
   * @return the attempts count after this increment
   */
  public int incrementAttempts(UUID eventId) {
    return jdbc.sql(
            """
            UPDATE core.event_outbox SET attempts = attempts + 1
            WHERE event_id = :eventId
            RETURNING attempts
            """)
        .param("eventId", eventId)
        .query(Integer.class)
        .single();
  }

  /**
   * Counts the caller tenant's currently dead-lettered rows (observability; {@code
   * eip_outbox_dead_total} is the relay's per-call counter — this is the current backlog).
   *
   * @return the dead-lettered row count
   */
  public long countDeadLettered() {
    return jdbc.sql(
            "SELECT count(*) FROM core.event_outbox WHERE published_at IS NULL"
                + " AND attempts >= :maxAttempts")
        .param("maxAttempts", MAX_ATTEMPTS)
        .query(Long.class)
        .single();
  }

  private String writeJson(EventEnvelope envelope) {
    try {
      return mapper.writeValueAsString(envelope);
    } catch (JsonProcessingException e) {
      throw new IngestionException("failed to serialize event envelope " + envelope.eventId(), e);
    }
  }
}
