// EIP backend — Gradle multi-module modular monolith.
// Declares exactly the nine modules of docs/engineering/BackendPlan.md §1 and nothing else
// (RepositoryStructure.md §2 invariant: new modules require an ADR + R-CA approval).

plugins {
    // Auto-provisions the Java 21 toolchain when it is not already installed.
    //
    // DEBT-001 residual: this version is NOT routed through gradle/libs.versions.toml's
    // `foojayResolver` entry. A settings file's own `plugins {}` block resolves before that same
    // file's `dependencyResolutionManagement`/implicit-catalog machinery is available — Gradle
    // does not support `alias(libs.plugins...)` (nor any version-catalog lookup) inside a settings
    // script's own plugins block, only in build scripts and (via `dependencyResolutionManagement`)
    // the rest of this file. Confirmed empirically as part of DEBT-001's catalog-consistency pass:
    // buildSrc's own settings.gradle.kts avoids this exact trap by wiring its `libs` catalog in
    // `dependencyResolutionManagement`, not its (empty) plugins block. Kept in sync with
    // libs.versions.toml's `foojayResolver` value by hand; bump both together.
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
