/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.secrets;

import com.eip.tenancy.audit.api.AuditActorType;
import com.eip.tenancy.audit.api.AuditCategory;
import com.eip.tenancy.audit.api.AuditEvent;
import com.eip.tenancy.audit.api.AuditOutcome;
import com.eip.tenancy.audit.api.RecordAuditEventUseCase;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Stores and reveals tenant secrets as AES-256-GCM envelopes in {@code core.secret} (ADR-014).
 * Callers run inside a tenant-bound transaction ({@code TenantTransactionRunner}); the row's {@code
 * tenant_id} comes from the RLS GUC so a secret can only be written/read under its own tenant.
 * {@code reveal} exists for in-process consumers only (connector sync) — no API layer may ever
 * expose it.
 *
 * <p><strong>{@code reveal}'s audit write needs a READ-WRITE ambient transaction (DEBT-024 Wave
 * 3B).</strong> {@code reveal} never opens its own transaction — it relies entirely on the caller's
 * already-active tenant-bound one for both the row SELECT and, now, the {@code secret.revealed}
 * audit INSERT. If that ambient transaction is read-only (e.g. {@code
 * TenantTransactionRunner#readCurrent}/{@code #read} — the only production call site found, {@code
 * RealConnectorSyncService#sync}, uses exactly this), Postgres rejects the audit INSERT; {@code
 * AuditService} swallows that failure (WARN log + {@code eip.audit.write.failures} counter) so
 * {@code reveal} itself is unaffected, but the audit trail is then silently incomplete for that
 * call. See this wave's final report for the recommended follow-up (switch that call site to a
 * read-write transaction) — out of this retrofit's write-set to fix directly.
 */
public class SecretsService {

  private final JdbcClient jdbc;
  private final byte[] kek;
  private final int kekVersion;
  private final @Nullable RecordAuditEventUseCase audit;

  /**
   * Creates the service with no audit wiring. Retained so existing direct-construction callers in
   * other modules (eip-ingestion's/eip-ai's integration tests, out of this wave's write-set) keep
   * compiling and behaving unchanged — {@code reveal} simply skips the audit write when built this
   * way. Production wiring uses the fully-wired constructor below via {@code SecretsConfiguration}.
   *
   * @param jdbc the shared JDBC client (joins the caller's tenant-bound transaction)
   * @param kek the 32-byte key-encryption key (from the environment; never logged)
   * @param kekVersion the KEK version recorded on each row
   */
  public SecretsService(JdbcClient jdbc, byte[] kek, int kekVersion) {
    this(jdbc, kek, kekVersion, null);
  }

  /**
   * Creates the service with Wave 3B's {@code secret.revealed} audit wiring (DEBT-024,
   * SecurityModel §11 — reveal is the highest-sensitivity secrets event, distinct from ordinary
   * access).
   *
   * @param jdbc the shared JDBC client (joins the caller's tenant-bound transaction)
   * @param kek the 32-byte key-encryption key (from the environment; never logged)
   * @param kekVersion the KEK version recorded on each row
   * @param audit the audit write path, or {@code null} to disable audit recording
   */
  public SecretsService(
      JdbcClient jdbc, byte[] kek, int kekVersion, @Nullable RecordAuditEventUseCase audit) {
    if (kek.length != 32) {
      throw new IllegalArgumentException("KEK must be exactly 32 bytes (AES-256)");
    }
    this.jdbc = jdbc;
    this.kek = kek.clone();
    this.kekVersion = kekVersion;
    this.audit = audit;
  }

  /**
   * Envelope-encrypts and stores a secret for the current tenant.
   *
   * @param name operator-facing label (e.g. {@code connector:jira:token})
   * @param plaintext the secret value
   * @return the {@code core.secret.id} to link from the owning row
   */
  public UUID store(String name, String plaintext) {
    byte[] dek = EnvelopeCipher.newDek();
    byte[] ciphertext = EnvelopeCipher.sealText(dek, plaintext);
    byte[] wrapped = EnvelopeCipher.seal(kek, dek);
    return jdbc.sql(
            """
            INSERT INTO core.secret (tenant_id, name, ciphertext, dek_wrapped, kek_version, algo)
            VALUES (current_setting('app.tenant_id')::uuid, :name, :ciphertext, :dek, :kekVersion,
                    'AES-256-GCM')
            RETURNING id
            """)
        .param("name", name)
        .param("ciphertext", ciphertext)
        .param("dek", wrapped)
        .param("kekVersion", kekVersion)
        .query(UUID.class)
        .single();
  }

  /**
   * Reveals a stored secret for in-process use (never exposed through any API). Records a {@code
   * secret.revealed} audit event alongside — the actor is always {@link AuditActorType#WORKER}:
   * {@code reveal} is reachable only from in-process connector-sync/worker code, never a human
   * request (see class javadoc), so no member principal is ever meaningfully attributable here.
   *
   * @param secretId the {@code core.secret.id}
   * @return the plaintext
   */
  public String reveal(UUID secretId) {
    String plaintext =
        jdbc.sql(
                "SELECT ciphertext, dek_wrapped FROM core.secret WHERE id = :id AND deleted_at IS"
                    + " NULL")
            .param("id", secretId)
            .query(
                (rs, rowNum) -> {
                  byte[] dek = EnvelopeCipher.open(kek, rs.getBytes("dek_wrapped"));
                  return EnvelopeCipher.openText(dek, rs.getBytes("ciphertext"));
                })
            .single();
    if (audit != null) {
      audit.record(
          new AuditEvent(
              AuditCategory.SECRETS,
              "secret.revealed",
              AuditOutcome.SUCCESS,
              AuditActorType.WORKER,
              null,
              Map.of("secretId", secretId)));
    }
    return plaintext;
  }
}
