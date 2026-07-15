/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.secrets;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Stores and reveals tenant secrets as AES-256-GCM envelopes in {@code core.secret} (ADR-014).
 * Callers run inside a tenant-bound transaction ({@code TenantTransactionRunner}); the row's {@code
 * tenant_id} comes from the RLS GUC so a secret can only be written/read under its own tenant.
 * {@code reveal} exists for in-process consumers only (connector sync) — no API layer may ever
 * expose it.
 */
public class SecretsService {

  private final JdbcClient jdbc;
  private final byte[] kek;
  private final int kekVersion;

  /**
   * Creates the service.
   *
   * @param jdbc the shared JDBC client (joins the caller's tenant-bound transaction)
   * @param kek the 32-byte key-encryption key (from the environment; never logged)
   * @param kekVersion the KEK version recorded on each row
   */
  public SecretsService(JdbcClient jdbc, byte[] kek, int kekVersion) {
    if (kek.length != 32) {
      throw new IllegalArgumentException("KEK must be exactly 32 bytes (AES-256)");
    }
    this.jdbc = jdbc;
    this.kek = kek.clone();
    this.kekVersion = kekVersion;
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
   * Reveals a stored secret for in-process use (never exposed through any API).
   *
   * @param secretId the {@code core.secret.id}
   * @return the plaintext
   */
  public String reveal(UUID secretId) {
    return jdbc.sql(
            "SELECT ciphertext, dek_wrapped FROM core.secret WHERE id = :id AND deleted_at IS NULL")
        .param("id", secretId)
        .query(
            (rs, rowNum) -> {
              byte[] dek = EnvelopeCipher.open(kek, rs.getBytes("dek_wrapped"));
              return EnvelopeCipher.openText(dek, rs.getBytes("ciphertext"));
            })
        .single();
  }
}
