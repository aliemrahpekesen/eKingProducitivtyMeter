// eip-app — composition root / main API app (BackendPlan.md §1). Depends on all modules; the
// only module producing a boot jar in this scaffold. Owns the Flyway migrations
// (DatabasePlan.md §7: db/migration lives here). Business logic arrives in Phase 0 SPRINT-01+.
plugins {
    id("eip.boot-app-conventions")
}
dependencies {
    implementation(project(":eip-core"))
    implementation(project(":eip-tenancy"))
    implementation(project(":eip-connectors"))
    implementation(project(":eip-ingestion"))
    implementation(project(":eip-analytics"))
    implementation(project(":eip-ai"))
    implementation(project(":eip-reports"))
    implementation("org.springframework.boot:spring-boot-starter")

    // Web + OpenAPI: the first /api/v1 read surface (TASK-0010). RFC 7807 problem+json is built into
    // Spring 6 (ProblemDetail); springdoc generates the OpenAPI 3 contract at /v3/api-docs.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation(libs.springdoc.openapi.webmvc)

    // Persistence: JDBC + Flyway forward-only migrations + PostgreSQL driver (DatabasePlan §3/§7).
    // Versions are managed by io.spring.dependency-management (boot-app-conventions).
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Integration test: real PostgreSQL 16 + pgvector via Testcontainers proves the deployable
    // V1 baseline + R__rls_policies apply and enforce RLS end-to-end (DatabasePlan §14).
    // spring-boot-starter-test supplies JUnit 5 + AssertJ (eip-app uses boot-app-conventions, not
    // modulith-conventions, so it needs its own test framework).
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.postgresql:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
