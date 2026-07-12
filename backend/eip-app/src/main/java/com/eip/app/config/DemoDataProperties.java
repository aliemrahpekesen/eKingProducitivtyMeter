/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.config;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Demo-profile settings (BackendPlan §2.2: validated {@code @ConfigurationProperties} records). The
 * fixed demo tenant id keeps the one-command demo and the frontend default stable without a
 * database lookup; production never reads these (the seeder is {@code @Profile("demo")}).
 *
 * @param tenantId the fixed demo tenant id
 */
@ConfigurationProperties(prefix = "eip.demo")
@Validated
public record DemoDataProperties(@NotNull @DefaultValue(DEFAULT_TENANT_ID) UUID tenantId) {

  /** The well-known default demo tenant id (kept stable across releases). */
  public static final String DEFAULT_TENANT_ID = "00000000-0000-4000-8000-0000000000de";
}
