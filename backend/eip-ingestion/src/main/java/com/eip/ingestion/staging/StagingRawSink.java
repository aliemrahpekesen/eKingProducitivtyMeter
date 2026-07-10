/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.staging;

import com.eip.connectors.spi.RawRecord;
import com.eip.connectors.spi.RawSink;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Persists emitted {@link RawRecord}s into {@code staging.raw_simulation} on a tenant-bound {@link
 * Connection}. Idempotency is content-hash based per {@code (connector, stream, natural_key)} — the
 * row's {@code tenant_id} is taken from {@code current_setting('app.tenant_id')}, so a row can only
 * be written under the RLS tenant already bound on the connection:
 *
 * <ul>
 *   <li>no existing row → INSERT (counted {@code inserted});
 *   <li>existing row, hash differs → UPDATE payload + hash + {@code ingested_at} ({@code updated});
 *   <li>existing row, hash equal → skip ({@code unchanged}) — the replay no-op.
 * </ul>
 *
 * <p>Not thread-safe: one sink drives one single-threaded connector sync inside one transaction.
 */
public final class StagingRawSink implements RawSink {

  private static final String SELECT_HASH =
      "SELECT content_hash FROM staging.raw_simulation"
          + " WHERE connector_id = ? AND stream = ? AND natural_key = ?";

  private static final String INSERT =
      "INSERT INTO staging.raw_simulation"
          + " (tenant_id, connector_id, stream, natural_key, source_system, source_instance,"
          + " external_id, op, fetch_kind, payload, content_hash)"
          + " VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)";

  private static final String UPDATE =
      "UPDATE staging.raw_simulation"
          + " SET source_system = ?, source_instance = ?, external_id = ?, op = ?, fetch_kind = ?,"
          + " payload = ?::jsonb, content_hash = ?, ingested_at = now()"
          + " WHERE connector_id = ? AND stream = ? AND natural_key = ?";

  private final Connection connection;
  private final UUID connectorId;
  private final RawPayloadCodec codec;

  private int emitted;
  private int inserted;
  private int updated;
  private int unchanged;

  /**
   * Creates a sink writing under the given connector on an already tenant-bound connection.
   *
   * @param connection an open connection inside a transaction with {@code app.tenant_id} bound
   * @param connectorId the owning {@code core.connector.id}
   * @param codec the payload/hash codec
   */
  public StagingRawSink(Connection connection, UUID connectorId, RawPayloadCodec codec) {
    this.connection = connection;
    this.connectorId = connectorId;
    this.codec = codec;
  }

  @Override
  public void emit(RawRecord record) {
    emitted++;
    String json = codec.toCanonicalJson(record.payload());
    byte[] hash = codec.contentHash(json);
    try {
      byte @Nullable [] existing = existingHash(record);
      if (existing == null) {
        insert(record, json, hash);
        inserted++;
      } else if (!Arrays.equals(existing, hash)) {
        update(record, json, hash);
        updated++;
      } else {
        unchanged++;
      }
    } catch (SQLException e) {
      throw new IngestionException(
          "failed to stage " + record.stream() + " " + record.naturalKey(), e);
    }
  }

  private byte @Nullable [] existingHash(RawRecord record) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(SELECT_HASH)) {
      ps.setObject(1, connectorId);
      ps.setString(2, record.stream());
      ps.setString(3, record.naturalKey());
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getBytes(1) : null;
      }
    }
  }

  private void insert(RawRecord record, String json, byte[] hash) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(INSERT)) {
      ps.setObject(1, connectorId);
      ps.setString(2, record.stream());
      ps.setString(3, record.naturalKey());
      ps.setString(4, record.sourceSystem());
      ps.setString(5, record.sourceInstance());
      ps.setString(6, record.externalId());
      ps.setString(7, record.op().name().toLowerCase(Locale.ROOT));
      ps.setString(8, record.fetchKind().name().toLowerCase(Locale.ROOT));
      ps.setString(9, json);
      ps.setBytes(10, hash);
      ps.executeUpdate();
    }
  }

  private void update(RawRecord record, String json, byte[] hash) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(UPDATE)) {
      ps.setString(1, record.sourceSystem());
      ps.setString(2, record.sourceInstance());
      ps.setString(3, record.externalId());
      ps.setString(4, record.op().name().toLowerCase(Locale.ROOT));
      ps.setString(5, record.fetchKind().name().toLowerCase(Locale.ROOT));
      ps.setString(6, json);
      ps.setBytes(7, hash);
      ps.setObject(8, connectorId);
      ps.setString(9, record.stream());
      ps.setString(10, record.naturalKey());
      ps.executeUpdate();
    }
  }

  /** Returns the total records emitted so far. */
  public int emitted() {
    return emitted;
  }

  /** Returns the count of newly staged rows. */
  public int inserted() {
    return inserted;
  }

  /** Returns the count of rows whose payload changed. */
  public int updated() {
    return updated;
  }

  /** Returns the count of idempotent no-op re-emissions. */
  public int unchanged() {
    return unchanged;
  }
}
