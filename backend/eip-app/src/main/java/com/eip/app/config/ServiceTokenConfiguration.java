/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.config;

import com.eip.tenancy.rbac.ServiceTokenGenerator;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition-root wiring for service-token generation/hashing (SecurityModel §3, ADR-025). {@link
 * ServiceTokenGenerator} is a plain, dependency-free class (no Spring stereotype, mirroring this
 * module's {@code rbac} package convention) — wired here exactly like {@link SecretsConfiguration}
 * wires {@code SecretsService}.
 */
@Configuration(proxyBeanMethods = false)
public class ServiceTokenConfiguration {

  /**
   * The shared service-token generator/hasher.
   *
   * @return the generator
   */
  @Bean
  public ServiceTokenGenerator serviceTokenGenerator() {
    return new ServiceTokenGenerator();
  }

  /**
   * The system UTC clock, injected into {@code ServiceTokenService} for testable expiry computation
   * (BackendPlan §2.5: {@code Clock} is injected everywhere for testability).
   *
   * @return the system UTC clock
   */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
