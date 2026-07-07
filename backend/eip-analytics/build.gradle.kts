// eip-analytics — metric engines, definitions registry, risk scoring. Allowed deps: eip-core,
// eip-tenancy (BackendPlan.md §1).
plugins {
    id("eip.modulith-conventions")
}
dependencies {
    api(project(":eip-core"))
    implementation(project(":eip-tenancy"))
}
