/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.security;

import com.eip.app.application.EffectivePermissionResolver;
import com.eip.app.tenant.HeaderTenantResolver;
import com.eip.tenancy.rbac.Permission;
import com.eip.tenancy.rbac.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
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
 * security context — the configured roles-claim path ({@link EipOidcClaimProperties#rolesClaim()},
 * {@code realm_access.roles} by default) intersected with {@link Role} names (unknown roles
 * ignored, never widened into a role EIP doesn't know), tenant from the configured tenant claim
 * ({@link EipOidcClaimProperties#tenantClaim()}, guaranteed present and a valid UUID by {@link
 * EipTenantClaimValidator}, which runs during token validation, before authentication succeeds). A
 * non-JWT authentication (permitAll paths this filter still runs over, e.g. {@code GET
 * /api/v1/session/auth}) resolves to {@link EipPrincipal#anonymous()}.
 *
 * <p><b>tenant role-permission overrides (SecurityModel §4, DEBT-012 residual part 2, ADR-026):</b>
 * both modes' permission set is computed via {@link EffectivePermissionResolver}, not {@link
 * Role#permissions()} directly — the resolver applies the tenant's stored overrides on top of each
 * role's fixed default set. A platform-scoped {@code header}-mode request (no tenant bound) and a
 * (belt-and-suspenders) tenant-less {@code oidc} branch fall back to the raw role defaults, since
 * an override lookup needs a tenant to scope to.
 *
 * <p><b>resource-level team scope (SecurityModel §4 layer 2):</b> a manager-scope role holder
 * ({@link #MANAGER_SCOPE_ROLES}) may additionally carry an {@code eip_teams} claim — a JSON array
 * of team-id strings — resolved into {@link EipPrincipal#scopedTeamIds()}. Scoping is deliberately
 * gated on role: SecurityModel §4 names {@code ENGINEERING_MANAGER}/{@code TEAM_LEAD}/{@code
 * RELEASE_MANAGER} as "manager-scope templates" and its layer-2 example ("an ENGINEERING_MANAGER on
 * Team A cannot read Team B's delivery-risk detail") is itself manager-specific — every other
 * role's scope is either platform/tenant-wide (PLATFORM_ADMIN, TENANT_ADMIN) or already "own teams
 * only" by definition in a way this claim does not further narrow (MEMBER) or is org-wide by design
 * (EXECUTIVE_VIEWER, ANALYST, VIEWER, SECURITY_AUDITOR). A non-manager-scope caller presenting
 * {@code eip_teams} anyway has it ignored (empty {@code scopedTeamIds}, i.e. unrestricted) — never
 * widened, mirroring how an unknown role name is ignored rather than trusted.
 */
@Component
@Order(9)
public class EipPrincipalFilter extends OncePerRequestFilter {

  /** The JWT claim carrying a manager-scope caller's team-scope restriction (SecurityModel §4). */
  static final String TEAMS_CLAIM = "eip_teams";

  /**
   * Roles whose scope a resource-level {@value #TEAMS_CLAIM} claim can narrow (SecurityModel §4).
   */
  private static final Set<Role> MANAGER_SCOPE_ROLES =
      Set.of(Role.ENGINEERING_MANAGER, Role.TEAM_LEAD, Role.RELEASE_MANAGER);

  private final EipSecurityProperties properties;
  private final EipOidcClaimProperties oidcClaimProperties;
  private final EffectivePermissionResolver effectivePermissionResolver;

  /**
   * Creates the filter.
   *
   * @param properties the active {@code eip.security.mode}
   * @param oidcClaimProperties the deployment-level tenant/roles claim-name overrides (DEBT-012
   *     residual)
   * @param effectivePermissionResolver resolves a role set's effective permissions adjusted by the
   *     tenant's role-permission overrides (SecurityModel §4, DEBT-012 residual part 2)
   */
  public EipPrincipalFilter(
      EipSecurityProperties properties,
      EipOidcClaimProperties oidcClaimProperties,
      EffectivePermissionResolver effectivePermissionResolver) {
    this.properties = properties;
    this.oidcClaimProperties = oidcClaimProperties;
    this.effectivePermissionResolver = effectivePermissionResolver;
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

  private EipPrincipal resolveHeader(HttpServletRequest request) {
    @Nullable String header = request.getHeader(HeaderTenantResolver.HEADER);
    @Nullable UUID tenantId = parseUuidOrNull(header);
    Set<Role> roles = Set.of(Role.TENANT_ADMIN);
    Set<Permission> permissions =
        tenantId == null
            ? Role.TENANT_ADMIN.permissions() // platform-scoped header request — no tenant, no
            // per-tenant override to look up (e.g. tenant creation)
            : effectivePermissionResolver.resolve(tenantId, roles);
    return new EipPrincipal(
        tenantId, roles, permissions, EipPrincipal.HEADER_MODE_SUBJECT, Set.of());
  }

  private EipPrincipal resolveOidc() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
      return EipPrincipal.anonymous();
    }
    Jwt jwt = jwtAuth.getToken();
    Set<Role> roles = rolesFromJwt(jwt, oidcClaimProperties.rolesClaim());
    @Nullable UUID tenantId =
        parseUuidOrNull(jwt.getClaimAsString(oidcClaimProperties.tenantClaim()));
    Set<Permission> permissions =
        tenantId == null
            ? unionOfDefaults(roles) // belt-and-suspenders — EipTenantClaimValidator already
            // guarantees a valid tenant claim for any authenticated oidc request
            : effectivePermissionResolver.resolve(tenantId, roles);
    Set<UUID> scopedTeamIds = teamScopeFromJwt(jwt, roles);
    return new EipPrincipal(tenantId, roles, permissions, jwt.getSubject(), scopedTeamIds);
  }

  private static Set<Permission> unionOfDefaults(Set<Role> roles) {
    Set<Permission> permissions = EnumSet.noneOf(Permission.class);
    roles.forEach(role -> permissions.addAll(role.permissions()));
    return permissions;
  }

  /**
   * Resolves the {@value #TEAMS_CLAIM} claim into {@link EipPrincipal#scopedTeamIds()} — but only
   * for a manager-scope role holder ({@link #MANAGER_SCOPE_ROLES}, SecurityModel §4). Any other
   * caller presenting the claim has it ignored, resolving to the unrestricted empty set — the claim
   * only ever narrows a manager-scope role's already-scoped access, never widens or restricts a
   * role this platform does not treat as team-scoped.
   */
  private static Set<UUID> teamScopeFromJwt(Jwt jwt, Set<Role> roles) {
    if (Collections.disjoint(roles, MANAGER_SCOPE_ROLES)) {
      return Set.of();
    }
    Object raw = jwt.getClaim(TEAMS_CLAIM);
    if (!(raw instanceof List<?> list)) {
      return Set.of();
    }
    Set<UUID> teamIds = new LinkedHashSet<>();
    for (Object entry : list) {
      if (entry instanceof String text) {
        @Nullable UUID teamId = parseUuidOrNull(text);
        if (teamId != null) {
          teamIds.add(teamId);
        }
      }
    }
    return Set.copyOf(teamIds);
  }

  /**
   * Reads the role-name list at a dot-separated claim path (see {@link
   * EipOidcClaimProperties#rolesClaim()}), walking nested maps down to the final segment.
   *
   * @param jwt the validated token
   * @param rolesClaimPath the configured dot-separated path (e.g. {@code realm_access.roles})
   * @return the resolved, known {@link Role} set (unknown/foreign names ignored, never widened)
   */
  private static Set<Role> rolesFromJwt(Jwt jwt, String rolesClaimPath) {
    List<?> rawRoles = extractRoleNames(jwt, rolesClaimPath);
    Set<Role> roles = EnumSet.noneOf(Role.class);
    for (Object entry : rawRoles) {
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

  // No Guava dependency in this module (mirrors Role's own ImmutableEnumChecker suppression
  // rationale) — String.split's documented edge cases (empty input, trailing separators) do not
  // apply to a validated, non-blank @ConfigurationProperties path made of simple claim-name
  // segments, so a plain literal-dot split is safe here.
  @SuppressWarnings("StringSplitter")
  private static List<?> extractRoleNames(Jwt jwt, String rolesClaimPath) {
    String[] segments = rolesClaimPath.split("\\.");
    if (segments.length == 0) {
      return List.of();
    }
    if (segments.length == 1) {
      Object raw = jwt.getClaim(segments[0]);
      return raw instanceof List<?> list ? list : List.of();
    }
    @Nullable Map<String, Object> current = jwt.getClaimAsMap(segments[0]);
    for (int i = 1; i < segments.length - 1 && current != null; i++) {
      Object next = current.get(segments[i]);
      current = next instanceof Map<?, ?> nested ? castStringKeyedMap(nested) : null;
    }
    if (current == null) {
      return List.of();
    }
    Object raw = current.get(segments[segments.length - 1]);
    return raw instanceof List<?> list ? list : List.of();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castStringKeyedMap(Map<?, ?> map) {
    // Every claim map JJWT/Nimbus hand back is JSON-object-derived, so keys are always String —
    // this cast is a shape assertion, not a type-safety gap.
    return (Map<String, Object>) map;
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
