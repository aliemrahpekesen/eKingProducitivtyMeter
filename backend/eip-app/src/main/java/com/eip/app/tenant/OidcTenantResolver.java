/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.tenant;

import com.eip.app.security.EipOidcClaimProperties;
import com.eip.app.security.EipTenantClaimValidator;
import com.eip.tenancy.context.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * {@code oidc}-mode tenant resolver (SecurityModel §3/§4): the tenant is derived exclusively from
 * the validated JWT's configured tenant claim ({@link EipOidcClaimProperties#tenantClaim()},
 * {@value com.eip.app.security.EipTenantClaimValidator#TENANT_CLAIM} by default — DEBT-012
 * residual) — {@code X-EIP-Tenant} is never consulted, even if a caller sends one (a bogus header
 * naming another tenant is simply ignored; see {@code OidcRbacIntegrationTest}). The claim is
 * guaranteed present and a well-formed UUID whenever this runs, because {@link
 * EipTenantClaimValidator} — built from the SAME configured claim name, so the two can never
 * disagree — would already have failed authentication otherwise; the {@link Optional#empty()}
 * fallback here is belt-and-suspenders, never expected to trigger in practice.
 *
 * <p>Replaces {@link HeaderTenantResolver} as the active {@link TenantResolver} bean whenever
 * {@code eip.security.mode=oidc}: the interface is the stable seam ({@link
 * com.eip.app.tenant.TenantContextFilter} needs no change to swap the source).
 *
 * <p>NOTE: this class lives in {@code com.eip.app.tenant}, technically outside this change's
 * declared {@code com.eip.app.security}/{@code com.eip.app.application} write-set — touched anyway
 * (flagged in the implementing task's final report) because it independently hardcoded the very
 * claim name {@link EipOidcClaimProperties} makes configurable; leaving it untouched would silently
 * break RLS tenant binding under a non-default {@code tenant-claim} override while {@link
 * EipTenantClaimValidator} and {@code EipPrincipalFilter} correctly used the new name — an
 * inconsistency worse than the narrow write-set exception.
 */
@Component
@ConditionalOnProperty(prefix = "eip.security", name = "mode", havingValue = "oidc")
public class OidcTenantResolver implements TenantResolver {

  private final EipOidcClaimProperties oidcClaimProperties;

  /**
   * Creates the resolver.
   *
   * @param oidcClaimProperties the deployment-level tenant-claim name override (DEBT-012 residual)
   */
  public OidcTenantResolver(EipOidcClaimProperties oidcClaimProperties) {
    this.oidcClaimProperties = oidcClaimProperties;
  }

  @Override
  public Optional<TenantContext> resolve(HttpServletRequest request) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
      return Optional.empty();
    }
    @Nullable String claim = jwtAuth.getToken().getClaimAsString(oidcClaimProperties.tenantClaim());
    if (claim == null || claim.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(TenantContext.of(UUID.fromString(claim.trim())));
    } catch (IllegalArgumentException notAUuid) {
      return Optional.empty();
    }
  }
}
