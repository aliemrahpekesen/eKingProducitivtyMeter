// Applied only to composition-root modules that produce a boot jar / container image
// (BackendPlan.md §1: eip-app now; eip-workers gains its worker main + this plugin when the
// worker runtime content lands in Phase 1). Builds on eip.java-conventions.

// NOT alias(libs.plugins...): a precompiled script plugin's OWN plugins {} block compiles in an
// isolated early pass with no access to the `libs` catalog accessor (see eip.java-conventions.gradle.kts
// for the confirmed failure mode) — DEBT-001 residual, documented. Both versions are still
// catalog-sourced via buildSrc's own classpath (buildSrc/build.gradle.kts), not duplicated here.
plugins {
    id("eip.java-conventions")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}
