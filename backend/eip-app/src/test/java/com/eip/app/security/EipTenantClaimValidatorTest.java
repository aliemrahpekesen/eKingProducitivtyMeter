/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** Unit-tests {@link EipTenantClaimValidator} in isolation, without a Spring context. */
class EipTenantClaimValidatorTest {

  private final EipTenantClaimValidator validator = new EipTenantClaimValidator();

  @Test
  void succeeds_when_the_claim_is_a_well_formed_uuid() {
    OAuth2TokenValidatorResult result = validator.validate(jwt(UUID.randomUUID().toString()));

    assertThat(result.hasErrors()).isFalse();
  }

  @Test
  void fails_when_the_claim_is_absent() {
    OAuth2TokenValidatorResult result = validator.validate(jwt(Map.of()));

    assertThat(result.hasErrors()).isTrue();
    assertThat(result.getErrors())
        .extracting("description")
        .containsExactly("token carries no tenant");
  }

  @Test
  void fails_when_the_claim_is_blank() {
    assertThat(validator.validate(jwt("   ")).hasErrors()).isTrue();
  }

  @Test
  void fails_when_the_claim_is_not_a_uuid() {
    assertThat(validator.validate(jwt("not-a-uuid")).hasErrors()).isTrue();
  }

  private static Jwt jwt(String tenantClaim) {
    return jwt(Map.of(EipTenantClaimValidator.TENANT_CLAIM, tenantClaim));
  }

  private static Jwt jwt(Map<String, Object> extraClaims) {
    return Jwt.withTokenValue("token-value")
        .header("alg", "RS256")
        .claim("sub", "test-subject")
        .claims(claims -> claims.putAll(extraClaims))
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(300))
        .build();
  }
}
