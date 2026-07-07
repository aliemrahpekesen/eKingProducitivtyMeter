// buildSrc hosts the shared convention plugins (RepositoryStructure.md §2). Applying a plugin
// marker here makes `id("...")` usable inside the precompiled script plugins below.

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
}
