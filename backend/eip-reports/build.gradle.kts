// eip-reports — report/template engine, artifact library. Allowed deps: eip-core, eip-tenancy,
// eip-analytics, eip-ai (Report Composition agent) (BackendPlan.md §1).
plugins {
    id("eip.modulith-conventions")
}
dependencies {
    api(project(":eip-core"))
    implementation(project(":eip-tenancy"))
    implementation(project(":eip-analytics"))
    implementation(project(":eip-ai"))
}
