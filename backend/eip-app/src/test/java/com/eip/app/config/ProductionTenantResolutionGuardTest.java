/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

/**
 * Proves the {@code prod}/{@code preprod}-profile guard (DEBT-012, M5 Wave S1a): both profiles now
 * boot, but ONLY with real OIDC tenant resolution configured ({@code eip.security.mode=oidc} plus a
 * non-blank, non-{@code CHANGE_ME} {@code EIP_OIDC_ISSUER}); every other environment profile is
 * untouched by this guard regardless of mode.
 */
class ProductionTenantResolutionGuardTest {

  private static final String REAL_ISSUER = "https://idp.example.com/realms/eip";

  private final ProductionTenantResolutionGuard guard = new ProductionTenantResolutionGuard();
  private final SpringApplication application = new SpringApplication();

  @Test
  void prod_with_header_mode_fails() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");
    environment.setProperty("eip.security.mode", "header");

    assertThatThrownBy(() -> guard.postProcessEnvironment(environment, application))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OIDC")
        .hasMessageContaining("DEBT-012");
  }

  @Test
  void prod_with_oidc_mode_but_missing_issuer_fails() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");
    environment.setProperty("eip.security.mode", "oidc");
    // EIP_OIDC_ISSUER intentionally left unset.

    assertThatThrownBy(() -> guard.postProcessEnvironment(environment, application))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OIDC")
        .hasMessageContaining("DEBT-012");
  }

  @Test
  void prod_with_oidc_mode_and_change_me_issuer_fails() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");
    environment.setProperty("eip.security.mode", "oidc");
    environment.setProperty("EIP_OIDC_ISSUER", "CHANGE_ME");

    assertThatThrownBy(() -> guard.postProcessEnvironment(environment, application))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CHANGE_ME");
  }

  @Test
  void prod_with_oidc_mode_and_a_real_issuer_passes() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");
    environment.setProperty("eip.security.mode", "oidc");
    environment.setProperty("EIP_OIDC_ISSUER", REAL_ISSUER);

    assertThatCode(() -> guard.postProcessEnvironment(environment, application))
        .doesNotThrowAnyException();
  }

  @Test
  void preprod_with_header_mode_fails() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("preprod");
    environment.setProperty("eip.security.mode", "header");

    assertThatThrownBy(() -> guard.postProcessEnvironment(environment, application))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OIDC")
        .hasMessageContaining("DEBT-012");
  }

  @Test
  void preprod_with_oidc_mode_and_a_real_issuer_passes() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("preprod");
    environment.setProperty("eip.security.mode", "oidc");
    environment.setProperty("EIP_OIDC_ISSUER", REAL_ISSUER);

    assertThatCode(() -> guard.postProcessEnvironment(environment, application))
        .doesNotThrowAnyException();
  }

  @Test
  void dev_with_header_mode_passes() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("dev");
    environment.setProperty("eip.security.mode", "header");

    assertThatCode(() -> guard.postProcessEnvironment(environment, application))
        .doesNotThrowAnyException();
  }

  @Test
  void every_non_prod_non_preprod_environment_is_untouched_by_this_guard() {
    // header mode, no OIDC config at all — this guard only ever restricts prod/preprod.
    for (String profile : new String[] {"dev", "test", "demo"}) {
      MockEnvironment environment = new MockEnvironment();
      environment.setActiveProfiles(profile);
      assertThatCode(() -> guard.postProcessEnvironment(environment, application))
          .doesNotThrowAnyException();
    }
  }
}
