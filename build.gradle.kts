import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import io.papermc.paperweight.tasks.RebuildGitPatches
import io.papermc.paperweight.tasks.RebuildBaseGitPatches
import java.io.File

plugins {
    java
    id("io.canvasmc.weaver.patcher") version "2.3.12"
    id("xyz.jpenilla.resource-factory-paper-convention") version "1.3.1" apply false
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"

paperweight {
    filterPatches = false
    upstreams.folia {
        ref = providers.gradleProperty("foliaCommit")

        patchFile {
            path = "folia-server/build.gradle.kts"
            outputFile = file("canvas-server/build.gradle.kts")
            patchFile = file("canvas-server/build.gradle.kts.patch")
        }
        patchFile {
            path = "folia-api/build.gradle.kts"
            outputFile = file("canvas-api/build.gradle.kts")
            patchFile = file("canvas-api/build.gradle.kts.patch")
        }
        patchRepo("paperApi") {
            upstreamPath = "paper-api"
            patchesDir = file("canvas-api/paper-patches")
            outputDir = file("paper-api")
        }
        patchDir("foliaApi") {
            upstreamPath = "folia-api"
            excludes = listOf("build.gradle.kts", "build.gradle.kts.patch", "paper-patches")
            patchesDir = file("canvas-api/folia-patches")
            outputDir = file("folia-api")
        }
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = Charsets.UTF_8.name()
        options.release = 21
        options.isFork = true
        options.compilerArgs.addAll(listOf("-Xlint:-deprecation", "-Xlint:-removal"))
    }
    tasks.withType<Javadoc>().configureEach {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources>().configureEach {
        filteringCharset = Charsets.UTF_8.name()
    }
    tasks.withType<Test>().configureEach {
        testLogging {
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
            events(TestLogEvent.STANDARD_OUT)
        }
    }
    extensions.configure<PublishingExtension> {
        repositories {
            maven("https://maven.canvasmc.io/snapshots") {
                name = "canvasmc"
                credentials {
                    username = providers.environmentVariable("PUBLISH_USER").orNull
                    password = providers.environmentVariable("PUBLISH_TOKEN").orNull
                }
            }
        }
    }

    if (project.name.endsWith("-debug") || project.name.endsWith("-plugin")) {
        apply(plugin = "xyz.jpenilla.resource-factory-paper-convention")
        dependencies {
            compileOnly(rootProject.projects.canvasServer)
            compileOnly(rootProject.projects.canvasApi)
        }
        extensions.configure<xyz.jpenilla.resourcefactory.paper.PaperPluginYaml> {
            apiVersion.set(providers.gradleProperty("apiVersion"))
            version = "SNAPSHOT-DEV"
            main = project.findProperty("main")?.toString()?.replace("\"", "")
            authors = listOf("CanvasMC")
            foliaSupported = true
        }

        tasks.processResources {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
    }
}

// patching scripts
tasks.register("fixupMinecraftFilePatches") {
    dependsOn(":canvas-server:fixupMinecraftSourcePatches")
}

tasks.register("rebuildMinecraftSinglePatch") {
    group = "patching"
    description = "Rebuilds minecraft source patches, then keeps only one target patch file changed via -Ppatch=<relative patch path>."
    dependsOn("fixupMinecraftFilePatches", "rebuildMinecraftFilePatches")

    doLast {
        val patchProperty = project.findProperty("patch")?.toString()?.trim()
            ?: throw GradleException("Missing -Ppatch=<relative path>, e.g. -Ppatch=net/minecraft/server/level/ServerEntity.java.patch")

        val sourcesPatchRoot = rootProject.file("canvas-server/minecraft-patches/sources").canonicalFile
        val normalizedPatch = patchProperty.removePrefix("/").replace('\\', '/')
        val targetPatch = File(sourcesPatchRoot, normalizedPatch).canonicalFile

        if (!targetPatch.path.startsWith(sourcesPatchRoot.path + File.separator)) {
            throw GradleException("Patch path must stay inside canvas-server/minecraft-patches/sources")
        }
        if (!targetPatch.exists()) {
            throw GradleException("Patch file does not exist: ${targetPatch.relativeTo(rootDir)}")
        }

        val allPatchFiles = fileTree(sourcesPatchRoot) {
            include("**/*.patch")
        }.files

        val filesToRestore = allPatchFiles
            .filter { it.canonicalFile != targetPatch }
            .map { it.relativeTo(rootDir).invariantSeparatorsPath }

        if (filesToRestore.isNotEmpty()) {
            providers.exec {
                commandLine("git", "checkout", "--", *filesToRestore.toTypedArray())
            }
        }

        logger.lifecycle("Kept only target minecraft patch for update: ${targetPatch.relativeTo(rootDir).invariantSeparatorsPath}")
    }
}

// TODO: remove me in 26.1
tasks.register("createPublisherJar") {
    dependsOn(":canvas-server:createMojmapPublisherJar")
}

// TODO: remove me in 26.1
tasks.register("cleanCreatePublisherJar") {
    dependsOn(":canvas-server:cleanCreateMojmapPublisherJar")
}
