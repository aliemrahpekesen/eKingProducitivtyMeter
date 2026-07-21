/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

/**
 * Proves the {@code prod}/{@code preprod}-profile secrets guard (DEBT-005): both profiles refuse to
 * boot while any of {@link ProductionSecretsGuard#GUARDED_VARS} is blank, {@code CHANGE_ME}, or a
 * known dev/demo fixture value, and boot proceeds only once every guarded variable carries a real
 * value. Every other environment profile is untouched by this guard regardless of value.
 */
class ProductionSecretsGuardTest {

  private static final String REAL_APP_PASSWORD = "S3cure-Pr0d-App-Pw!";
  private static final String REAL_MIGRATOR_PASSWORD = "S3cure-Pr0d-Migrator-Pw!";
  private static final String REAL_MASTER_KEY = "cHJvZC1yZWFsLW1hc3Rlci1rZXktMzItYnl0ZXMtbG9uZyE=";
  private static final String REAL_CURSOR_SIGNING_KEY =
      "cHJvZC1yZWFsLWN1cnNvci1zaWduaW5nLWtleS0zMi1ieXRlcyE=";

  private final ProductionSecretsGuard guard = new ProductionSecretsGuard();
  private final SpringApplication application = new SpringApplication();

  private static MockEnvironment withAllRealSecrets(String... profiles) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profiles);
    environment.setProperty("EIP_APP_DB_PASSWORD", REAL_APP_PASSWORD);
    environment.setProperty("EIP_MIGRATOR_DB_PASSWORD", REAL_MIGRATOR_PASSWORD);
    environment.setProperty("EIP_SECRETS_MASTER_KEY", REAL_MASTER_KEY);
    environment.setProperty("EIP_API_CURSOR_SIGNING_KEY", REAL_CURSOR_SIGNING_KEY);
    return environment;
  }

  @Test
  void prod_with_change_me_values_fails_listing_the_offending_variables() {
    MockEnvironment environment = withAllRealSecrets("prod");
    environment.setProperty("EIP_APP_DB_PASSWORD", "CHANGE_ME");
    environment.setProperty("EIP_MIGRATOR_DB_PASSWORD", "CHANGE_ME");

    assertThatThrownBy(() -> guard.postProcessEnvironment(environment, application))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("DEBT-005")
        .hasMessageContaining("EIP_APP_DB_PASSWORD")
        .hasMessageContaining("EIP_MIGRATOR_DB_PASSWORD")
        .hasMessageNotContaining("CHANGE_ME, CHANGE_ME"); // values themselves are never repeated
  }

  @Test
  void prod_with_a_dev_fixture_value_fails() {
    MockEnvironment environment = withAllRealSecrets("prod");
    environment.setProperty("EIP_APP_DB_PASSWORD", "eip_app_dev_pw");

    assertThatThrownBy(() -> guard.postProcessEnvironment(environment, application))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("EIP_APP_DB_PASSWORD");
  }

  @Test
  void prod_with_the_dev_only_master_key_fixture_fails() {
    MockEnvironment environment = withAllRealSecrets("prod");
    environment.setProperty("EIP_SECRETS_MASTER_KEY", EipSecretsProperties.DEV_ONLY_MASTER_KEY);

    assertThatThrownBy(() -> guard.postProcessEnvironment(environment, application))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("EIP_SECRETS_MASTER_KEY");
  }

  @Test
  void prod_with_the_dev_only_cursor_signing_key_fixture_fails() {
    MockEnvironment environment = withAllRealSecrets("prod");
    environment.setProperty(
        "EIP_API_CURSOR_SIGNING_KEY", ApiCursorSigningProperties.DEV_ONLY_CURSOR_SIGNING_KEY);

    assertThatThrownBy(() -> guard.postProcessEnvironment(environment, application))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("EIP_API_CURSOR_SIGNING_KEY");
  }

  @Test
  void prod_with_a_blank_value_fails() {
    MockEnvironment environment = withAllRealSecrets("prod");
    environment.setProperty("EIP_MIGRATOR_DB_PASSWORD", "   ");

    assertThatThrownBy(() -> guard.postProcessEnvironment(environment, application))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("EIP_MIGRATOR_DB_PASSWORD");
  }

  @Test
  void prod_with_an_unset_variable_fails() {
    // EIP_SECRETS_MASTER_KEY intentionally never set — unset must count the same as blank.
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");
    environment.setProperty("EIP_APP_DB_PASSWORD", REAL_APP_PASSWORD);
    environment.setProperty("EIP_MIGRATOR_DB_PASSWORD", REAL_MIGRATOR_PASSWORD);

    assertThatThrownBy(() -> guard.postProcessEnvironment(environment, application))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("EIP_SECRETS_MASTER_KEY");
  }

  @Test
  void prod_with_all_real_values_passes() {
    MockEnvironment environment = withAllRealSecrets("prod");

    assertThatCode(() -> guard.postProcessEnvironment(environment, application))
        .doesNotThrowAnyException();
  }

  @Test
  void preprod_with_a_dev_fixture_value_fails() {
    MockEnvironment environment = withAllRealSecrets("preprod");
    environment.setProperty("EIP_APP_DB_PASSWORD", "eip_dev_pw");

    assertThatThrownBy(() -> guard.postProcessEnvironment(environment, application))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("EIP_APP_DB_PASSWORD");
  }

  @Test
  void preprod_with_all_real_values_passes() {
    MockEnvironment environment = withAllRealSecrets("preprod");

    assertThatCode(() -> guard.postProcessEnvironment(environment, application))
        .doesNotThrowAnyException();
  }

  @Test
  void dev_with_fixture_values_passes_since_this_guard_only_restricts_prod_preprod() {
    for (String profile : new String[] {"dev", "test", "demo"}) {
      MockEnvironment environment = new MockEnvironment();
      environment.setActiveProfiles(profile);
      environment.setProperty("EIP_APP_DB_PASSWORD", "eip_app_dev_pw");
      environment.setProperty("EIP_MIGRATOR_DB_PASSWORD", "eip_dev_pw");
      environment.setProperty("EIP_SECRETS_MASTER_KEY", EipSecretsProperties.DEV_ONLY_MASTER_KEY);
      environment.setProperty(
          "EIP_API_CURSOR_SIGNING_KEY", ApiCursorSigningProperties.DEV_ONLY_CURSOR_SIGNING_KEY);

      assertThatCode(() -> guard.postProcessEnvironment(environment, application))
          .doesNotThrowAnyException();
    }
  }

  @Test
  void message_names_every_offending_variable_but_never_the_offending_value() {
    MockEnvironment environment = withAllRealSecrets("prod");
    environment.setProperty("EIP_APP_DB_PASSWORD", "eip_app_dev_pw");
    environment.setProperty("EIP_MIGRATOR_DB_PASSWORD", "eip_dev_pw");
    environment.setProperty("EIP_SECRETS_MASTER_KEY", "CHANGE_ME");
    environment.setProperty(
        "EIP_API_CURSOR_SIGNING_KEY", ApiCursorSigningProperties.DEV_ONLY_CURSOR_SIGNING_KEY);

    assertThatThrownBy(() -> guard.postProcessEnvironment(environment, application))
        .isInstanceOf(IllegalStateException.class)
        .satisfies(
            ex -> {
              String message = ex.getMessage();
              assertThat(message)
                  .contains("EIP_APP_DB_PASSWORD")
                  .contains("EIP_MIGRATOR_DB_PASSWORD")
                  .contains("EIP_SECRETS_MASTER_KEY")
                  .contains("EIP_API_CURSOR_SIGNING_KEY")
                  .doesNotContain("eip_app_dev_pw")
                  .doesNotContain("eip_dev_pw");
            });
  }
}
