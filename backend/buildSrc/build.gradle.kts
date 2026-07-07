// buildSrc hosts the shared convention plugins (RepositoryStructure.md §2). Applying a plugin
// marker here makes `id("...")` usable inside the precompiled script plugins below.
// (buildSrc cannot consume the main build's version catalog directly — see DEBT-001; the versions
// here are kept in sync with backend/gradle/libs.versions.toml by hand until DEBT-001 is paid.)

plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-gradle-plugin:3.4.1")
    implementation("io.spring.gradle:dependency-management-plugin:1.1.7")
    implementation("com.diffplug.spotless:spotless-plugin-gradle:6.25.0")
    implementation("net.ltgt.gradle:gradle-errorprone-plugin:4.1.0")
}
