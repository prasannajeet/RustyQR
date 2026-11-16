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
    config.setFrom(files("${rootProject.projectDir}/config/detekt/detekt.yml"))
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
        exclude { it.file.path.contains("/build/") }
    }
}
