/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

/**
 * Fail-fast startup guard for the {@code prod} profile: until OIDC tenant resolution lands
 * (DEBT-012), the only resolver is the dev/demo header resolver, which must never serve production
 * traffic. Registered as an {@link EnvironmentPostProcessor} (META-INF/spring.factories) so the
 * refusal fires BEFORE any bean wiring — the operator sees this message as the first and only
 * failure, not an incidental missing-bean error.
 */
public class ProductionTenantResolutionGuard implements EnvironmentPostProcessor {

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (environment.acceptsProfiles(Profiles.of("prod"))) {
      throw new IllegalStateException(
          "Refusing to start with the 'prod' profile: OIDC tenant resolution is not implemented"
              + " yet (DEBT-012), and header/demo tenant resolution must never serve production"
              + " traffic. Use the preprod profile for production rehearsal.");
    }
  }
}
