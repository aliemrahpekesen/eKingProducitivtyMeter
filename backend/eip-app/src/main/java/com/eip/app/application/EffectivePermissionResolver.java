/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.application;

import com.eip.tenancy.rbac.Permission;
import com.eip.tenancy.rbac.Role;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves a principal's effective {@link Permission} set for a given tenant and role set — {@link
 * Role#permissions()} adjusted by that tenant's editable overrides (SecurityModel §4, DEBT-012
 * residual part 2, ADR-026). Extracted as a seam so {@code EipPrincipalFilter} (which must call
 * this on every {@code oidc}/{@code header} request, before {@code
 * com.eip.tenancy.context.TenantContextHolder} is necessarily bound) can be unit-tested against a
 * trivial no-override fake instead of a real {@code TenantTransactionRunner} + database
 * (TestingStrategy §2). {@link RolePermissionOverrideService} is the sole production
 * implementation.
 */
public interface EffectivePermissionResolver {

  /**
   * Resolves the effective permission set for the given tenant and role set.
   *
   * @param tenantId the tenant whose overrides apply
   * @param roles the principal's resolved roles (usually one, but not assumed to be)
   * @return the union, across {@code roles}, of each role's default permissions adjusted by that
   *     tenant's overrides for that role; empty if {@code roles} is empty
   */
  Set<Permission> resolve(UUID tenantId, Set<Role> roles);
}
