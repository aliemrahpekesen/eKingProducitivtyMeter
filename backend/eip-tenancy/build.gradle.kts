// eip-tenancy — org/RBAC/audit/tenant-context. Allowed dep: eip-core (BackendPlan.md §1).
// Owns the tenant-context mechanism for the RLS GUC (DatabasePlan §12): the pure-JDBC
// RlsTenantBinder primitive plus the Spring-transaction TenantTransactionRunner — the single
// tenant-aware transaction boundary (BackendPlan §2.4/§6); no manual commit/rollback elsewhere.
plugins {
    id("eip.modulith-conventions")
}
dependencies {
    api(project(":eip-core"))
    api(libs.jspecify)

    // Spring transaction + JDBC support for the tenant-aware transaction runner. Versions from the
    // Boot BOM. spring-tx is `api` because the runner's constructor takes PlatformTransactionManager.
    api(platform(libs.spring.boot.bom))
    api("org.springframework:spring-tx")
    implementation("org.springframework:spring-jdbc")

    // Spring Modulith package metadata (annotations only, compile-time; verified application-wide
    // from eip-app's ApplicationModules test — BackendPlan §3).
    compileOnly(platform(libs.spring.modulith.bom))
    compileOnly("org.springframework.modulith:spring-modulith-core")

    // Test-only: Mockito (third-party java.sql/Spring-tx seam, CodingStandards §5) + AssertJ.
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.mockito:mockito-core")
    testImplementation(libs.assertj.core)
}
