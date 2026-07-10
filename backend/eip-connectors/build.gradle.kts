// eip-connectors — Connector SPI + connectors. Allowed deps: eip-core, eip-tenancy
// (tenant context only) (BackendPlan.md §1).
plugins {
    id("eip.modulith-conventions")
}
dependencies {
    api(project(":eip-core"))
    implementation(project(":eip-tenancy"))

    // Test-only: AssertJ (mandated assertion library, CodingStandards §5). JUnit 5 + ArchUnit
    // arrive via eip.modulith-conventions. No database or Spring — the connector is a pure producer
    // exercised against a capturing in-memory sink.
    testImplementation(libs.assertj.core)
}
