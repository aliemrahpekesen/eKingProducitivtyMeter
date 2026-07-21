/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.persistence;

import com.eip.app.persistence.ServiceTokenRepository.ServiceTokenRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Hand-rolled in-memory fake for {@link ServiceTokenStore} (TestingStrategy §2), used by {@code
 * ServiceTokenServiceTest} instead of mocking the EIP-owned {@link ServiceTokenRepository}.
 */
public final class InMemoryServiceTokenStore implements ServiceTokenStore {

  private final Map<UUID, Row> rows = new LinkedHashMap<>();

  @Override
  public UUID insert(
      @Nullable UUID tenantId,
      String name,
      String tokenPrefix,
      String tokenHash,
      String role,
      List<String> permissionSubset,
      @Nullable UUID createdByMemberId,
      Instant expiresAt) {
    UUID id = UUID.randomUUID();
    rows.put(
        id,
        new Row(id, tenantId, name, tokenPrefix, role, permissionSubset, expiresAt, null, null));
    return id;
  }

  @Override
  public List<ServiceTokenRow> listForTenant(UUID tenantId) {
    return rows.values().stream()
        .filter(r -> tenantId.equals(r.tenantId))
        .map(Row::toView)
        .toList();
  }

  @Override
  public List<ServiceTokenRow> listPlatformScoped() {
    return rows.values().stream().filter(r -> r.tenantId == null).map(Row::toView).toList();
  }

  @Override
  public int revokeForTenant(UUID id, UUID tenantId) {
    Row row = rows.get(id);
    if (row == null || !tenantId.equals(row.tenantId) || row.revokedAt != null) {
      return 0;
    }
    rows.put(id, row.revoked());
    return 1;
  }

  @Override
  public int revokePlatformScoped(UUID id) {
    Row row = rows.get(id);
    if (row == null || row.tenantId != null || row.revokedAt != null) {
      return 0;
    }
    rows.put(id, row.revoked());
    return 1;
  }

  /**
   * Returns every row inserted so far, for test assertions this fake's interface alone cannot make
   * (e.g. the persisted {@code tenant_id}/{@code permission_subset} the service computed).
   *
   * @return all rows, insertion order
   */
  public List<Row> allRows() {
    return List.copyOf(rows.values());
  }

  /** One in-memory row, mirroring {@code core.service_token}'s shape. */
  public record Row(
      UUID id,
      @Nullable UUID tenantId,
      String name,
      String tokenPrefix,
      String role,
      List<String> permissionSubset,
      Instant expiresAt,
      @Nullable Instant lastUsedAt,
      @Nullable Instant revokedAt) {

    Row revoked() {
      return new Row(
          id,
          tenantId,
          name,
          tokenPrefix,
          role,
          permissionSubset,
          expiresAt,
          lastUsedAt,
          Instant.now());
    }

    ServiceTokenRow toView() {
      return new ServiceTokenRow(
          id,
          name,
          tokenPrefix,
          role,
          new ArrayList<>(permissionSubset),
          expiresAt,
          lastUsedAt,
          revokedAt != null,
          Instant.now());
    }
  }
}
