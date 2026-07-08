/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.context;

import java.util.Objects;
import java.util.UUID;

/**
 * The tenant a unit of work executes as. Every request thread and every Kafka consumer binds a
 * {@code TenantContext} before touching tenant-scoped data; {@link RlsTenantBinder} projects it
 * onto the database connection as the {@code app.tenant_id} GUC so Postgres RLS enforces isolation
 * (DatabasePlan §5).
 *
 * @param tenantId the owning tenant (the {@code core.tenant.id})
 */
public record TenantContext(UUID tenantId) {

  public TenantContext {
    Objects.requireNonNull(tenantId, "tenantId");
  }

  /**
   * Convenience factory.
   *
   * @param tenantId the owning tenant id
   * @return the context
   */
  public static TenantContext of(UUID tenantId) {
    return new TenantContext(tenantId);
  }
}
