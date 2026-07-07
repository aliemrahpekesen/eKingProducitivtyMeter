// eip-workers — deployable async worker runtime, a second composition root (BackendPlan.md §1).
// In this scaffold it is a plain library skeleton: its worker main class and boot-app-conventions
// are added when the worker runtime content lands (Phase 1, SPRINT-05) so the boot jar has a
// mainClass. It composes the ingestion/ai/reports modules it will host.
plugins {
    id("eip.modulith-conventions")
}
dependencies {
    implementation(project(":eip-ingestion"))
    implementation(project(":eip-ai"))
    implementation(project(":eip-reports"))
}
