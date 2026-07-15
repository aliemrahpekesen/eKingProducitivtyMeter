/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.tenant;

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
 * the validated JWT's {@value com.eip.app.security.EipTenantClaimValidator#TENANT_CLAIM} claim —
 * {@code X-EIP-Tenant} is never consulted, even if a caller sends one (a bogus header naming
 * another tenant is simply ignored; see {@code OidcRbacIntegrationTest}). The claim is guaranteed
 * present and a well-formed UUID whenever this runs, because {@link EipTenantClaimValidator} would
 * already have failed authentication otherwise — the {@link Optional#empty()} fallback here is
 * belt-and-suspenders, never expected to trigger in practice.
 *
 * <p>Replaces {@link HeaderTenantResolver} as the active {@link TenantResolver} bean whenever
 * {@code eip.security.mode=oidc}: the interface is the stable seam ({@link
 * com.eip.app.tenant.TenantContextFilter} needs no change to swap the source).
 */
@Component
@ConditionalOnProperty(prefix = "eip.security", name = "mode", havingValue = "oidc")
public class OidcTenantResolver implements TenantResolver {

  @Override
  public Optional<TenantContext> resolve(HttpServletRequest request) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
      return Optional.empty();
    }
    @Nullable String claim =
        jwtAuth.getToken().getClaimAsString(EipTenantClaimValidator.TENANT_CLAIM);
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
