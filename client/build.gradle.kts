plugins {
    // Applies the Loom variant each Minecraft version needs (see settings.gradle.kts).
    id("dev.kikugie.loom-back-compat")
}

// Read once at project level; inside task blocks property() would look on the task instead.
val modVersion = project.property("mod.version").toString()
val minecraftRange = project.property("mod.mc_compat").toString()

version = "$modVersion+${sc.current.version}"
base.archivesName = "snowball-client"

val requiredJava: JavaVersion = if (sc.current.parsed >= "26.1") JavaVersion.VERSION_25 else JavaVersion.VERSION_21

dependencies {
    minecraft("com.mojang:minecraft:${sc.current.version}")
    // Mojang mappings on obfuscated versions (1.21.x); 26.x ships with readable names.
    loomx.applyMojangMappings()
    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

loom {
    runConfigs.all {
        // Keep the dev run directory out of the source tree and shared between versions.
        runDirectory = rootProject.file("run")
    }
}

fabricApi {
    // Real in-game UI tests: `./gradlew :26.2:runClientGameTest` drives the menu and takes screenshots.
    configureTests {
        createSourceSet = true
        modId = "snowballclient-gametest"
        enableGameTests = false
        enableClientGameTests = true
        eula = true
    }
}

java {
    withSourcesJar()
    sourceCompatibility = requiredJava
    targetCompatibility = requiredJava
    toolchain {
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        options.release = requiredJava.majorVersion.toInt()
        options.encoding = "UTF-8"
        // Show every error when porting to another version, not just the first hundred.
        options.compilerArgs.addAll(listOf("-Xmaxerrs", "5000"))
    }

    processResources {
        val props = mapOf(
            "version" to modVersion,
            "minecraft" to minecraftRange,
            "java" to requiredJava.majorVersion,
        )
        inputs.properties(props)
        filesMatching("fabric.mod.json") { expand(props) }
        filesMatching("snowballclient.mixins.json") { expand(props) }
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    withType<Jar>().configureEach {
        from(rootProject.file("../LICENSE")) { rename { "${it}_snowball-client" } }
    }

    // Collects every version's jar into build/libs/launcher/, the folder the launcher bundles and reads in development.
    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds this version's jar and copies it to build/libs/launcher/"
        from(loomx.modJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.dir("libs/launcher"))
    }

    // Website screenshots in real worlds: ./gradlew :26.2:runClientGameTest -Pscene
    matching { it.name == "runClientGameTest" }.configureEach {
        if (project.hasProperty("scene")) (this as JavaExec).systemProperty("snowball.scene", "true")
    }
}
