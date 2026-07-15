/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.persistence;

import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.tx.TenantTransactionRunner;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Consumer idempotency ledger over {@code core.processed_events} (BackendPlan §6). Owns its own
 * tenant-bound transaction (the {@link ConnectorQueryRepository}-style convention: the repository
 * wraps a single, self-contained unit of work rather than expecting the caller to open one) so the
 * check-then-insert dedup below is atomic within one connection.
 *
 * <p><strong>Deviation from a literal {@code ON CONFLICT DO NOTHING}:</strong> the V1 baseline's
 * primary key is {@code (consumer_group, event_id, processed_at)} — {@code processed_at} is part of
 * the key only because the table is {@code PARTITION BY RANGE (processed_at)} (PostgreSQL requires
 * the partition column in every unique constraint on a partitioned table). There is therefore no
 * unique constraint on {@code (consumer_group, event_id)} alone to conflict on — a real one would
 * need {@code processed_at} too, which varies per insert and would never conflict. Dedup here is a
 * check-then-insert instead: correct for this v0.1's single consumer-group-per-partition
 * {@code @KafkaListener} (messages for a given key process sequentially), though it is not a hard
 * concurrency guarantee the way a real unique-constraint conflict would be. Flagged as a known
 * trade-off rather than silently reinterpreted.
 */
@Repository
public class ProcessedEventsRepository {

  private final TenantTransactionRunner tx;
  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param tx the tenant-aware transaction boundary
   * @param jdbc the JDBC client
   */
  public ProcessedEventsRepository(TenantTransactionRunner tx, JdbcClient jdbc) {
    this.tx = tx;
    this.jdbc = jdbc;
  }

  /**
   * Records that {@code consumerGroup} processed {@code eventId} for {@code tenant}, unless already
   * recorded.
   *
   * @param tenant the event's owning tenant
   * @param consumerGroup the consumer group name
   * @param eventId the event's id (dedup key)
   * @return {@code true} if this is the first time (caller should process the event); {@code false}
   *     if already processed (caller should skip — a duplicate delivery)
   */
  public boolean recordIfNew(TenantContext tenant, String consumerGroup, UUID eventId) {
    return tx.call(
        tenant,
        () -> {
          boolean alreadyProcessed =
              jdbc.sql(
                          "SELECT count(*) FROM core.processed_events"
                              + " WHERE consumer_group = :consumerGroup AND event_id = :eventId")
                      .param("consumerGroup", consumerGroup)
                      .param("eventId", eventId)
                      .query(Long.class)
                      .single()
                  > 0;
          if (alreadyProcessed) {
            return false;
          }
          jdbc.sql(
                  "INSERT INTO core.processed_events (consumer_group, event_id, tenant_id)"
                      + " VALUES (:consumerGroup, :eventId, :tenantId)")
              .param("consumerGroup", consumerGroup)
              .param("eventId", eventId)
              .param("tenantId", tenant.tenantId())
              .update();
          return true;
        });
  }
}
