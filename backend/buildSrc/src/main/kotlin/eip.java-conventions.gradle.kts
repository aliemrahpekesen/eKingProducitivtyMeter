// Shared Java conventions applied to every backend module (BackendPlan.md §1 excerpt,
// CodingStandards.md §1/§2/§8). Wires the G1 static-analysis + G2 coverage tooling so CI (and a
// local `./gradlew check`) enforce the same gates: Java 21 toolchain, -parameters/-Werror, Spotless
// (Google Java Format + Apache-2.0 header), Error Prone + NullAway, Checkstyle, JUnit 5, and JaCoCo
// coverage verification. The Spring Modulith `ModularityTests` boundary test itself lands with
// eip-core in TASK-0005; this convention makes `check` the single entry point CI invokes.

import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.artifacts.VersionCatalogsExtension

// DEBT-001: precompiled script plugins (this file) do NOT get the type-safe `libs` accessor that
// regular build scripts (incl. buildSrc/build.gradle.kts itself) get for free — confirmed
// empirically (`:buildSrc:compileKotlin` fails "Unresolved reference: libs" even outside the
// plugins {} block). The catalog is still the single source of truth: look it up via Gradle's
// documented programmatic API (`VersionCatalogsExtension`/`VersionCatalog`) instead, which works
// from any script.
val catalogLibs = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun catalogVersion(alias: String) = catalogLibs.findVersion(alias).get().requiredVersion
fun catalogLibrary(alias: String) = catalogLibs.findLibrary(alias).get()

plugins {
    `java-library`
    jacoco
    checkstyle
    // NOT `alias(libs.plugins.spotless)`: a precompiled script plugin's OWN `plugins {}` block is
    // extracted and compiled in an isolated early pass with no access to ANY extension, including
    // VersionCatalogsExtension (confirmed empirically). The plugin's version is still
    // catalog-sourced: it comes from buildSrc's own classpath
    // (buildSrc/build.gradle.kts `implementation(libs.spotless.plugin.gradle)`), so no version
    // literal is duplicated here — DEBT-001 residual, documented rather than worked around.
    id("com.diffplug.spotless")
    id("net.ltgt.errorprone")
}

group = "com.eip"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(catalogVersion("java").toInt())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    errorprone(catalogLibrary("errorprone-core"))
    errorprone(catalogLibrary("nullaway"))
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-parameters", "-Werror"))
    options.encoding = "UTF-8"
    options.errorprone {
        disableWarningsInGeneratedCode = true
        // NullAway checks null-safety on our own packages; ERROR so it blocks the build (CodingStandards §2.1).
        option("NullAway:AnnotatedPackages", "com.eip")
        error("NullAway")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("spring.threads.virtual.enabled", "true")
    finalizedBy(tasks.named("jacocoTestReport"))
}

// XML report (DEBT-003): needed to measure/enforce per-module line/branch coverage
// programmatically (this file's LINE/BRANCH JacocoCoverageVerification rules below, and any
// ad-hoc tooling reading build/reports/jacoco/test/jacocoTestReport.xml); HTML remains for human
// inspection.
tasks.withType<JacocoReport>().configureEach {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

spotless {
    java {
        googleJavaFormat(catalogVersion("googleJavaFormat"))
        licenseHeader(
            """
            /*
             * Copyright the Engineering Intelligence Platform (EIP) authors.
             * SPDX-License-Identifier: Apache-2.0
             */
            """.trimIndent() + "\n",
        )
        target("src/**/*.java")
    }
}

checkstyle {
    toolVersion = catalogVersion("checkstyle")
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}

jacoco {
    toolVersion = catalogVersion("jacoco")
}

// Coverage ratchet (TestingStrategy §1, DEBT-003 paid): ≥85% LINE + ≥75% BRANCH on the eip-core
// shared kernel AND eip-analytics (the two modules TestingStrategy §1 names explicitly); ≥75% LINE
// elsewhere (no branch floor on the "elsewhere" tier — TestingStrategy §1 states line-only there).
// Uses JaCoCo's LINE/BRANCH counters explicitly rather than the default INSTRUCTION counter, which
// does not match the doc's source-of-record semantics. Composition-root main classes are excluded
// from measurement (nothing to unit-test in a bootstrap class). Empty modules (no source yet, e.g.
// eip-ai/eip-workers pre-Phase-1) have no measured classes, so JacocoCoverageVerification is
// SKIPPED by Gradle automatically — the rule starts biting the moment real logic and its tests land.
val highCoverageTierModules = setOf("eip-core", "eip-analytics")
val lineMinimum = if (project.name in highCoverageTierModules) "0.85".toBigDecimal() else "0.75".toBigDecimal()
val branchMinimum = "0.75".toBigDecimal()

tasks.withType<JacocoCoverageVerification>().configureEach {
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) { exclude("**/*Application*", "**/config/**") }
            },
        ),
    )
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = lineMinimum
            }
            if (project.name in highCoverageTierModules) {
                limit {
                    counter = "BRANCH"
                    minimum = branchMinimum
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.withType<JacocoCoverageVerification>())
}
