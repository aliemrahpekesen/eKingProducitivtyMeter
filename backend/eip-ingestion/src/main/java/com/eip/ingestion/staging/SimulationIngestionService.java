/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.staging;

import com.eip.connectors.spi.Connector;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Drives one connector sync into raw staging on a tenant-bound {@link Connection}. The caller
 * (eip-app) owns the transaction and has already bound the RLS tenant GUC; this service resolves
 * the connector's {@code core.connector} row (creating it once per tenant) and streams the
 * connector's emitted records through a {@link StagingRawSink}. Running it twice with the same
 * source data is a no-op after the first — the sink's content-hash idempotency guarantees it.
 */
public final class SimulationIngestionService {

  private static final String SELECT_CONNECTOR =
      "SELECT id FROM core.connector WHERE type = ? AND deleted_at IS NULL"
          + " ORDER BY created_at LIMIT 1";

  private static final String INSERT_CONNECTOR =
      "INSERT INTO core.connector (tenant_id, type, name, status, simulation)"
          + " VALUES (current_setting('app.tenant_id')::uuid, ?, ?, 'ACTIVE', true) RETURNING id";

  private final RawPayloadCodec codec;

  /**
   * Creates the service over the app's shared JSON mapper.
   *
   * @param mapper the JSON mapper used to serialize raw payloads
   */
  public SimulationIngestionService(ObjectMapper mapper) {
    this.codec = new RawPayloadCodec(mapper);
  }

  /**
   * Ingests a connector's full emission into raw staging for the currently bound tenant.
   *
   * @param connection an open, tenant-bound connection inside a transaction
   * @param connector the connector to sync
   * @return the ingestion outcome (emitted / inserted / updated / unchanged)
   */
  public IngestionResult ingest(Connection connection, Connector connector) {
    try {
      UUID connectorId = ensureConnector(connection, connector.type());
      StagingRawSink sink = new StagingRawSink(connection, connectorId, codec);
      connector.sync(() -> sink);
      return new IngestionResult(sink.emitted(), sink.inserted(), sink.updated(), sink.unchanged());
    } catch (SQLException e) {
      throw new IngestionException("failed to ingest connector " + connector.type(), e);
    }
  }

  /**
   * Returns the tenant's connector row id for {@code type}, creating it once if absent. RLS scopes
   * both the lookup and the insert to the bound tenant.
   *
   * @param connection the tenant-bound connection
   * @param type the connector type discriminator
   * @return the {@code core.connector.id}
   * @throws SQLException on a database error
   */
  public UUID ensureConnector(Connection connection, String type) throws SQLException {
    try (PreparedStatement select = connection.prepareStatement(SELECT_CONNECTOR)) {
      select.setString(1, type);
      try (ResultSet rs = select.executeQuery()) {
        if (rs.next()) {
          return rs.getObject(1, UUID.class);
        }
      }
    }
    try (PreparedStatement insert = connection.prepareStatement(INSERT_CONNECTOR)) {
      insert.setString(1, type);
      insert.setString(2, "Simulation Source");
      try (ResultSet rs = insert.executeQuery()) {
        if (!rs.next()) {
          throw new IngestionException("connector insert returned no id", null);
        }
        return rs.getObject(1, UUID.class);
      }
    }
  }
}
