/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

/**
 * Layering rules the Modulith module boundaries cannot express (BackendPlan §2.4): controllers are
 * pure DTO adapters over ports; the pure domain/metric/connector packages stay free of Spring,
 * JDBC, and persistence concerns; eip-app never reaches into another module's application or
 * persistence internals. Production classes only.
 */
class ArchitectureRulesTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("com.eip");

  @Test
  void controllers_touch_no_jdbc_or_sql_machinery() {
    noClasses()
        .that()
        .areAnnotatedWith(RestController.class)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework.jdbc..", "java.sql..", "javax.sql..")
        .because("controllers translate DTOs and call ports only (BackendPlan §2.4)")
        .check(CLASSES);
  }

  @Test
  void controllers_depend_only_on_ports_dtos_and_web_machinery() {
    classes()
        .that()
        .areAnnotatedWith(RestController.class)
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage(
            "java..",
            "jakarta..",
            "org.jspecify..",
            "org.springframework..",
            "com.eip.app.api..",
            "com.eip.app.application..",
            "com.eip.analytics.api..",
            "com.eip.ingestion.api..",
            "com.eip.tenancy.api..",
            "com.eip.reports.api..")
        .because("controllers call application/query ports and return API DTOs, nothing else")
        .check(CLASSES);
  }

  @Test
  void pure_domain_packages_stay_framework_free() {
    noClasses()
        .that()
        .resideInAnyPackage(
            "com.eip.core..",
            "com.eip.connectors.spi..",
            "com.eip.connectors.simulation..",
            "com.eip.analytics.friction..",
            "com.eip.tenancy.context..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework.beans..",
            "org.springframework.context..",
            "org.springframework.stereotype..",
            "org.springframework.jdbc..",
            "org.springframework.transaction..",
            "org.springframework.web..",
            "org.springframework.boot..",
            "jakarta.persistence..",
            "com.fasterxml.jackson..")
        .because(
            "domain calculations, value objects, connector CONTRACTS, and the RLS primitive are"
                + " framework-independent pure Java (founder decision 5)")
        .check(CLASSES);
  }

  @Test
  void connector_implementations_use_no_spring_or_jdbc() {
    // Real connector impls may use Jackson + the JDK HttpClient, but never Spring or JDBC:
    // they must stay extractable and testable without a container (founder decision 5).
    noClasses()
        .that()
        .resideInAPackage("com.eip.connectors..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework.beans..",
            "org.springframework.context..",
            "org.springframework.stereotype..",
            "org.springframework.jdbc..",
            "org.springframework.transaction..",
            "org.springframework.web..",
            "org.springframework.boot..",
            "java.sql..",
            "javax.sql..",
            "jakarta.persistence..")
        .check(CLASSES);
  }

  @Test
  void eip_app_owns_no_pipeline_business_logic() {
    noClasses()
        .that()
        .resideInAPackage("com.eip.app..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "com.eip.analytics.friction..",
            "com.eip.analytics.application..",
            "com.eip.analytics.persistence..",
            "com.eip.ingestion.application..",
            "com.eip.ingestion.persistence..",
            "com.eip.connectors..",
            "com.eip.reports.application..",
            "com.eip.reports.persistence..")
        .because(
            "normalization, correlation, metric, projection, and report-composition logic belong"
                + " to their owning modules; eip-app wires and invokes use cases only (ADR-019)")
        .check(CLASSES);
  }
}
