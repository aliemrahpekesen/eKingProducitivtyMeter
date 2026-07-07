// eip-core — shared kernel (leaf module). Zero dependency on any other eip-* module
// (BackendPlan.md §1 "none — leaf"; AP-1). Content: canonical base types, event envelope,
// error taxonomy, and the SPI package roots (TASK-0005, P0-E2-S1, CC-1).
plugins {
    id("eip.modulith-conventions")
}

dependencies {
    // Nullness contract carried on the API surface so downstream modules inherit @NullMarked /
    // @Nullable semantics (CodingStandards §2.1). NullAway reads these; JSpecify is the only
    // third-party runtime dependency the shared kernel takes.
    api(libs.jspecify)

    // Test-only: Spring Modulith drives the ApplicationModules boundary verification + Documenter
    // snapshot; AssertJ is the mandated assertion library (CodingStandards §5). ArchUnit + JUnit 5
    // arrive via eip.modulith-conventions.
    testImplementation(platform(libs.spring.modulith.bom))
    testImplementation(libs.spring.modulith.starter.core)
    testImplementation("org.springframework.modulith:spring-modulith-docs")
    testImplementation(libs.assertj.core)
}
