// eip-connectors — Connector SPI + connectors. Allowed deps: eip-core, eip-tenancy
// (tenant context only) (BackendPlan.md §1).
plugins {
    id("eip.modulith-conventions")
}
dependencies {
    // Spring Modulith package metadata (annotations only, compile-time; verified
    // application-wide from eip-app's ApplicationModules test — BackendPlan §3).
    compileOnly(platform(libs.spring.modulith.bom))
    compileOnly("org.springframework.modulith:spring-modulith-core")

    api(project(":eip-core"))
    implementation(project(":eip-tenancy"))

    // Test-only: AssertJ (mandated assertion library, CodingStandards §5). JUnit 5 + ArchUnit
    // arrive via eip.modulith-conventions. No database or Spring — the connector is a pure producer
    // exercised against a capturing in-memory sink.
    testImplementation(libs.assertj.core)
}
