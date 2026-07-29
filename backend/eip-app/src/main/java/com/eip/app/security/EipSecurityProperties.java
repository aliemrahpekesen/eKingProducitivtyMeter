/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Selects the auth mode (SecurityModel §3, BackendPlan §1 module table). {@code header} (default)
 * preserves the dev/demo {@code X-EIP-Tenant} convenience; {@code oidc} requires a valid JWT and
 * derives tenant + roles from its claims (SecurityModel §4). {@link
 * com.eip.app.config.ProductionTenantResolutionGuard} refuses {@code prod}/{@code preprod} boot
 * unless this resolves to {@link SecurityMode#OIDC} with a real issuer configured (DEBT-012).
 *
 * @param mode the active security mode
 */
@ConfigurationProperties(prefix = "eip.security")
@Validated
public record EipSecurityProperties(@DefaultValue("header") SecurityMode mode) {

  /**
   * Returns whether the active mode is {@link SecurityMode#OIDC}.
   *
   * @return {@code true} in {@code oidc} mode
   */
  public boolean oidc() {
    return mode == SecurityMode.OIDC;
  }

  /** The two supported authentication modes. */
  public enum SecurityMode {
    /** Dev/demo convenience: trusts {@code X-EIP-Tenant}; implicit {@code TENANT_ADMIN}. */
    HEADER,
    /** Production mechanism: OIDC JWT bearer tokens validated against the configured issuer. */
    OIDC
  }
}
