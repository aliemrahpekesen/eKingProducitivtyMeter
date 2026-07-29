/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.config;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

/**
 * Fail-fast startup guard for the {@code prod}/{@code preprod} profiles (DEBT-012, M5 Wave S1a):
 * these profiles now MAY boot, but only with real OIDC tenant resolution configured — {@code
 * eip.security.mode=oidc} AND a non-blank, non-{@code CHANGE_ME} {@value #ISSUER_ENV_VAR}. Without
 * both, the dev/demo header resolver would be the only tenant-resolution mechanism, and it must
 * never serve production (or rehearsal) traffic. Registered as an {@link EnvironmentPostProcessor}
 * (META-INF/spring.factories) so the refusal fires BEFORE any bean wiring — the operator sees this
 * message as the first and only failure, not an incidental missing-bean error.
 *
 * <p>Checks the raw {@value #ISSUER_ENV_VAR} environment variable directly, not the derived {@code
 * spring.security.oauth2.resourceserver.jwt.issuer-uri} property — the two are equivalent whenever
 * {@code application.yaml}'s default (<code>${EIP_OIDC_ISSUER:...}</code>) is left alone, and
 * checking the env var directly keeps this check independent of Spring Security's property-binding
 * timing/precedence, matching the {@code config/environments/*.env} {@code CHANGE_ME} convention
 * (DEBT-005) those files already use for this exact variable.
 */
public class ProductionTenantResolutionGuard implements EnvironmentPostProcessor {

  /** {@link #ISSUER_ENV_VAR}'s sentinel "not a real deployment" value (DEBT-005 convention). */
  static final String CHANGE_ME = "CHANGE_ME";

  /**
   * The {@code eip.security.mode} property (see {@code
   * com.eip.app.security.EipSecurityProperties}).
   */
  static final String MODE_PROPERTY = "eip.security.mode";

  /** The env var carrying the OIDC issuer (SecurityModel §3). */
  static final String ISSUER_ENV_VAR = "EIP_OIDC_ISSUER";

  private static final String OIDC_MODE = "oidc";

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (!environment.acceptsProfiles(Profiles.of("prod", "preprod"))) {
      return;
    }
    String mode = environment.getProperty(MODE_PROPERTY, "header");
    @Nullable String issuer = environment.getProperty(ISSUER_ENV_VAR);
    boolean oidcMode = OIDC_MODE.equalsIgnoreCase(mode);
    boolean issuerConfigured =
        issuer != null && !issuer.isBlank() && !CHANGE_ME.equals(issuer.trim());
    if (oidcMode && issuerConfigured) {
      return;
    }
    throw new IllegalStateException(
        "Refusing to start with profile(s) '"
            + String.join(",", environment.getActiveProfiles())
            + "': OIDC tenant resolution requires "
            + MODE_PROPERTY
            + "=oidc and a real "
            + ISSUER_ENV_VAR
            + " (not blank, not CHANGE_ME) — DEBT-012. Header/demo tenant resolution must never"
            + " serve production traffic. Got "
            + MODE_PROPERTY
            + "='"
            + mode
            + "', "
            + ISSUER_ENV_VAR
            + "='"
            + issuer
            + "'. Set EIP_SECURITY_MODE=oidc and EIP_OIDC_ISSUER to your IdP realm.");
  }
}
