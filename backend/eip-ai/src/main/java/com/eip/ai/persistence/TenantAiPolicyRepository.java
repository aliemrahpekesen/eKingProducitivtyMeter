/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Store over {@code core.tenant_ai_policy} (V7 migration, ADR-024): one row per tenant, upserted on
 * every {@code ManageAiPolicyUseCase.update}. Every statement relies on the RLS GUC bound by the
 * caller's tenant-bound transaction, never an explicit {@code WHERE tenant_id = ...} parameter.
 */
@Repository
public class TenantAiPolicyRepository {

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc the JDBC client
   */
  public TenantAiPolicyRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Reads the current tenant's policy row, if one has ever been written.
   *
   * @return the row, or empty if the tenant has never configured a policy
   */
  public Optional<Row> find() {
    return jdbc.sql(
            """
            SELECT enabled, provider, base_url, model, secret_id, temperature, max_tokens
            FROM core.tenant_ai_policy WHERE tenant_id = current_setting('app.tenant_id')::uuid
            """)
        .query(TenantAiPolicyRepository::mapRow)
        .optional();
  }

  /**
   * Inserts or replaces the current tenant's policy row.
   *
   * @param enabled whether the layer is enabled
   * @param provider the configured provider, or {@code null}
   * @param baseUrl the configured base URL, or {@code null}
   * @param model the configured model, or {@code null}
   * @param secretId the {@code core.secret.id} backing the provider API key, or {@code null}
   * @param temperature the configured sampling temperature
   * @param maxTokens the configured maximum generation length, in tokens
   */
  public void upsert(
      boolean enabled,
      @Nullable String provider,
      @Nullable String baseUrl,
      @Nullable String model,
      @Nullable UUID secretId,
      double temperature,
      int maxTokens) {
    jdbc.sql(
            """
            INSERT INTO core.tenant_ai_policy
              (tenant_id, enabled, provider, base_url, model, secret_id, temperature, max_tokens)
            VALUES
              (current_setting('app.tenant_id')::uuid, :enabled, :provider, :baseUrl, :model,
               :secretId, :temperature, :maxTokens)
            ON CONFLICT (tenant_id) DO UPDATE SET
              enabled = EXCLUDED.enabled,
              provider = EXCLUDED.provider,
              base_url = EXCLUDED.base_url,
              model = EXCLUDED.model,
              secret_id = EXCLUDED.secret_id,
              temperature = EXCLUDED.temperature,
              max_tokens = EXCLUDED.max_tokens
            """)
        .param("enabled", enabled)
        .param("provider", provider)
        .param("baseUrl", baseUrl)
        .param("model", model)
        .param("secretId", secretId)
        .param("temperature", temperature)
        .param("maxTokens", maxTokens)
        .update();
  }

  private static Row mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new Row(
        rs.getBoolean("enabled"),
        rs.getString("provider"),
        rs.getString("base_url"),
        rs.getString("model"),
        (UUID) rs.getObject("secret_id"),
        rs.getBigDecimal("temperature").doubleValue(),
        rs.getInt("max_tokens"));
  }

  /** One {@code core.tenant_ai_policy} row. */
  public record Row(
      boolean enabled,
      @Nullable String provider,
      @Nullable String baseUrl,
      @Nullable String model,
      @Nullable UUID secretId,
      double temperature,
      int maxTokens) {}
}
