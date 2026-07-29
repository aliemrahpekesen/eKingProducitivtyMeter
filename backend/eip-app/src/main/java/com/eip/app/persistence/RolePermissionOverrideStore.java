/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.persistence;

import com.eip.app.persistence.RolePermissionOverrideRepository.OverrideRow;
import java.util.Collection;
import java.util.List;

/**
 * The subset of {@link RolePermissionOverrideRepository} {@code RolePermissionOverrideService}
 * depends on, extracted so its unit tests can exercise the service against a hand-rolled in-memory
 * fake rather than mocking an EIP-owned class (TestingStrategy §2 — mirrors {@code
 * ServiceTokenStore}'s seam). {@link RolePermissionOverrideRepository} is the sole production
 * implementation, always RLS-scoped (V11, ADR-026) — the caller must run inside a tenant-bound
 * transaction ({@code TenantTransactionRunner}) for every method here.
 */
public interface RolePermissionOverrideStore {

  /**
   * Reads every override row for the given roles, in the current RLS-bound tenant. A single query
   * covering all roles (not one query per role) — the caller (a principal may hold multiple roles,
   * or the admin GET/PUT surface asking about one role) groups the result by {@link
   * OverrideRow#role()} itself.
   *
   * @param roles the {@code Role} enum names to look up (empty returns empty, no query executed)
   * @return every matching override row, in no particular order
   */
  List<OverrideRow> findForRoles(Collection<String> roles);

  /**
   * Inserts or updates a single (role, permission) override for the current RLS-bound tenant.
   *
   * @param role the {@code Role} enum name
   * @param permission the {@code Permission} enum name
   * @param granted {@code true} to grant the permission beyond the role's default, {@code false} to
   *     revoke a permission the role's default set includes
   */
  void upsert(String role, String permission, boolean granted);
}
