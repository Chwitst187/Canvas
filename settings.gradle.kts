import java.util.*

pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        maven {
            name = "canvasmcReleases"
            url = uri("https://maven.canvasmc.io/releases")
        }
        maven {
            name = "canvasmcSnapshots"
            url = uri("https://maven.canvasmc.io/snapshots")
        }
        // Some setups only expose Canvas artifacts via Nexus-style /repository/* paths.
        maven {
            name = "canvasmcNexusReleases"
            url = uri("https://maven.canvasmc.io/repository/releases")
        }
        maven {
            name = "canvasmcNexusSnapshots"
            url = uri("https://maven.canvasmc.io/repository/snapshots")
        }
    }

    resolutionStrategy {
        val weaverPatcherModule = providers.gradleProperty("weaverPatcherModule").orNull
        val weaverCoreModule = providers.gradleProperty("weaverCoreModule").orNull

        eachPlugin {
            when (requested.id.id) {
                // Optional fallback: allows resolving plugin IDs from direct module coordinates.
                // Example: -PweaverPatcherModule=io.canvasmc.weaver:weaver-patcher
                "io.canvasmc.weaver.patcher" -> weaverPatcherModule?.let { useModule("$it:${requested.version}") }
                "io.canvasmc.weaver.core" -> weaverCoreModule?.let { useModule("$it:${requested.version}") }
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

if (!file(".git").exists()) {
    val errorText = """
        
        =====================[ ERROR ]=====================
         The Canvas project directory is not a properly cloned Git repository.
         
         In order to build Canvas from source you must clone
         the Canvas repository using Git, not download a code
         zip from GitHub.
         
         Built Canvas jars are available for download at
         https://canvasmc.io/downloads
         
         See https://github.com/CraftCanvasMC/Canvas/blob/HEAD/CONTRIBUTING.md
         for further information on building and modifying Canvas.
        ===================================================
    """.trimIndent()
    error(errorText)
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "canvas"
for (name in listOf("canvas-api", "canvas-server")) {
    val projName = name.lowercase(Locale.ENGLISH)
    include(projName)
    findProject(":$projName")!!.projectDir = file(name)
}

rootDir.listFiles()
    ?.filter { it.isDirectory && (it.name.endsWith("-debug", ignoreCase = true) || it.name.endsWith("-plugin", ignoreCase = true)) }
    ?.forEach { dir ->
        val projName = dir.name.lowercase(Locale.ENGLISH)
        include(projName)
        findProject(":$projName")!!.projectDir = dir
    }
