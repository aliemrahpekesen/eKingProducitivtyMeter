/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit guard asserting the leaf invariant (AP-1; BackendPlan §1: {@code eip-core} depends on
 * "none — leaf"). It complements the Gradle module isolation — {@code eip-core} declares no project
 * dependency, so another module's types are not even on its classpath — by failing loudly if a
 * stray import to another {@code eip-*} module ever appears.
 */
class LeafBoundaryTest {

  private static final JavaClasses CORE_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.eip.core");

  @Test
  void eipCoreDependsOnNoOtherEipModule() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.eip.core..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "com.eip.tenancy..",
                "com.eip.connectors..",
                "com.eip.ingestion..",
                "com.eip.analytics..",
                "com.eip.ai..",
                "com.eip.reports..",
                "com.eip.app..",
                "com.eip.workers..");

    rule.check(CORE_CLASSES);
  }
}
