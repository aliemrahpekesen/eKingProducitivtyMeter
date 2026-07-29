// eip-ai — agent runtime, LLM SPI, RAG, MCP. Allowed deps: eip-core, eip-tenancy,
// eip-analytics (metric query API only) (BackendPlan.md §1). M6-A (ADR-024) ships the first
// slice: a thin, OPT-IN (default OFF) per-tenant AI explanation layer — an LLM SPI (spi/), pure
// JDK-HttpClient provider clients (providers/), policy/explain/narrate ports (api/), prompt
// composition + numeric cross-check + orchestration (application/), and tenant policy/audit
// persistence (persistence/). eip-ai deliberately does NOT depend on eip-reports even though
// `narrate` explains a report's content: eip-reports already declares `eip-ai` as a dependency
// (future Report Composition agent invocation, BackendPlan §1), so the reverse edge would both
// violate the documented allowed-dependency table and fail as a circular Gradle project
// dependency — `NarrateReportUseCase` therefore takes eip-ai's own `ReportNarrativeInput` DTO
// (mirroring `com.eip.reports.api.ReportTotals`/`ReportTeamSection`'s shape), assembled by
// `eip-app` from a `GetReportQuery` read (see ADR-024 for the full rationale). Layering
// (BackendPlan §2.4): api (ports + DTOs) -> application (@Service; pure PromptComposer/
// NumericCrossChecker) -> persistence (@Repository JdbcClient); spi/providers are pure/
// JDK-HttpClient-only, mirroring eip-connectors' SourceHttp style.
plugins {
    id("eip.modulith-conventions")
}
dependencies {
    api(project(":eip-core"))
    implementation(project(":eip-tenancy"))
    implementation(project(":eip-analytics"))

    // Spring application/infrastructure layer (BackendPlan §2/§5), mirroring eip-reports:
    // JdbcClient + transactions via eip-tenancy's TenantTransactionRunner, and Micrometer for the
    // `eip.ai.calls{purpose,status}` call counter (ExplainService).
    api(platform(libs.spring.boot.bom))
    implementation(libs.spring.context)
    implementation(libs.spring.jdbc)
    implementation(libs.spring.tx)
    implementation(libs.jackson.databind)
    implementation("io.micrometer:micrometer-core")

    // Spring Modulith package metadata (annotations only; application-wide verification in eip-app).
    compileOnly(platform(libs.spring.modulith.bom))
    compileOnly(libs.spring.modulith.core)

    // Module integration tests run the real policy upsert/find + secret-storage SQL against
    // Testcontainers PostgreSQL under the NOBYPASSRLS role, migrated with the app's Flyway
    // scripts (single schema source) — mirrors eip-reports's harness. WireMock stubs the
    // Ollama/OpenAI-compatible provider clients (BackendPlan §14 pattern).
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.database.postgresql)
    testImplementation(libs.assertj.core)
    testImplementation(libs.wiremock.standalone)
}
