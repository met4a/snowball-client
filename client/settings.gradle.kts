pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
    }
}

plugins {
    // One source tree, one build per Minecraft version (https://stonecutter.kikugie.dev).
    id("dev.kikugie.stonecutter") version "0.9.8"
    // Picks the right Loom per version: remapping with Mojang mappings for 1.21.x, none for 26.x.
    id("dev.kikugie.loom-back-compat") version "0.4.2"
    // Lets Gradle download the Java toolchain each version needs.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    create(rootProject) {
        versions("1.21.11", "26.2")
        // The code in src/ is written for this version; other versions are produced from it.
        vcsVersion = "26.2"
    }
}

rootProject.name = "snowball-client"
