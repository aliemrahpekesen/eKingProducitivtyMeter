// eip-app — composition root / main API app (BackendPlan.md §1). Depends on all modules; the
// only module producing a boot jar in this scaffold. Contains no business logic — a Spring Boot
// app-class stub only (OpenAPI, security chain, controllers arrive in Phase 0 SPRINT-01+).
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
}
