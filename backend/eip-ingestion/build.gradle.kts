// eip-ingestion — sync engine, raw staging, normalizers, DLQ. Allowed deps: eip-core,
// eip-tenancy, eip-connectors (BackendPlan.md §1).
plugins {
    id("eip.modulith-conventions")
}
dependencies {
    api(project(":eip-core"))
    implementation(project(":eip-tenancy"))
    implementation(project(":eip-connectors"))

    // Raw staging persists connector payloads as jsonb and normalizers parse them back; Jackson is
    // the platform's JSON library (already the transitive choice via Spring in eip-app). Version is
    // pinned by the Spring Boot BOM so it never drifts from the app's. Persistence itself is plain
    // java.sql (no Spring here — this stays a library, like eip-tenancy); the Connection is supplied
    // by eip-app inside a tenant-bound transaction.
    implementation(platform(libs.spring.boot.bom))
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // Test-only: Mockito to drive the java.sql seam (mirrors eip-tenancy), AssertJ assertions.
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.mockito:mockito-core")
    testImplementation(libs.assertj.core)
}
