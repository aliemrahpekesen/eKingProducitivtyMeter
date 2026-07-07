// Shared Java conventions applied to every backend module (BackendPlan.md §1 excerpt,
// CodingStandards.md §2/§8). Java 21 toolchain, -parameters, JUnit 5, JaCoCo, and Spotless
// (Google Java Format + Apache-2.0 license header). Deeper static analysis (Error Prone,
// NullAway, Checkstyle, ArchUnit) is a G1 CI-stage concern wired in TASK-0002; the Modulith
// ModularityTests harness lands with eip-core in TASK-0005.

plugins {
    `java-library`
    jacoco
    id("com.diffplug.spotless")
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

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-parameters", "-Werror"))
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("spring.threads.virtual.enabled", "true")
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
