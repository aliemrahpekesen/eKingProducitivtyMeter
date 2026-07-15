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
    implementation(libs.spring.context)
    implementation(libs.spring.jdbc)
    implementation(libs.spring.tx)
    implementation("org.springframework.boot:spring-boot-autoconfigure") // @ConditionalOnProperty
    implementation(libs.jackson.databind)

    // Spring Modulith package metadata (annotations only; application-wide verification in eip-app).
    compileOnly(platform(libs.spring.modulith.bom))
    compileOnly(libs.spring.modulith.core)

    // Module integration tests run the real staging + normalization SQL against Testcontainers
    // PostgreSQL under the NOBYPASSRLS role, migrated with the app's Flyway scripts (schema truth
    // lives in eip-app's db/migration — single source, referenced by filesystem location).
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.database.postgresql)
    testImplementation(libs.assertj.core)
    // The event envelope carries java.time.Instant fields; production serialization uses eip-app's
    // Boot-autoconfigured ObjectMapper (jsr310 already on its runtime classpath via
    // spring-boot-starter-json). Module-local tests construct a plain ObjectMapper directly, so the
    // module needs the datatype module on its own test classpath.
    testImplementation(libs.jackson.datatype.jsr310)
}
