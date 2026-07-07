// EIP backend — Gradle multi-module modular monolith.
// Declares exactly the nine modules of docs/engineering/BackendPlan.md §1 and nothing else
// (RepositoryStructure.md §2 invariant: new modules require an ADR + R-CA approval).

plugins {
    // Auto-provisions the Java 21 toolchain when it is not already installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "eip-backend"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "eip-core",
    "eip-tenancy",
    "eip-connectors",
    "eip-ingestion",
    "eip-analytics",
    "eip-ai",
    "eip-reports",
    "eip-app",
    "eip-workers",
)
