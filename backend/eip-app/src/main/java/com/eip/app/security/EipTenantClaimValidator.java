/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.security;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Validates that a JWT carries the {@value #TENANT_CLAIM} custom claim as a well-formed UUID
 * (SecurityModel §4: the OIDC tenant claim). Composed with the standard issuer/timestamp validators
 * on the {@code oidc}-mode {@link org.springframework.security.oauth2.jwt.JwtDecoder} bean, so a
 * token failing this check never reaches an authenticated {@link
 * org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken} — the
 * resource-server filter raises the failure through the resource-server's {@code
 * AuthenticationEntryPoint} (SecurityModel: "token carries no tenant" 401), and {@code
 * com.eip.app.tenant.OidcTenantResolver}/{@link EipPrincipalFilter} can then trust the claim is
 * present and valid whenever authentication has succeeded.
 */
public final class EipTenantClaimValidator implements OAuth2TokenValidator<Jwt> {

  /** The custom JWT claim carrying the caller's tenant id (SecurityModel §4). */
  public static final String TENANT_CLAIM = "eip_tenant";

  private static final OAuth2Error NO_TENANT_ERROR =
      new OAuth2Error("invalid_token", "token carries no tenant", null);

  @Override
  public OAuth2TokenValidatorResult validate(Jwt token) {
    @Nullable String claim = token.getClaimAsString(TENANT_CLAIM);
    if (claim == null || claim.isBlank() || !isUuid(claim)) {
      return OAuth2TokenValidatorResult.failure(NO_TENANT_ERROR);
    }
    return OAuth2TokenValidatorResult.success();
  }

  private static boolean isUuid(String value) {
    try {
      UUID.fromString(value.trim());
      return true;
    } catch (IllegalArgumentException notAUuid) {
      return false;
    }
  }
}
