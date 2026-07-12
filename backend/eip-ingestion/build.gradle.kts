// eip-ingestion — sync engine, raw staging, normalizers, DLQ; the single canonical writer
// (ADR-019). Allowed deps: eip-core, eip-tenancy, eip-connectors (BackendPlan.md §1).
// Layering (BackendPlan §2.4): api (ports) → application (@Service use cases) → persistence
// (@Repository JdbcClient/JdbcTemplate adapters). Domain/connector contracts stay pure Java.
plugins {
    id("eip.modulith-conventions")
}
dependencies {
    api(project(":eip-core"))
    implementation(project(":eip-tenancy"))
    implementation(project(":eip-connectors"))

    // Spring application/infrastructure layer (BackendPlan §2/§5): stereotypes + JdbcClient/
    // JdbcTemplate + transactions via eip-tenancy's TenantTransactionRunner. Jackson serializes
    // raw payloads to jsonb and parses them back in normalization. Versions from the Boot BOM.
    implementation(platform(libs.spring.boot.bom))
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-jdbc")
    implementation("org.springframework:spring-tx")
    implementation("org.springframework.boot:spring-boot-autoconfigure") // @ConditionalOnProperty
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // Spring Modulith package metadata (annotations only; application-wide verification in eip-app).
    compileOnly(platform(libs.spring.modulith.bom))
    compileOnly("org.springframework.modulith:spring-modulith-core")

    // Module integration tests run the real staging + normalization SQL against Testcontainers
    // PostgreSQL under the NOBYPASSRLS role, migrated with the app's Flyway scripts (schema truth
    // lives in eip-app's db/migration — single source, referenced by filesystem location).
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.postgresql:postgresql")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation(libs.assertj.core)
}
