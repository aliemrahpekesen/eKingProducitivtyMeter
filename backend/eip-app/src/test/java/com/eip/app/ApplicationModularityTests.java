/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Application-wide Spring Modulith boundary verification (BackendPlan §3) over the full {@code
 * com.eip} root — the actual application root now that the composition root scans all modules.
 * Enforces: no cycles between modules, and cross-module access only through module API (the OPEN
 * kernel modules core/tenancy/connectors, and the {@code api} named interfaces of ingestion and
 * analytics — application/persistence internals are inaccessible). A violation is a build-red
 * event, not a review comment. The Documenter snapshot is committed under {@code
 * docs/architecture/generated/modulith} so the module diagram always reflects the real structure.
 */
class ApplicationModularityTests {

  private static final ApplicationModules MODULES = ApplicationModules.of("com.eip");

  @Test
  void verifiesApplicationModuleStructure() {
    MODULES.verify();
  }

  @Test
  void writesDocumentationSnapshot() {
    Documenter.Options options =
        Documenter.Options.defaults()
            .withOutputFolder("../../docs/architecture/generated/modulith");
    new Documenter(MODULES, options).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
  }
}
