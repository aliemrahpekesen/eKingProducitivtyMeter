/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.persistence;

import com.eip.ingestion.api.IngestionException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Adapter over {@code core.connector_checkpoint} (DatabasePlan §3): one row per (connector, stream)
 * holding the connector's incremental-sync cursor as a flat string map. v0.1 drives only the
 * connector-level cursor, keyed by {@link #DEFAULT_STREAM}; per-stream checkpoints are future
 * scope. Every statement runs on the caller's tenant-bound transaction, keying {@code tenant_id}
 * off {@code current_setting('app.tenant_id')} so RLS WITH CHECK holds (mirrors {@link
 * ConnectorAdminRepository}'s style).
 */
@Repository
public class ConnectorCheckpointRepository {

  /** The v0.1 connector-level checkpoint stream (no per-stream granularity yet). */
  public static final String DEFAULT_STREAM = "default";

  private static final TypeReference<Map<String, String>> CURSOR_TYPE = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final ObjectMapper mapper;

  /**
   * Creates the repository.
   *
   * @param jdbc the tenant-bound JDBC client
   * @param mapper the shared {@link ObjectMapper} used to (de)serialize the cursor to jsonb
   */
  public ConnectorCheckpointRepository(JdbcClient jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  /**
   * Loads a connector's checkpoint cursor for one stream.
   *
   * @param connectorId the connector
   * @param stream the logical stream ({@link #DEFAULT_STREAM} for the connector-level cursor)
   * @return the cursor map, or empty when no checkpoint row exists yet (first/full sync)
   */
  public Optional<Map<String, String>> find(UUID connectorId, String stream) {
    return jdbc.sql(
            """
            SELECT cursor::text FROM core.connector_checkpoint
            WHERE connector_id = :connectorId AND stream = :stream
            """)
        .param("connectorId", connectorId)
        .param("stream", stream)
        .query(String.class)
        .optional()
        .map(this::readCursor);
  }

  /**
   * Inserts or advances a connector's checkpoint cursor for one stream, stamping the appropriate
   * sync-kind timestamp column.
   *
   * @param connectorId the connector
   * @param stream the logical stream ({@link #DEFAULT_STREAM} for the connector-level cursor)
   * @param cursor the advanced cursor to persist
   * @param fullSync {@code true} to stamp {@code last_full_sync_at}, {@code false} for {@code
   *     last_incremental_sync_at}
   */
  public void upsert(
      UUID connectorId, String stream, Map<String, String> cursor, boolean fullSync) {
    // The timestamp column is chosen from a fixed two-value whitelist below — never caller input —
    // so string-formatting it into the SQL carries no injection risk.
    String timestampColumn = fullSync ? "last_full_sync_at" : "last_incremental_sync_at";
    jdbc.sql(
            """
            INSERT INTO core.connector_checkpoint (tenant_id, connector_id, stream, cursor, %s)
            VALUES (current_setting('app.tenant_id')::uuid, :connectorId, :stream, :cursor::jsonb,
                    now())
            ON CONFLICT (tenant_id, connector_id, stream) DO UPDATE SET
              cursor = EXCLUDED.cursor, %s = now()
            """
                .formatted(timestampColumn, timestampColumn))
        .param("connectorId", connectorId)
        .param("stream", stream)
        .param("cursor", writeCursor(cursor))
        .update();
  }

  private Map<String, String> readCursor(String json) {
    try {
      return mapper.readValue(json, CURSOR_TYPE);
    } catch (JsonProcessingException e) {
      throw new IngestionException("failed to parse connector checkpoint cursor", e);
    }
  }

  private String writeCursor(Map<String, String> cursor) {
    try {
      return mapper.writeValueAsString(cursor);
    } catch (JsonProcessingException e) {
      throw new IngestionException("failed to serialize connector checkpoint cursor", e);
    }
  }
}
