// eip-analytics — metric engines, definitions registry, risk scoring. Allowed deps: eip-core,
// eip-tenancy (BackendPlan.md §1). Reads the canonical model (read-only) and writes the analytics
// schema; computation is pure Java over plain java.sql (no Spring — the Connection is supplied by
// eip-app inside a tenant-bound transaction, like eip-ingestion/eip-tenancy).
plugins {
    id("eip.modulith-conventions")
}
dependencies {
    api(project(":eip-core"))
    implementation(project(":eip-tenancy"))

    // Test-only: Mockito for the java.sql seam, AssertJ assertions (CodingStandards §5).
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.mockito:mockito-core")
    testImplementation(libs.assertj.core)
}
