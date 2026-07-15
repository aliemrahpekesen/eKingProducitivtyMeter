// Convention for Spring Modulith application modules (BackendPlan.md §1, §3). Builds on
// eip.java-conventions and makes ArchUnit + JUnit 5 available so the module-boundary `ModularityTests`
// can be authored with eip-core in TASK-0005 (P0-E2-S1, CC-1). The `spring-modulith-starter-core`
// dependency and the concrete boundary test are added by that task; wiring the test libraries here
// keeps the `./gradlew check` CI stage (TASK-0002) the single entry point that runs them once present.

import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("eip.java-conventions")
}

// DEBT-001: see eip.java-conventions.gradle.kts for why this uses the programmatic
// VersionCatalogsExtension API rather than the type-safe `libs` accessor.
val catalogLibs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    testImplementation(catalogLibs.findLibrary("junit-jupiter").get())
    testImplementation(catalogLibs.findLibrary("archunit-junit5").get())
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
