// eip-tenancy — org/RBAC/audit/tenant-context. Allowed dep: eip-core (BackendPlan.md §1).
plugins {
    id("eip.modulith-conventions")
}
dependencies {
    api(project(":eip-core"))
}
