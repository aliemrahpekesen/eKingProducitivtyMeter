/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.security;

import com.eip.app.tenant.HeaderTenantResolver;
import com.eip.tenancy.rbac.Permission;
import com.eip.tenancy.rbac.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the request's {@link EipPrincipal} and binds it via {@link EipPrincipalHolder} for
 * {@link PermissionEnforcementInterceptor} to read (SecurityModel §3/§4). Runs after Spring
 * Security (JWT validation, when {@code oidc}) and just before {@link
 * com.eip.app.tenant.TenantContextFilter}, though the two are otherwise independent: this filter
 * governs permissions only, tenant binding stays the tenant filter's job.
 *
 * <p><b>service tokens (SecurityModel §3):</b> checked FIRST, regardless of {@code
 * eip.security.mode} — if {@link ServiceTokenAuthenticationFilter} has authenticated this request,
 * the security context holds a {@link ServiceTokenAuthentication}, whose already-resolved {@link
 * EipPrincipal} is used verbatim. This is the same seam the {@code oidc} JWT path populates, just a
 * different {@code Authentication} implementation — service tokens are mode-independent
 * (SecurityModel §3: "follow the same request path from the JWT-validation step onward").
 *
 * <p><b>header mode:</b> (no service token presented) always resolves an implicit {@code
 * TENANT_ADMIN} principal — a deliberate demo/dev convenience (SecurityModel: header resolution
 * carries no authentication) — regardless of whether {@code X-EIP-Tenant} is present, so
 * platform-scoped requests (e.g. tenant creation, which names no tenant) still pass RBAC the same
 * way every other header-mode request does. The same {@link PermissionEnforcementInterceptor} check
 * executes either way, so RBAC is genuinely exercised in header mode too, not bypassed.
 *
 * <p><b>oidc mode:</b> (no service token presented) reads the validated {@link Jwt} from the
 * security context — {@code realm_access.roles} intersected with {@link Role} names (unknown roles
 * ignored, never widened into a role EIP doesn't know), tenant from the {@code eip_tenant} custom
 * claim (guaranteed present and a valid UUID by {@link EipTenantClaimValidator}, which runs during
 * token validation, before authentication succeeds). A non-JWT authentication (permitAll paths this
 * filter still runs over, e.g. {@code GET /api/v1/session/auth}) resolves to {@link
 * EipPrincipal#anonymous()}.
 */
@Component
@Order(9)
public class EipPrincipalFilter extends OncePerRequestFilter {

  /** Realm-role claim key (Keycloak default mapper). */
  static final String REALM_ACCESS_CLAIM = "realm_access";

  /** Key inside {@value #REALM_ACCESS_CLAIM} carrying the role name list. */
  static final String ROLES_KEY = "roles";

  private final EipSecurityProperties properties;

  public EipPrincipalFilter(EipSecurityProperties properties) {
    this.properties = properties;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    EipPrincipalHolder.set(resolvePrincipal(request));
    try {
      chain.doFilter(request, response);
    } finally {
      EipPrincipalHolder.clear();
    }
  }

  private EipPrincipal resolvePrincipal(HttpServletRequest request) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth instanceof ServiceTokenAuthentication serviceTokenAuth) {
      return serviceTokenAuth.getPrincipal();
    }
    return properties.oidc() ? resolveOidc() : resolveHeader(request);
  }

  private static EipPrincipal resolveHeader(HttpServletRequest request) {
    @Nullable String header = request.getHeader(HeaderTenantResolver.HEADER);
    @Nullable UUID tenantId = parseUuidOrNull(header);
    return new EipPrincipal(
        tenantId,
        Set.of(Role.TENANT_ADMIN),
        Role.TENANT_ADMIN.permissions(),
        EipPrincipal.HEADER_MODE_SUBJECT);
  }

  private static EipPrincipal resolveOidc() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
      return EipPrincipal.anonymous();
    }
    Jwt jwt = jwtAuth.getToken();
    Set<Role> roles = rolesFromJwt(jwt);
    Set<Permission> permissions = EnumSet.noneOf(Permission.class);
    roles.forEach(role -> permissions.addAll(role.permissions()));
    @Nullable UUID tenantId =
        parseUuidOrNull(jwt.getClaimAsString(EipTenantClaimValidator.TENANT_CLAIM));
    return new EipPrincipal(tenantId, roles, permissions, jwt.getSubject());
  }

  private static Set<Role> rolesFromJwt(Jwt jwt) {
    @Nullable Map<String, Object> realmAccess = jwt.getClaimAsMap(REALM_ACCESS_CLAIM);
    if (realmAccess == null) {
      return Set.of();
    }
    Object rawRoles = realmAccess.get(ROLES_KEY);
    if (!(rawRoles instanceof List<?> list)) {
      return Set.of();
    }
    Set<Role> roles = EnumSet.noneOf(Role.class);
    for (Object entry : list) {
      if (entry instanceof String name) {
        try {
          roles.add(Role.valueOf(name));
        } catch (IllegalArgumentException unknownRole) {
          // Unknown/foreign realm role — ignored, never widened into an EIP role.
        }
      }
    }
    return roles;
  }

  private static @Nullable UUID parseUuidOrNull(@Nullable String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException notAUuid) {
      return null;
    }
  }
}
