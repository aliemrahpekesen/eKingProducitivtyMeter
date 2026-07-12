/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Proves the {@code prod}-profile guard refuses startup while tenant resolution is header/demo only
 * (DEBT-012): production can never silently run with the dev tenant resolver.
 */
class ProductionTenantResolutionGuardTest {

  @Test
  void refuses_production_startup_until_oidc_lands() {
    assertThatThrownBy(() -> new ProductionTenantResolutionGuard().afterPropertiesSet())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OIDC")
        .hasMessageContaining("DEBT-012");
  }
}
