// eip-ingestion — sync engine, raw staging, normalizers, DLQ. Allowed deps: eip-core,
// eip-tenancy, eip-connectors (BackendPlan.md §1).
plugins {
    id("eip.modulith-conventions")
}
dependencies {
    api(project(":eip-core"))
    implementation(project(":eip-tenancy"))
    implementation(project(":eip-connectors"))
}
