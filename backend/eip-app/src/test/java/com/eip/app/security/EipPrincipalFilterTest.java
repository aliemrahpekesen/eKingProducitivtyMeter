/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.eip.app.application.EffectivePermissionResolver;
import com.eip.app.security.EipSecurityProperties.SecurityMode;
import com.eip.tenancy.rbac.Permission;
import com.eip.tenancy.rbac.Role;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Unit-tests {@link EipPrincipalFilter}'s {@code oidc}-mode resolution directly (no Spring context,
 * no database — the end-to-end proof over a real handler chain lives in {@code
 * OidcRbacIntegrationTest}): the deployment-level claim-name overrides (DEBT-012 residual, part 2)
 * and the manager-scope-gated {@code eip_teams} resource-scope claim (SecurityModel §4 layer 2).
 */
class EipPrincipalFilterTest {

  @Test
  void resolves_tenant_and_roles_under_a_non_default_configured_claim_name() throws Exception {
    // Booting with eip.security.oidc.tenant-claim=custom_tenant and roles-claim=custom_roles —
    // a JWT using those alternate names must still resolve tenant/roles correctly.
    EipPrincipalFilter filter =
        new EipPrincipalFilter(
            new EipSecurityProperties(SecurityMode.OIDC),
            new EipOidcClaimProperties("custom_tenant", "custom_roles"),
            noOverrideResolver());
    UUID tenantId = UUID.randomUUID();
    Jwt jwt =
        jwt(
            Map.of(
                "custom_tenant", tenantId.toString(),
                "custom_roles", List.of("TENANT_ADMIN")));
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

    EipPrincipal captured = runFilterAndCapture(filter);

    assertThat(captured.tenantId()).isEqualTo(tenantId);
    assertThat(captured.roles()).containsExactly(Role.TENANT_ADMIN);
    assertThat(captured.scopedTeamIds()).isEmpty();
  }

  @Test
  void resolves_a_nested_three_segment_roles_claim_path() throws Exception {
    // Generalizes beyond Keycloak's two-segment realm_access.roles default (e.g. a client-roles
    // mapping like resource_access.<client>.roles).
    EipPrincipalFilter filter =
        new EipPrincipalFilter(
            new EipSecurityProperties(SecurityMode.OIDC),
            new EipOidcClaimProperties(
                EipTenantClaimValidator.TENANT_CLAIM, "resource_access.eip-client.roles"),
            noOverrideResolver());
    Jwt jwt =
        jwt(
            Map.of(
                EipTenantClaimValidator.TENANT_CLAIM,
                UUID.randomUUID().toString(),
                "resource_access",
                Map.of("eip-client", Map.of("roles", List.of("VIEWER")))));
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

    EipPrincipal captured = runFilterAndCapture(filter);

    assertThat(captured.roles()).containsExactly(Role.VIEWER);
  }

  @Test
  void manager_scope_role_with_eip_teams_claim_resolves_scoped_team_ids() throws Exception {
    EipPrincipalFilter filter =
        new EipPrincipalFilter(
            new EipSecurityProperties(SecurityMode.OIDC),
            defaultClaimProperties(),
            noOverrideResolver());
    UUID teamA = UUID.randomUUID();
    Jwt jwt =
        jwt(
            Map.of(
                EipTenantClaimValidator.TENANT_CLAIM,
                UUID.randomUUID().toString(),
                "realm_access",
                Map.of("roles", List.of("ENGINEERING_MANAGER")),
                "eip_teams",
                List.of(teamA.toString())));
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

    EipPrincipal captured = runFilterAndCapture(filter);

    assertThat(captured.scopedTeamIds()).containsExactly(teamA);
  }

  @Test
  void non_manager_scope_role_with_eip_teams_claim_has_it_ignored() throws Exception {
    // Per SecurityModel §4's manager-scope-templates wording, the claim only narrows a
    // manager-scope role; a non-manager role presenting it anyway is unrestricted (empty set),
    // never widened or narrowed by an untrusted claim outside its documented meaning.
    EipPrincipalFilter filter =
        new EipPrincipalFilter(
            new EipSecurityProperties(SecurityMode.OIDC),
            defaultClaimProperties(),
            noOverrideResolver());
    Jwt jwt =
        jwt(
            Map.of(
                EipTenantClaimValidator.TENANT_CLAIM,
                UUID.randomUUID().toString(),
                "realm_access",
                Map.of("roles", List.of("MEMBER")),
                "eip_teams",
                List.of(UUID.randomUUID().toString())));
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

    EipPrincipal captured = runFilterAndCapture(filter);

    assertThat(captured.roles()).containsExactly(Role.MEMBER);
    assertThat(captured.scopedTeamIds()).isEmpty();
  }

  @Test
  void manager_scope_role_without_the_claim_is_unrestricted() throws Exception {
    EipPrincipalFilter filter =
        new EipPrincipalFilter(
            new EipSecurityProperties(SecurityMode.OIDC),
            defaultClaimProperties(),
            noOverrideResolver());
    Jwt jwt =
        jwt(
            Map.of(
                EipTenantClaimValidator.TENANT_CLAIM,
                UUID.randomUUID().toString(),
                "realm_access",
                Map.of("roles", List.of("TEAM_LEAD"))));
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

    EipPrincipal captured = runFilterAndCapture(filter);

    assertThat(captured.scopedTeamIds()).isEmpty();
  }

  @Test
  void a_non_jwt_authentication_resolves_to_anonymous() throws Exception {
    EipPrincipalFilter filter =
        new EipPrincipalFilter(
            new EipSecurityProperties(SecurityMode.OIDC),
            defaultClaimProperties(),
            noOverrideResolver());
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("someone", null));

    EipPrincipal captured = runFilterAndCapture(filter);

    assertThat(captured.subject()).isEqualTo(EipPrincipal.ANONYMOUS_SUBJECT);
    assertThat(captured.scopedTeamIds()).isEmpty();
  }

  private static EipOidcClaimProperties defaultClaimProperties() {
    return new EipOidcClaimProperties(
        EipTenantClaimValidator.TENANT_CLAIM, EipOidcClaimProperties.DEFAULT_ROLES_CLAIM);
  }

  /**
   * A trivial {@link EffectivePermissionResolver} fake mirroring "no overrides stored" — the union
   * of each role's own default {@link Role#permissions()}. Keeps this test fast and independent of
   * a real database (TestingStrategy §2); {@code RolePermissionOverrideService}'s own tests prove
   * the actual override-application logic.
   */
  private static EffectivePermissionResolver noOverrideResolver() {
    return (tenantId, roles) -> {
      Set<Permission> permissions = EnumSet.noneOf(Permission.class);
      roles.forEach(role -> permissions.addAll(role.permissions()));
      return permissions;
    };
  }

  /**
   * Runs the filter over a bare request/response and captures the {@link EipPrincipal} bound during
   * the (no-op) downstream chain — {@link EipPrincipalHolder} is cleared again once {@link
   * EipPrincipalFilter#doFilterInternal} returns, so it must be read from inside the chain
   * callback.
   */
  private static EipPrincipal runFilterAndCapture(EipPrincipalFilter filter) throws Exception {
    try {
      EipPrincipal[] captured = new EipPrincipal[1];
      filter.doFilter(
          new MockHttpServletRequest(),
          new MockHttpServletResponse(),
          (req, res) -> captured[0] = EipPrincipalHolder.current().orElseThrow());
      return captured[0];
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private static Jwt jwt(Map<String, Object> claims) {
    return Jwt.withTokenValue("token-value")
        .header("alg", "RS256")
        .claim("sub", "test-subject")
        .claims(c -> c.putAll(claims))
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(300))
        .build();
  }
}
