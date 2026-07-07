// Convention for Spring Modulith application modules (BackendPlan.md §1, §3). Builds on
// eip.java-conventions and makes ArchUnit + JUnit 5 available so the module-boundary `ModularityTests`
// can be authored with eip-core in TASK-0005 (P0-E2-S1, CC-1). The `spring-modulith-starter-core`
// dependency and the concrete boundary test are added by that task; wiring the test libraries here
// keeps the `./gradlew check` CI stage (TASK-0002) the single entry point that runs them once present.

plugins {
    id("eip.java-conventions")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
