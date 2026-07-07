// Shared Java conventions applied to every backend module (BackendPlan.md §1 excerpt,
// CodingStandards.md §1/§2/§8). Wires the G1 static-analysis + G2 coverage tooling so CI (and a
// local `./gradlew check`) enforce the same gates: Java 21 toolchain, -parameters/-Werror, Spotless
// (Google Java Format + Apache-2.0 header), Error Prone + NullAway, Checkstyle, JUnit 5, and JaCoCo
// coverage verification. The Spring Modulith `ModularityTests` boundary test itself lands with
// eip-core in TASK-0005; this convention makes `check` the single entry point CI invokes.

import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    jacoco
    checkstyle
    id("com.diffplug.spotless")
    id("net.ltgt.errorprone")
}

group = "com.eip"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    errorprone("com.google.errorprone:error_prone_core:2.36.0")
    errorprone("com.uber.nullaway:nullaway:0.12.1")
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

spotless {
    java {
        googleJavaFormat("1.24.0")
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
    toolVersion = "10.20.1"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}

jacoco {
    toolVersion = "0.8.12"
}

// Coverage ratchet (TestingStrategy §1): ≥85% on the eip-core shared kernel, ≥75% elsewhere.
// Composition-root main classes are excluded from measurement (nothing to unit-test in a bootstrap
// class). On the empty Phase-0 skeleton there are no measured classes, so the rule passes; it starts
// biting the moment real logic and its tests land.
val coverageMinimum = if (project.name == "eip-core") "0.85".toBigDecimal() else "0.75".toBigDecimal()

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
            limit { minimum = coverageMinimum }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.withType<JacocoCoverageVerification>())
}
