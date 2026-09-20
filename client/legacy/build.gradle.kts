plugins {
    // Legacy Looming extends Fabric Loom for Minecraft 1.3-1.13.2; both versions must match.
    id("net.fabricmc.fabric-loom-remap") version "1.16-SNAPSHOT"
    id("legacy-looming") version "1.16-SNAPSHOT"
}

val minecraftVersion = property("minecraft_version").toString()
// One version number for every Snowball Client build: read it from the main build's properties.
val modVersion = Regex("""mod\.version\s*=\s*"([^"]+)"""")
    .find(rootDir.resolve("../stonecutter.properties.toml").readText())!!.groupValues[1]
// Minecraft 1.8.9 itself asks for Java 8, but it runs fine on modern Java and the shared client code
// is written for it, so the launcher starts Snowball instances of this version with Java 21.
val javaVersion = 21

version = "$modVersion+$minecraftVersion"
base.archivesName = "snowball-client"

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(legacy.yarn(minecraftVersion, property("yarn_build").toString()))
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.legacyfabric.legacy-fabric-api:legacy-fabric-api:${property("legacy_fabric_api_version")}")
}

// Client code that never touches Minecraft is shared with the modern builds. A file of the same name
// under this project's own src/main/java replaces the shared one, which is how 1.8.9 provides its
// version of everything that does touch Minecraft.
val sharedRoot = rootDir.resolve("../src/main/java")
val ownSources = file("src/main/java")
val touchesMinecraft = Regex("""^import\s+(static\s+)?(net\.minecraft|com\.mojang|net\.fabricmc\.fabric|org\.joml|org\.slf4j)\.""", RegexOption.MULTILINE)
val sharedFiles = sharedRoot.walkTopDown()
    .filter { it.isFile && it.extension == "java" }
    .map { it.relativeTo(sharedRoot).invariantSeparatorsPath }
    .filter { !ownSources.resolve(it).exists() }
    .filter { !touchesMinecraft.containsMatchIn(sharedRoot.resolve(it).readText()) }
    .toList()

val copySharedSources = tasks.register<Sync>("copySharedSources") {
    group = "build"
    description = "Copies the Minecraft-independent client code this build shares with the modern versions"
    from(sharedRoot) { include(sharedFiles) }
    into(layout.buildDirectory.dir("generated/shared/java"))
}

// The icon, logo and language file live with the modern builds; they are copied in under assets/ so the
// paths inside the jar match what the client asks for.
val copySharedAssets = tasks.register<Sync>("copySharedAssets") {
    group = "build"
    description = "Copies the shared assets (icon, logo, language file) into this build"
    from(rootDir.resolve("../src/main/resources/assets")) { into("assets") }
    into(layout.buildDirectory.dir("generated/shared/resources"))
}

sourceSets.main {
    java.srcDir(copySharedSources)
    resources.srcDir(copySharedAssets)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        options.release = javaVersion
        options.encoding = "UTF-8"
        // Show every error when porting, not just the first hundred.
        options.compilerArgs.addAll(listOf("-Xmaxerrs", "5000"))
    }

    processResources {
        val props = mapOf("version" to modVersion, "minecraft" to minecraftVersion, "java" to javaVersion.toString())
        inputs.properties(props)
        filesMatching("fabric.mod.json") { expand(props) }
        filesMatching("snowballclient.mixins.json") { expand(props) }
    }

    // Same output folder as the main build: the launcher bundles every jar in build/libs/launcher/.
    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds the Minecraft $minecraftVersion jar and copies it to build/libs/launcher/"
        from(remapJar.flatMap { it.archiveFile })
        into(rootDir.resolve("../build/libs/launcher"))
    }
}
