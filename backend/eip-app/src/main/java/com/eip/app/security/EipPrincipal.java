/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.security;

import com.eip.tenancy.rbac.Permission;
import com.eip.tenancy.rbac.Role;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The authenticated caller's identity + effective RBAC permission set for the current request
 * (SecurityModel §3/§4), resolved per mode by {@link EipPrincipalFilter} and read by {@link
 * PermissionEnforcementInterceptor}. {@code header} mode always resolves one (implicit {@code
 * TENANT_ADMIN}, SecurityModel demo convenience); {@code oidc} mode resolves it from the validated
 * JWT's claims.
 *
 * @param tenantId the tenant the request claims, if resolvable yet — {@code null} for a header-mode
 *     request naming no tenant (e.g. platform-scoped tenant creation), never used for RLS binding
 *     (that remains {@link com.eip.tenancy.context.TenantContextHolder}'s job via the {@link
 *     com.eip.app.tenant.TenantResolver} seam; this field is informational only)
 * @param roles the caller's resolved roles (unknown JWT realm roles are ignored, never widened)
 * @param permissions the union of {@code roles}' {@link Role#permissions()} (adjusted by any
 *     tenant-editable role-permission overrides, SecurityModel §4) — what {@link
 *     PermissionEnforcementInterceptor} actually checks
 * @param subject the caller's subject (JWT {@code sub}, or a fixed demo label in header mode) — for
 *     logs/audit only, never trusted as a tenant or permission source
 * @param scopedTeamIds resource-level team scope (SecurityModel §4 layer 2): empty means
 *     unrestricted (every existing caller's behavior, unchanged — the default every
 *     non-manager-scope principal resolves to); non-empty restricts the caller to those teams'
 *     data, enforced by the application/composition layer (not by permission checks, which stay
 *     role-based). Only ever populated for a manager-scope role holder presenting the {@code
 *     eip_teams} JWT claim ({@link EipPrincipalFilter}) — see that class's javadoc for the exact
 *     gating rule.
 */
public record EipPrincipal(
    @Nullable UUID tenantId,
    Set<Role> roles,
    Set<Permission> permissions,
    String subject,
    Set<UUID> scopedTeamIds) {

  /** Subject label for the header-mode demo convenience principal. */
  public static final String HEADER_MODE_SUBJECT = "header-mode-demo";

  /** Subject label for a request with no resolvable principal (e.g. an unauthenticated path). */
  public static final String ANONYMOUS_SUBJECT = "anonymous";

  /**
   * The no-permissions principal used when no authentication is present (permitAll paths this
   * filter still runs over) — deny-by-default falls through to {@link
   * PermissionEnforcementInterceptor}'s missing-permission check exactly as for any other caller.
   *
   * @return an anonymous, permission-less principal
   */
  public static EipPrincipal anonymous() {
    return new EipPrincipal(null, Set.of(), Set.of(), ANONYMOUS_SUBJECT, Set.of());
  }

  /**
   * Returns whether this principal holds at least one of the given permissions.
   *
   * @param required the any-of permission set required by the endpoint
   * @return {@code true} if {@link #permissions()} intersects {@code required}
   */
  public boolean hasAnyOf(Set<Permission> required) {
    return required.stream().anyMatch(permissions::contains);
  }
}
