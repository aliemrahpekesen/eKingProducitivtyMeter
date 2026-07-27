/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The current session's "who am I": tenant identity plus the caller's effective RBAC permission set
 * (SecurityModel §4). Assembled in two stages by disjoint concerns — {@code GetSessionQuery} reads
 * the tenant identity ({@code tenantId} + {@code organizationName}) tenant-scoped under RLS, then
 * {@code SessionController} projects the request principal's effective permissions on top via
 * {@link #withEffectivePermissions(List)}. Persistence never touches permissions; the web layer
 * never touches the database.
 *
 * @param tenantId the resolved tenant
 * @param organizationName the tenant's organisation display name, if any (null when unseeded)
 * @param effectivePermissions the caller's effective permission wire ids ({@code
 *     Permission.wireId()}, e.g. {@code "tenant.manage"}) — the SAME set {@code
 *     PermissionEnforcementInterceptor} enforces, including tenant-editable role overrides. Sorted
 *     ascending for a byte-stable contract; always a list (empty, never null) so the frontend
 *     {@code <Can>} gate can read it unconditionally.
 */
public record SessionView(
    UUID tenantId, @Nullable String organizationName, List<String> effectivePermissions) {

  /**
   * Returns the tenant-identity-only view ({@code effectivePermissions} empty) that {@code
   * GetSessionQuery} produces — permissions are a request-scoped web-layer concern the query cannot
   * (and must not) resolve.
   *
   * @param tenantId the resolved tenant
   * @param organizationName the tenant's organisation display name, if any
   * @return the identity-only session view
   */
  public static SessionView identity(UUID tenantId, @Nullable String organizationName) {
    return new SessionView(tenantId, organizationName, List.of());
  }

  /**
   * Returns a copy of this identity view with the caller's effective permissions projected on — the
   * web-layer half of the two-stage assembly (see the class javadoc).
   *
   * @param effectivePermissions the caller's effective permission wire ids, sorted ascending
   * @return the enriched session view
   */
  public SessionView withEffectivePermissions(List<String> effectivePermissions) {
    return new SessionView(tenantId, organizationName, effectivePermissions);
  }
}
