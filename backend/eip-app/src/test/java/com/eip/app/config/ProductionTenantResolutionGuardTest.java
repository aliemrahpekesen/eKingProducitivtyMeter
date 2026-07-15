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
 * Proves the {@code prod}-profile guard refuses startup at the ENVIRONMENT stage (before any bean
 * wiring) while tenant resolution is header/demo only (DEBT-012), and stays silent for every other
 * environment profile.
 */
class ProductionTenantResolutionGuardTest {

  private final ProductionTenantResolutionGuard guard = new ProductionTenantResolutionGuard();
  private final SpringApplication application = new SpringApplication();

  @Test
  void refuses_production_startup_until_oidc_lands() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");

    assertThatThrownBy(() -> guard.postProcessEnvironment(environment, application))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OIDC")
        .hasMessageContaining("DEBT-012");
  }

  @Test
  void allows_every_non_prod_environment() {
    for (String profile : new String[] {"dev", "test", "preprod", "demo"}) {
      MockEnvironment environment = new MockEnvironment();
      environment.setActiveProfiles(profile);
      assertThatCode(() -> guard.postProcessEnvironment(environment, application))
          .doesNotThrowAnyException();
    }
  }
}
