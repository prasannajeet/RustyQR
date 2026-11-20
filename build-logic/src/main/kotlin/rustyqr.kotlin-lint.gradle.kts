import io.gitlab.arturbosch.detekt.Detekt

// Lint setup shared by every Kotlin module: detekt (with the formatting rules) and ktlint, both at the repository's
// own rules. Modules add only what is specific to them: which sources detekt scans and any type-resolved task.
plugins {
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
}

val detektVersion =
    extensions
        .getByType<VersionCatalogsExtension>()
        .named("libs")
        .findVersion("detekt")
        .get()
        .requiredVersion

dependencies {
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:$detektVersion")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
}

tasks.withType<Detekt>().configureEach {
    // Match on the project-relative path so a checkout that lives under a /build/ directory is not skipped.
    val moduleRoot = project.projectDir
    exclude {
        val path = it.file.relativeTo(moduleRoot).invariantSeparatorsPath
        path.startsWith("build/") || path.contains("/build/")
    }
}

ktlint {
    android.set(true)
    verbose.set(true)
    filter {
        // Project-relative, like the detekt exclude: a checkout under some /build/ directory must still be linted.
        val moduleRoot = project.projectDir
        exclude {
            val path = it.file.relativeTo(moduleRoot).invariantSeparatorsPath
            path.startsWith("build/") || path.contains("/build/")
        }
    }
}

// The plain `detekt` task defaults to src/main/kotlin, which does not exist in a KMP layout. Point it at every KMP
// Kotlin source set. Generated code lives under build/ and is excluded above.
pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
    detekt {
        source.setFrom(
            fileTree("src") {
                include("*Main/kotlin/**/*.kt", "*Test/kotlin/**/*.kt")
            },
        )
    }
}
