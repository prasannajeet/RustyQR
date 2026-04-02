import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "com.p2.apps.rustyqr"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "com.p2.apps.rustyqr"
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.targetSdk
                .get()
                .toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(libs.compose.uiTooling)
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:${libs.versions.detekt.get()}")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("${rootProject.projectDir}/config/detekt/detekt.yml"))
    baseline = file("detekt-baseline.xml")
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    exclude { it.file.absolutePath.contains("/build/") }
}

tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
    exclude { it.file.absolutePath.contains("/build/") }
}

ktlint {
    android.set(true)
    verbose.set(true)
    filter {
        exclude { it.file.path.contains("/build/") }
        exclude { it.file.path.contains("/generated/") }
    }
}

val rootDir = rootProject.projectDir.absolutePath

tasks.register("installGitHooks") {
    group = "git hooks"
    description = "Installs git hooks for conventional commits and pre-commit checks"
    doLast {
        val hooksDir = File(rootDir, ".husky")
        val gitHooksDir = File(rootDir, ".git/hooks")
        if (!gitHooksDir.exists()) gitHooksDir.mkdirs()
        listOf("commit-msg", "pre-commit").forEach { hookName ->
            val source = File(hooksDir, hookName)
            val target = File(gitHooksDir, hookName)
            if (source.exists()) {
                source.copyTo(target, overwrite = true)
                target.setExecutable(true)
                logger.lifecycle("Git hook '$hookName' installed successfully!")
            }
        }
    }
}

tasks.register<Exec>("swiftlint") {
    group = "verification"
    description = "Runs SwiftLint on iOS source files"
    workingDir = File(rootDir, "iosApp")
    commandLine("/opt/homebrew/bin/swiftlint", "lint", "--config", "$rootDir/.swiftlint.yml", "--strict")
}

tasks.register("lintAll") {
    group = "verification"
    description = "Runs all linters: ktlint, detekt, and swiftlint"
    dependsOn("ktlintCheck", "detekt", "swiftlint")
}

// Make both preBuild and project sync tasks depend on installGitHooks
tasks.named("preBuild") {
    dependsOn("installGitHooks")
}

// Add hook installation to project sync
tasks.named("clean") {
    dependsOn("installGitHooks")
}
