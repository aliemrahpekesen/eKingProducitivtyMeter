// Applied only to composition-root modules that produce a boot jar / container image
// (BackendPlan.md §1: eip-app now; eip-workers gains its worker main + this plugin when the
// worker runtime content lands in Phase 1). Builds on eip.java-conventions.

plugins {
    id("eip.java-conventions")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}
