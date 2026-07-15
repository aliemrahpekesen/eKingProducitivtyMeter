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
    testCompileOnly(platform(libs.spring.modulith.bom))
    testCompileOnly("org.springframework.modulith:spring-modulith-core")

    api(project(":eip-core"))
    implementation(project(":eip-tenancy"))

    // Real connector implementations (jira/bitbucket/sonarqube) parse source JSON with Jackson and
    // speak HTTP via the JDK HttpClient — still zero Spring (contracts and impls stay framework
    // free; only wiring lives in eip-ingestion). Boot-BOM pinned.
    implementation(platform(libs.spring.boot.bom))
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // Test-only: AssertJ (mandated assertion library, CodingStandards §5). JUnit 5 + ArchUnit
    // arrive via eip.modulith-conventions. No database or Spring — the connector is a pure producer
    // exercised against a capturing in-memory sink.
    testImplementation(libs.assertj.core)
    // WireMock (BackendPlan §14: sync engine tested against WireMock'd connectors);
    // standalone (shaded) jar avoids Jetty/Tomcat classpath clashes. Version literal ->
    // catalog pass (DEBT-001).
    testImplementation("org.wiremock:wiremock-standalone:3.9.1")
}
