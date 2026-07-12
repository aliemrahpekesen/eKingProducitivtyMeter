/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fail-fast startup guard for the {@code prod} profile: until OIDC tenant resolution lands
 * (DEBT-012), the only resolver is the dev/demo header resolver, which must never serve production
 * traffic. Booting with {@code prod} therefore refuses to start — loudly, at startup — instead of
 * silently running with header/demo tenant resolution.
 */
@Component
@Profile("prod")
public class ProductionTenantResolutionGuard implements InitializingBean {

  @Override
  public void afterPropertiesSet() {
    throw new IllegalStateException(
        "Refusing to start with the 'prod' profile: OIDC tenant resolution is not implemented yet"
            + " (DEBT-012), and header/demo tenant resolution must never serve production"
            + " traffic.");
  }
}
