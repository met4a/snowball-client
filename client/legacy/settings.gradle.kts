pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.legacyfabric.net/") { name = "Legacy Fabric" }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Lets Gradle download the Java toolchain this build needs.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "snowball-client-legacy"
