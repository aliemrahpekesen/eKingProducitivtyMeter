// eip-ai — agent runtime, LLM SPI, RAG, MCP. Allowed deps: eip-core, eip-tenancy,
// eip-analytics (metric query API only) (BackendPlan.md §1).
plugins {
    id("eip.modulith-conventions")
}
dependencies {
    api(project(":eip-core"))
    implementation(project(":eip-tenancy"))
    implementation(project(":eip-analytics"))
}
