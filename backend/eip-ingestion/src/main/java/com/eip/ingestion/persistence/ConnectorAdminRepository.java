/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.persistence;

import com.eip.ingestion.api.IngestionException;
import com.eip.ingestion.api.ManageConnectorsUseCase.ConnectorAdminView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Tenant-scoped admin adapter over {@code core.connector}. Config is non-secret jsonb; the secret
 * lives envelope-encrypted in {@code core.secret} and only its presence is ever exposed.
 */
@Repository
public class ConnectorAdminRepository {

  private static final TypeReference<Map<String, String>> CONFIG_TYPE = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final ObjectMapper mapper;

  public ConnectorAdminRepository(JdbcClient jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  /**
   * Inserts a connector registration.
   *
   * @param type catalog type
   * @param name operator-facing name
   * @param config non-secret configuration
   * @param secretId link to the stored envelope secret, or null
   * @param simulation whether the source is simulated
   * @return the created row's admin view
   */
  public ConnectorAdminView insert(
      String type,
      String name,
      Map<String, String> config,
      @Nullable UUID secretId,
      boolean simulation) {
    UUID id =
        jdbc.sql(
                """
                INSERT INTO core.connector (tenant_id, type, name, config, secret_id, status,
                                            simulation)
                VALUES (current_setting('app.tenant_id')::uuid, :type, :name, :config::jsonb,
                        :secretId, 'CONFIGURED', :simulation)
                RETURNING id
                """)
            .param("type", type)
            .param("name", name)
            .param("config", toJson(config))
            .param("secretId", secretId)
            .param("simulation", simulation)
            .query(UUID.class)
            .single();
    return new ConnectorAdminView(
        id, type, name, "CONFIGURED", simulation, config, secretId != null);
  }

  /**
   * Lists the tenant's connectors with admin detail, newest first.
   *
   * @return the admin views (no secret material)
   */
  public List<ConnectorAdminView> list() {
    return jdbc.sql(
            """
            SELECT id, type, name, status, simulation, config::text, secret_id
            FROM core.connector WHERE deleted_at IS NULL ORDER BY created_at DESC, id
            """)
        .query(this::mapRow)
        .list();
  }

  /**
   * Loads one connector.
   *
   * @param id the connector id
   * @return the admin view, if visible under the current tenant
   */
  public Optional<ConnectorAdminView> find(UUID id) {
    return jdbc.sql(
            """
            SELECT id, type, name, status, simulation, config::text, secret_id
            FROM core.connector WHERE id = :id AND deleted_at IS NULL
            """)
        .param("id", id)
        .query(this::mapRow)
        .optional();
  }

  /** A row with its secret link (in-process use only). */
  public record RowWithSecret(ConnectorAdminView view, @Nullable UUID secretId) {}

  /**
   * Loads one connector together with its secret link (for sync/test flows only).
   *
   * @param id the connector id
   * @return the row, if visible under the current tenant
   */
  public Optional<RowWithSecret> findWithSecret(UUID id) {
    return jdbc.sql(
            """
            SELECT id, type, name, status, simulation, config::text, secret_id
            FROM core.connector WHERE id = :id AND deleted_at IS NULL
            """)
        .param("id", id)
        .query(
            (rs, rowNum) ->
                new RowWithSecret(mapRow(rs, rowNum), rs.getObject("secret_id", UUID.class)))
        .optional();
  }

  /**
   * Updates a connector's lifecycle status.
   *
   * @param id the connector id
   * @param status the new status
   * @return rows updated (0 when not visible under the current tenant)
   */
  public int updateStatus(UUID id, String status) {
    return jdbc.sql(
            """
            UPDATE core.connector SET status = :status, updated_at = now()
            WHERE id = :id AND deleted_at IS NULL
            """)
        .param("status", status)
        .param("id", id)
        .update();
  }

  private ConnectorAdminView mapRow(java.sql.ResultSet rs, int rowNum)
      throws java.sql.SQLException {
    return new ConnectorAdminView(
        rs.getObject("id", UUID.class),
        rs.getString("type"),
        rs.getString("name"),
        rs.getString("status"),
        rs.getBoolean("simulation"),
        fromJson(rs.getString(6)),
        rs.getObject("secret_id") != null);
  }

  private String toJson(Map<String, String> config) {
    try {
      return mapper.writeValueAsString(config);
    } catch (JsonProcessingException e) {
      throw new IngestionException("failed to serialize connector config", e);
    }
  }

  private Map<String, String> fromJson(@Nullable String json) {
    try {
      return json == null ? Map.of() : mapper.readValue(json, CONFIG_TYPE);
    } catch (JsonProcessingException e) {
      throw new IngestionException("failed to parse connector config", e);
    }
  }
}
