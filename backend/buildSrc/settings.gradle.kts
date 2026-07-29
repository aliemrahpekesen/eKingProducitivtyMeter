rootProject.name = "buildSrc"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    // DEBT-001: buildSrc is a separate build, so it cannot see the root build's implicit
    // gradle/libs.versions.toml catalog automatically — this explicitly points buildSrc's own
    // "libs" catalog at the SAME file, so buildSrc/build.gradle.kts and the precompiled
    // convention plugins under src/main/kotlin get the identical `libs` accessor as every module
    // build file, with zero duplicated version literals.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
