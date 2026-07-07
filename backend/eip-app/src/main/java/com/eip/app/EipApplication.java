/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Composition-root entry point for the EIP API application. Scaffold stub only — controllers, the
 * OpenAPI surface, the security filter chain, and the problem+json advice are added in later
 * Phase-0 tasks (SPRINT-01+). This module contains no business logic (BackendPlan.md §1).
 */
@SpringBootApplication
public class EipApplication {

  public static void main(String[] args) {
    SpringApplication.run(EipApplication.class, args);
  }
}
