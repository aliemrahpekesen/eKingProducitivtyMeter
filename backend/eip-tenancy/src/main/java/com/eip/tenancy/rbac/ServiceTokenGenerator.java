/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.rbac;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generates and hashes service-token bearer values (SecurityModel §3, ADR-025): a raw token is
 * shown to its creator exactly once and never stored; only its SHA-256 hash is persisted
 * (`core.service_token.token_hash`), and a presented token is validated by re-hashing and comparing
 * — the same one-way pattern as password storage, deliberately distinct from the reversible
 * AES-256-GCM envelope encryption {@code core.secret}/{@code SecretsService} use (SecurityModel
 * §6), since a service token is never read back in plaintext after creation.
 *
 * <p>Plain, dependency-free class (no Spring stereotype) mirroring this module's {@code rbac}
 * package convention (see {@link Role}'s javadoc) — wired as a {@code @Bean} by the composition
 * root ({@code eip-app}), the same pattern {@code SecretsService} uses.
 */
public final class ServiceTokenGenerator {

  /** The stable prefix every generated raw token carries (SecurityModel §3). */
  public static final String TOKEN_PREFIX = "eipt_";

  /**
   * Random bytes per token: 24 bytes (192 bits) base64url-encodes to exactly 32 characters with no
   * padding, matching the SecurityModel §3 token shape ({@code "eipt_" + 32 chars}).
   */
  private static final int RANDOM_BYTES = 24;

  private final SecureRandom random = new SecureRandom();

  /**
   * Generates a fresh raw token. Never logged or persisted verbatim by any caller — only {@link
   * #hash(String)}'s digest is stored.
   *
   * @return {@code "eipt_"} followed by 32 URL-safe base64 characters
   */
  public String generateRawToken() {
    byte[] bytes = new byte[RANDOM_BYTES];
    random.nextBytes(bytes);
    return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * Computes the SHA-256 hex digest of a raw token, for storage or lookup-by-hash comparison.
   * Deterministic (no salt): a service token is itself already 192 bits of high-entropy random
   * material, so a per-token salt (needed against low-entropy inputs like passwords) adds no
   * defense here and would prevent the exact-match lookup {@code findByHash} depends on.
   *
   * @param rawToken the raw token value (e.g. as presented in an {@code Authorization} header)
   * @return the lowercase hex-encoded SHA-256 digest
   */
  public String hash(String rawToken) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] result = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(result);
    } catch (NoSuchAlgorithmException impossible) {
      // SHA-256 is a mandatory JCE algorithm on every conforming JVM (JLS platform guarantee).
      throw new IllegalStateException("SHA-256 MessageDigest unavailable", impossible);
    }
  }
}
