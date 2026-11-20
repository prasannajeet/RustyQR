plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader (detekt and ktlint come with the build-logic convention plugin)
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.uniffi) apply false
    id("rustyqr.kotlin-lint")
}

// Repository-wide tasks live here, not in :composeApp: a library module must not depend on its consumers
// (:androidApp), and `./gradlew lintAll` should run once for the whole repository.
val repoRoot = layout.projectDirectory.asFile.absolutePath

// The root has no src/, which is where detekt 2 looks by default: point it at the build scripts instead.
detekt {
    source.setFrom(
        files(
            "build.gradle.kts",
            "settings.gradle.kts",
            "build-logic/build.gradle.kts",
            "build-logic/settings.gradle.kts",
            "build-logic/src/main/kotlin",
        ),
    )
}

// Points git at the tracked .husky/ scripts (relative core.hooksPath), so every worktree runs its own branch's hooks.
tasks.register<Exec>("installGitHooks") {
    group = "git hooks"
    description = "Points git's core.hooksPath at .husky (conventional commits and pre-commit checks)"
    commandLine("sh", "$repoRoot/scripts/install-git-hooks.sh", repoRoot)
}

tasks.register<Delete>("cleanXcodeProject") {
    group = "xcode"
    description = "Deletes xcodegen-generated artifacts (iosApp.xcodeproj + Info.plist)"
    delete(
        File(repoRoot, "iosApp/iosApp.xcodeproj"),
        File(repoRoot, "iosApp/iosApp/Info.plist"),
    )
}

tasks.register<Exec>("generateXcodeProject") {
    group = "xcode"
    description = "Regenerates iosApp.xcodeproj from project.yml via XcodeGen"
    outputs.upToDateWhen { false }
    workingDir = File(repoRoot, "iosApp")
    environment("VERSION_NAME", gradle.extra["appVersionName"] as String)
    environment("BUILD_NUMBER", (gradle.extra["appBuildNumber"] as Int).toString())
    commandLine("xcodegen", "generate")
    doLast {
        logger.lifecycle(
            "\n⚠️  If the iOS run configuration is missing in Android Studio, " +
                "run: File > Sync Project with Gradle Files",
        )
    }
}

// Homebrew installs to /opt/homebrew/bin on Apple silicon and /usr/local/bin on Intel Macs; fall back to PATH.
// Resolved when the task runs, so nothing is read at configuration time and the configuration cache stays valid.
val findSwiftlint =
    """
    for dir in /opt/homebrew/bin /usr/local/bin; do
        if [ -x "${'$'}dir/swiftlint" ]; then exec "${'$'}dir/swiftlint" "${'$'}@"; fi
    done
    if command -v swiftlint >/dev/null 2>&1; then exec swiftlint "${'$'}@"; fi
    echo "swiftlint not found in /opt/homebrew/bin, /usr/local/bin or PATH (brew install swiftlint)" >&2
    exit 127
    """.trimIndent()

tasks.register<Exec>("swiftlint") {
    group = "verification"
    description = "Runs SwiftLint on iOS source files"
    workingDir = File(repoRoot, "iosApp")
    commandLine(
        "sh",
        "-c",
        findSwiftlint,
        "swiftlint",
        "lint",
        "--config",
        "$repoRoot/.swiftlint.yml",
        "--strict",
    )
}

// Every module that applies rustyqr.kotlin-lint registers its own `lintKotlin` (ktlint + detekt, type-resolved
// included), and `./gradlew lintKotlin` runs the one of every project. This root task adds what no module owns: the
// font-file check (:composeApp:verifyFontFiles) and ktlint of build-logic (an included build that applies no detekt).
// Its own ktlint and detekt cover the root scripts and build-logic sources (see the detekt block above).
// It also pulls in every module's task, so `./gradlew :lintKotlin` and `lintAll` cover the whole repository too.
tasks.named("lintKotlin") {
    description = "Runs ktlint, detekt (type-resolved included) and verifyFontFiles for the whole repository"
    dependsOn(
        ":composeApp:verifyFontFiles",
        gradle.includedBuild("build-logic").task(":ktlintCheck"),
        // Lazy by name: only the lintKotlin tasks are realised, not every task of every subproject.
        subprojects.map { sub -> sub.tasks.named { name -> name == "lintKotlin" } },
    )
}

tasks.register("lintAll") {
    group = "verification"
    description = "Runs all linters: lintKotlin (ktlint, detekt) and swiftlint"
    dependsOn("lintKotlin", "swiftlint")
}
