// eip-connectors — Connector SPI + connectors. Allowed deps: eip-core, eip-tenancy
// (tenant context only) (BackendPlan.md §1).
plugins {
    id("eip.modulith-conventions")
}
dependencies {
    api(project(":eip-core"))
    implementation(project(":eip-tenancy"))
}
