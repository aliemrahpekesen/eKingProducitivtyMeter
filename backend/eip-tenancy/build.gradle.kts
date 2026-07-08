// eip-tenancy — org/RBAC/audit/tenant-context. Allowed dep: eip-core (BackendPlan.md §1).
// Owns the tenant-context mechanism that sets the transaction-scoped RLS GUC (DatabasePlan §12).
plugins {
    id("eip.modulith-conventions")
}
dependencies {
    api(project(":eip-core"))
    api(libs.jspecify)

    // The tenant-context binder is pure JDBC (java.sql); no Spring at runtime. Test-only: Mockito
    // (third-party java.sql seam, CodingStandards §5) + AssertJ. Versions managed by the Boot BOM
    // since this library module has no io.spring.dependency-management of its own.
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.mockito:mockito-core")
    testImplementation(libs.assertj.core)
}
