// Marker convention for Spring Modulith application modules (BackendPlan.md §1, §3). It builds
// on eip.java-conventions and is the hook where spring-modulith-starter-core, the test fixtures,
// and the ModularityTests source set are added. Those arrive with the eip-core shared kernel and
// the module-boundary test in TASK-0005 (P0-E2-S1, CC-1); this scaffold keeps the plugin present
// so module build files can already declare `id("eip.modulith-conventions")` without churn.

plugins {
    id("eip.java-conventions")
}
