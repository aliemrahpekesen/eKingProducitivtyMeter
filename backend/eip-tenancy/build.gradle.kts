// eip-tenancy — org/RBAC/audit/tenant-context. Allowed dep: eip-core (BackendPlan.md §1).
// Owns the tenant-context mechanism for the RLS GUC (DatabasePlan §12): the pure-JDBC
// RlsTenantBinder primitive plus the Spring-transaction TenantTransactionRunner — the single
// tenant-aware transaction boundary (BackendPlan §2.4/§6); no manual commit/rollback elsewhere.
plugins {
    id("eip.modulith-conventions")
}
dependencies {
    api(project(":eip-core"))
    api(libs.jspecify)

    // Spring transaction + JDBC support for the tenant-aware transaction runner. Versions from the
    // Boot BOM. spring-tx is `api` because the runner's constructor takes PlatformTransactionManager.
    api(platform(libs.spring.boot.bom))
    api(libs.spring.tx)
    implementation(libs.spring.jdbc)
    implementation(libs.spring.context) // @Service/@Repository admin layer (ADR-022 M1)

    // DEBT-024 Wave 3A (audit hash-chain): Jackson canonicalizes audit rows to deterministic JSON
    // for hashing (mirrors eip-ingestion's RawPayloadCodec); Micrometer records write/chain/verify
    // counters and the chain-lag gauge; micrometer-tracing's Tracer (API only — eip-app's OTel
    // bridge supplies the bean) is how AuditService reads the current span's traceId, mirroring
    // com.eip.app.security.ProblemResponses/ApiExceptionHandler's exact idiom.
    implementation(libs.jackson.databind)
    implementation("io.micrometer:micrometer-core")
    implementation("io.micrometer:micrometer-tracing")

    // Spring Modulith package metadata (annotations only, compile-time; verified application-wide
    // from eip-app's ApplicationModules test — BackendPlan §3).
    compileOnly(platform(libs.spring.modulith.bom))
    compileOnly(libs.spring.modulith.core)
    testCompileOnly(platform(libs.spring.modulith.bom))
    testCompileOnly(libs.spring.modulith.core)

    // Test-only: Mockito (third-party java.sql/Spring-tx seam, CodingStandards §5) + AssertJ.
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.mockito:mockito-core")
    testImplementation(libs.assertj.core)
    // Module integration tests run the admin/secrets SQL against Testcontainers PostgreSQL under
    // the NOBYPASSRLS role, migrated with the app's Flyway scripts (single schema source).
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.database.postgresql)
}
