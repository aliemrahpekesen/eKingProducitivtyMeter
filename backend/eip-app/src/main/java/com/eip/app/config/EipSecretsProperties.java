/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Envelope-encryption settings (ADR-014). {@code masterKey} is the base64-encoded 32-byte KEK; the
 * default is a well-known DEV-ONLY key so local environments work out of the box — production
 * deployments MUST override it via {@code EIP_SECRET_MASTER_KEY} (the prod env file ships CHANGE_ME
 * per DEBT-005; prod boot is guarded until OIDC anyway, DEBT-012).
 *
 * @param masterKey base64 of exactly 32 key bytes
 * @param kekVersion version stamped on stored secrets (bump on rotation)
 */
@ConfigurationProperties(prefix = "eip.secrets")
@Validated
public record EipSecretsProperties(
    @NotBlank @DefaultValue(DEV_ONLY_MASTER_KEY) String masterKey,
    @DefaultValue("1") int kekVersion) {

  /** Base64 of the 32-byte DEV-ONLY master key ("EIP-DEV-ONLY-MASTER-KEY-32BYTES!"). */
  public static final String DEV_ONLY_MASTER_KEY = "RUlQLURFVi1PTkxZLU1BU1RFUi1LRVktMzJCWVRFUyE=";
}
