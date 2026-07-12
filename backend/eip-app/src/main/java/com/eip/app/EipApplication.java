/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Composition-root entry point for the EIP API application. Scans the full {@code com.eip}
 * application (the library modules contribute their {@code @Service}/{@code @Repository}
 * application and infrastructure beans; domain code stays framework-free) and binds validated
 * {@code @ConfigurationProperties} records (BackendPlan §2.2). Module boundaries are enforced by
 * the application-wide Spring Modulith verification, not by scan scope.
 */
@SpringBootApplication(scanBasePackages = "com.eip")
@ConfigurationPropertiesScan("com.eip")
public class EipApplication {

  public static void main(String[] args) {
    SpringApplication.run(EipApplication.class, args);
  }
}
