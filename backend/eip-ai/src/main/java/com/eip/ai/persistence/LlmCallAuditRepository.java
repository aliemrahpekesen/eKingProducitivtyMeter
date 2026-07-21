/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai.persistence;

import com.eip.core.domain.UuidV7Generator;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Insert-only store over {@code ai.llm_call_audit} (V7 migration, ADR-024): one row per call
 * attempt, hash-only (never prompt/response content). {@code ExplainService} writes exactly one row
 * per {@code explain}/{@code narrate} invocation, regardless of outcome.
 */
@Repository
public class LlmCallAuditRepository {

  private final JdbcClient jdbc;
  private final UuidV7Generator ids;

  /**
   * Creates the repository.
   *
   * @param jdbc the JDBC client
   * @param ids the platform's UUIDv7 id generator (the table has no {@code DEFAULT} on {@code id})
   */
  public LlmCallAuditRepository(JdbcClient jdbc, UuidV7Generator ids) {
    this.jdbc = jdbc;
    this.ids = ids;
  }

  /**
   * Inserts one audit row.
   *
   * @param purpose {@code "EXPLAIN_DASHBOARD"} | {@code "REPORT_NARRATIVE"}
   * @param provider the provider that was called
   * @param model the model that was requested
   * @param promptSha256 SHA-256 hex digest of the composed user prompt
   * @param promptChars character length of the composed user prompt
   * @param responseSha256 SHA-256 hex digest of the provider response text, or {@code null} if no
   *     response was received
   * @param responseChars character length of the provider response text, or {@code null}
   * @param latencyMs wall-clock latency of the attempt, in milliseconds
   * @param status {@code "OK"} | {@code "FAILED"} | {@code "REJECTED"}
   * @param error a content-free failure summary, or {@code null} on success
   */
  public void insert(
      String purpose,
      String provider,
      String model,
      String promptSha256,
      int promptChars,
      @Nullable String responseSha256,
      @Nullable Integer responseChars,
      int latencyMs,
      String status,
      @Nullable String error) {
    jdbc.sql(
            """
            INSERT INTO ai.llm_call_audit
              (id, tenant_id, purpose, provider, model, prompt_sha256, prompt_chars,
               response_sha256, response_chars, latency_ms, status, error)
            VALUES
              (:id, current_setting('app.tenant_id')::uuid, :purpose, :provider, :model,
               :promptSha256, :promptChars, :responseSha256, :responseChars, :latencyMs, :status,
               :error)
            """)
        .param("id", ids.generate())
        .param("purpose", purpose)
        .param("provider", provider)
        .param("model", model)
        .param("promptSha256", promptSha256)
        .param("promptChars", promptChars)
        .param("responseSha256", responseSha256)
        .param("responseChars", responseChars)
        .param("latencyMs", latencyMs)
        .param("status", status)
        .param("error", error)
        .update();
  }
}
