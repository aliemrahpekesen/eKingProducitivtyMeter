// eip-reports — deterministic report engine (TASK-0022 M4 Wave R1, ADR-023): composes
// eip-analytics query ports into a versioned ReportDocument and renders it to self-contained HTML.
// AI-composed report types (Report Composition agent) are future work — eip-ai stays a declared
// dependency for that follow-up though nothing in v0.1 uses it yet. Allowed deps: eip-core,
// eip-tenancy, eip-analytics, eip-ai (BackendPlan.md §1). Layering (BackendPlan §2.4): api
// (ports + DTOs) -> application (@Service; pure ReportComposer/ReportHtmlRenderer) -> persistence
// (@Repository JdbcClient).
plugins {
    id("eip.modulith-conventions")
}
dependencies {
    api(project(":eip-core"))
    implementation(project(":eip-tenancy"))
    // `api`, not `implementation`: ReportDocument/ReportTeamSection embed eip-analytics'
    // TrendPointView/RecommendationView directly (reused, not cloned — TASK-0022) as part of
    // eip-reports' own API surface, so a consumer compiling against com.eip.reports.api
    // transitively needs com.eip.analytics.api too.
    api(project(":eip-analytics"))
    implementation(project(":eip-ai"))

    // Spring application/infrastructure layer (BackendPlan §2/§5), mirroring eip-analytics:
    // JdbcClient + transactions via eip-tenancy's TenantTransactionRunner. Jackson is `api` because
    // ReportDocument (parsed back from the persisted `document` jsonb) is exposed via the module's
    // own API surface (ReportDocumentView). jackson-datatype-jsr310 is declared directly (not just
    // relied on transitively via eip-app's web starter) so the module's own tests can serialize the
    // document's Instant fields without a Spring context.
    api(platform(libs.spring.boot.bom))
    implementation(libs.spring.context)
    implementation(libs.spring.jdbc)
    implementation(libs.spring.tx)
    api(libs.jackson.databind)
    implementation(libs.jackson.datatype.jsr310)

    // Spring Modulith package metadata (annotations only; application-wide verification in eip-app).
    compileOnly(platform(libs.spring.modulith.bom))
    compileOnly(libs.spring.modulith.core)

    // Module integration tests run the real insert/list/find SQL against Testcontainers PostgreSQL
    // under the NOBYPASSRLS role, migrated with the app's Flyway scripts (single schema source) —
    // mirrors eip-analytics's harness.
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.database.postgresql)
    testImplementation(libs.assertj.core)
}
