plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader (detekt and ktlint come with the build-logic convention plugin)
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
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

tasks.register<Exec>("swiftlint") {
    group = "verification"
    description = "Runs SwiftLint on iOS source files"
    workingDir = File(repoRoot, "iosApp")
    commandLine("/opt/homebrew/bin/swiftlint", "lint", "--config", "$repoRoot/.swiftlint.yml", "--strict")
}

// Every Kotlin check in the repository: ktlint and detekt of each module and of the root scripts, ktlint of
// build-logic (it applies no detekt), plus the font-file check (:composeApp:verifyFontFiles).
// The per-module `detekt` also runs that module's type-resolved tasks.
val lintKotlin =
    tasks.register("lintKotlin") {
        group = "verification"
        description = "Runs ktlint, detekt (type-resolved included) and verifyFontFiles for the whole repository"
        dependsOn(
            ":ktlintCheck",
            ":detekt",
            ":composeApp:ktlintCheck",
            ":composeApp:detekt",
            ":composeApp:verifyFontFiles",
            ":androidApp:ktlintCheck",
            ":androidApp:detekt",
            ":rustyqr-bindings:ktlintCheck",
            ":rustyqr-bindings:detekt",
            gradle.includedBuild("build-logic").task(":ktlintCheck"),
        )
    }

tasks.register("lintAll") {
    group = "verification"
    description = "Runs all linters: lintKotlin (ktlint, detekt) and swiftlint"
    dependsOn(lintKotlin, "swiftlint")
}
