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

  private final EipTenantClaimValidator validator =
      new EipTenantClaimValidator(EipTenantClaimValidator.TENANT_CLAIM);

  @Test
  void succeeds_when_the_claim_is_a_well_formed_uuid() {
    OAuth2TokenValidatorResult result = validator.validate(jwt(UUID.randomUUID().toString()));

    assertThat(result.hasErrors()).isFalse();
  }

  @Test
  void a_non_default_configured_claim_name_is_honored() {
    // DEBT-012 residual (part 2): a deployment overriding eip.security.oidc.tenant-claim gets a
    // validator constructed with that name — proving the claim name is genuinely threaded through,
    // not hardcoded, at the unit level (the full end-to-end proof lives in the oidc integration
    // test).
    EipTenantClaimValidator custom = new EipTenantClaimValidator("custom_tenant_claim");
    UUID tenantId = UUID.randomUUID();

    OAuth2TokenValidatorResult underDefaultName =
        custom.validate(jwt(Map.of(EipTenantClaimValidator.TENANT_CLAIM, tenantId.toString())));
    assertThat(underDefaultName.hasErrors()).isTrue(); // wrong claim name — not found

    OAuth2TokenValidatorResult underConfiguredName =
        custom.validate(jwt(Map.of("custom_tenant_claim", tenantId.toString())));
    assertThat(underConfiguredName.hasErrors()).isFalse();
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
