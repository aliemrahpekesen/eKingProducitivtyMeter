/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.persistence;

import com.eip.app.persistence.ServiceTokenRepository.ServiceTokenRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The subset of {@link ServiceTokenRepository} that {@code ServiceTokenService} depends on,
 * extracted so {@code ServiceTokenServiceTest} can exercise the service against a hand-rolled
 * in-memory fake rather than mocking an EIP-owned class (TestingStrategy §2: "No mocking of owned
 * types where avoidable... use the real object or a hand-rolled in-memory fake living next to the
 * production interface"). {@link ServiceTokenRepository} is the sole production implementation;
 * {@code findByHash}/{@code touchLastUsed} (the authentication-path methods {@code
 * ServiceTokenAuthenticationFilter} calls) are deliberately NOT part of this seam — that filter's
 * behavior is proven against a real database in {@code ServiceTokenAuthIntegrationTest}, not a
 * fake.
 */
public interface ServiceTokenStore {

  /**
   * Inserts a new service token row.
   *
   * @param tenantId the owning tenant, or {@code null} for a platform-scoped token
   * @param name operator-facing label
   * @param tokenPrefix the first characters after {@code eipt_}
   * @param tokenHash the SHA-256 hex digest of the full raw token
   * @param role the bound {@code Role} enum name
   * @param permissionSubset the narrowed permission subset (enum names), or empty for "all"
   * @param createdByMemberId the creating member, if known
   * @param expiresAt the token's expiry instant
   * @return the new row's id
   */
  UUID insert(
      @Nullable UUID tenantId,
      String name,
      String tokenPrefix,
      String tokenHash,
      String role,
      List<String> permissionSubset,
      @Nullable UUID createdByMemberId,
      Instant expiresAt);

  /**
   * Lists a tenant's own service tokens, newest first.
   *
   * @param tenantId the tenant
   * @return the tenant's tokens
   */
  List<ServiceTokenRow> listForTenant(UUID tenantId);

  /**
   * Lists platform-scoped service tokens, newest first.
   *
   * @return the platform-scoped tokens
   */
  List<ServiceTokenRow> listPlatformScoped();

  /**
   * Revokes a tenant-scoped token owned by the given tenant.
   *
   * @param id the token id
   * @param tenantId the owning tenant
   * @return rows updated (0 if not found under this tenant, or already revoked)
   */
  int revokeForTenant(UUID id, UUID tenantId);

  /**
   * Revokes a platform-scoped token.
   *
   * @param id the token id
   * @return rows updated (0 if not found among platform-scoped tokens, or already revoked)
   */
  int revokePlatformScoped(UUID id);
}
