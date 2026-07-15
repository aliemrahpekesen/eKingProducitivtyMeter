/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

/**
 * Fail-fast startup guard for the {@code prod}/{@code preprod} profiles (DEBT-005): refuses to boot
 * if any secret-bearing environment variable in {@link #GUARDED_VARS} is left at a non-production
 * value — blank/unset, the {@value #CHANGE_ME} placeholder ({@code config/environments/*.env}'s
 * documented convention), or one of the well-known dev/demo fixture values {@code
 * scripts/install/install.sh} and {@code infra/docker-compose/.env.example} ship (a real
 * deployment's secret manager will never happen to inject one of these exact constants). Registered
 * as an {@link EnvironmentPostProcessor} (META-INF/spring.factories, alongside {@link
 * ProductionTenantResolutionGuard}) so the refusal fires at the same environment-preparation stage,
 * before any bean wiring — the operator sees this as the first and only failure.
 *
 * <p>Unset counts the same as blank (matching {@link ProductionTenantResolutionGuard}'s treatment
 * of a missing issuer): a prod/preprod deployment that never set one of these variables would
 * otherwise boot silently against {@code application.yaml}'s dev-friendly default, which is exactly
 * the risk this guard exists to close.
 *
 * <p>The aggregated failure message names the offending environment variable(s) only — never their
 * values — so a boot-failure log can be shared without leaking whatever placeholder or dev secret
 * was actually configured.
 */
public class ProductionSecretsGuard implements EnvironmentPostProcessor {

  /** {@link #GUARDED_VARS}' sentinel "not a real deployment" value (DEBT-005 convention). */
  static final String CHANGE_ME = "CHANGE_ME";

  /**
   * Well-known non-production fixture values that must never reach a prod/preprod boot, even if an
   * operator sets one of {@link #GUARDED_VARS} to something non-blank by mistake: {@code
   * infra/docker-compose/.env.example}'s {@code POSTGRES_PASSWORD}, {@code
   * KEYCLOAK_ADMIN_PASSWORD}, and {@code MINIO_ROOT_PASSWORD}; {@code scripts/install/install.sh}'s
   * hardcoded local {@code EIP_APP_DB_PASSWORD}; and {@link
   * EipSecretsProperties#DEV_ONLY_MASTER_KEY}.
   */
  static final Set<String> DEV_FIXTURE_VALUES =
      Set.of(
          "eip_dev_pw",
          "eip_app_dev_pw",
          "admin_dev_pw",
          "eip_minio_dev_pw",
          EipSecretsProperties.DEV_ONLY_MASTER_KEY);

  /**
   * Secret-bearing environment variables checked for {@code prod}/{@code preprod} (DEBT-005): the
   * RLS-enforced app role's DB password, the privileged Flyway migrator role's DB password ({@code
   * application.yaml}), and the envelope-encryption master key ({@link EipSecretsProperties}).
   */
  static final List<String> GUARDED_VARS =
      List.of("EIP_APP_DB_PASSWORD", "EIP_MIGRATOR_DB_PASSWORD", "EIP_SECRETS_MASTER_KEY");

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (!environment.acceptsProfiles(Profiles.of("prod", "preprod"))) {
      return;
    }
    List<String> offending = new ArrayList<>();
    for (String var : GUARDED_VARS) {
      @Nullable String value = environment.getProperty(var);
      if (isInvalid(value)) {
        offending.add(var);
      }
    }
    if (offending.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        "Refusing to start with profile(s) '"
            + String.join(",", environment.getActiveProfiles())
            + "': the following secret-bearing environment variable(s) are blank/unset, '"
            + CHANGE_ME
            + "', or a known non-production fixture value (DEBT-005) — set each to a real"
            + " production secret (e.g. via your secret manager) before this environment will"
            + " boot: "
            + String.join(", ", offending)
            + ". Values are intentionally omitted from this message.");
  }

  private static boolean isInvalid(@Nullable String value) {
    return value == null
        || value.isBlank()
        || CHANGE_ME.equals(value.trim())
        || DEV_FIXTURE_VALUES.contains(value.trim());
  }
}
