/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.api;

import java.util.List;
import java.util.UUID;

/**
 * Platform-level tenant administration ({@code core.tenant} is platform-scoped — the enumerated
 * no-RLS exception). Creating a tenant only registers it; its organisation structure is created
 * tenant-scoped through {@link ManageOrgStructureUseCase}.
 */
public interface ManageTenantsUseCase {

  /**
   * Registers a new tenant.
   *
   * @param name display name
   * @param slug unique URL-safe identifier
   * @return the created tenant
   */
  TenantView create(String name, String slug);

  /**
   * Lists all tenants, newest first.
   *
   * @return the tenants
   */
  List<TenantView> list();

  /**
   * One tenant row.
   *
   * @param id the tenant id (used as {@code X-EIP-Tenant} until OIDC lands)
   * @param name display name
   * @param slug unique identifier
   */
  record TenantView(UUID id, String name, String slug) {}
}
