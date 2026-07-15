// buildSrc hosts the shared convention plugins (RepositoryStructure.md §2). Applying a plugin
// marker here makes `id("...")` usable inside the precompiled script plugins below.
// buildSrc/settings.gradle.kts wires this project's own `libs` version catalog accessor at the
// root build's gradle/libs.versions.toml (DEBT-001, paid) — no version literal is duplicated here.

plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(libs.spring.boot.gradle.plugin)
    implementation(libs.spring.dependency.management.plugin)
    implementation(libs.spotless.plugin.gradle)
    implementation(libs.gradle.errorprone.plugin)
}
