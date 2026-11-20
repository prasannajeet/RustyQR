import dev.detekt.gradle.Detekt

// Lint setup shared by every Kotlin module: detekt (with the formatting rules) and ktlint, both at the repository's
// own rules. Modules add only what is specific to them: which sources detekt scans and any type-resolved task.
plugins {
    id("dev.detekt")
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
    detektPlugins("dev.detekt:detekt-rules-ktlint-wrapper:$detektVersion")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
}

// Generated code and reports live under the module's own build/ directory: neither detekt nor ktlint looks at them.
val buildOutput = buildOutputSpec(project.projectDir)

tasks.withType<Detekt>().configureEach {
    exclude(buildOutput)
}

ktlint {
    android.set(true)
    verbose.set(true)
    filter {
        exclude(buildOutput)
    }
}

// One entry point per module: every module that applies this plugin gets a `lintKotlin` that runs its ktlint and
// detekt (which also runs the module's type-resolved detekt tasks). `./gradlew lintKotlin` selects the task of that
// name in every project, so a new module is linted by lintKotlin, lintAll, the pre-commit hook and CI without being
// listed anywhere.
tasks.register("lintKotlin") {
    group = "verification"
    description = "Runs ktlint and detekt (type-resolved included) for this module"
    dependsOn("ktlintCheck", "detekt")
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
