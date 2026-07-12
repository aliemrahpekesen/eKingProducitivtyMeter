// eip-analytics — metric engines, definitions registry, risk scoring. Allowed deps: eip-core,
// eip-tenancy (BackendPlan.md §1). Layering (BackendPlan §2.4): api (query/use-case ports + DTOs)
// → application (@Service) → friction (pure domain engine, framework-free) → persistence
// (@Repository JdbcClient/JdbcTemplate). Reads canonical schemas read-only; writes analytics only
// (ADR-019).
plugins {
    id("eip.modulith-conventions")
}
dependencies {
    api(project(":eip-core"))
    implementation(project(":eip-tenancy"))

    // Spring application/infrastructure layer (BackendPlan §2/§5): stereotypes + JdbcClient/
    // JdbcTemplate; transactions via eip-tenancy's TenantTransactionRunner. Jackson is `api`
    // because FrictionMetricView exposes the definition's inputs as a JsonNode. Boot-BOM pinned.
    api(platform(libs.spring.boot.bom))
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-jdbc")
    implementation("org.springframework:spring-tx")
    api("com.fasterxml.jackson.core:jackson-databind")

    // Spring Modulith package metadata (annotations only; application-wide verification in eip-app).
    compileOnly(platform(libs.spring.modulith.bom))
    compileOnly("org.springframework.modulith:spring-modulith-core")

    // Module integration tests run the real compute/read SQL against Testcontainers PostgreSQL
    // under the NOBYPASSRLS role, migrated with the app's Flyway scripts (single schema source).
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.postgresql:postgresql")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation(libs.assertj.core)
}
