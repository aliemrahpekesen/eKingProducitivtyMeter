/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.core;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Spring Modulith boundary verification for the shared kernel (BackendPlan §3). {@code verify()} is
 * the runtime enforcement of the module structure — a dependency-rule violation is a build-red
 * event, not a review comment. The second test regenerates the committed Documenter snapshot so the
 * module diagram in {@code generated-docs/} always reflects the actual structure.
 */
class ModularityTests {

  private static final ApplicationModules MODULES = ApplicationModules.of("com.eip.core");

  @Test
  void verifiesModuleStructure() {
    MODULES.verify();
  }

  @Test
  void writesDocumentationSnapshot() {
    Documenter.Options options = Documenter.Options.defaults().withOutputFolder("generated-docs");
    new Documenter(MODULES, options).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
  }
}
