import ch.ubique.uniffi.plugin.model.RustHost

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.uniffi)
    id("rustyqr.kotlin-lint")
}

// The Rust profile follows -PreleaseBuild=true, not the AGP build type or the Xcode configuration. Release builds
// cover the shipped targets only: desktop JVM libraries are never shipped and need cross toolchains.
val isReleaseBuild = providers.gradleProperty("releaseBuild").orNull == "true"

if (isReleaseBuild) {
    logger.lifecycle("releaseBuild=true: the JVM target and the type-resolved detektJvmTest are skipped.")
}

kotlin {
    android {
        namespace = "com.p2.apps.rustyqr.bindings"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
    }
    if (RustHost.Platform.MacOS.isCurrent) {
        iosArm64()
        iosSimulatorArm64()
    }
    if (!isReleaseBuild) {
        // Exists only so the host can run the bindings tests.
        jvm()
    }

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

cargo {
    packageDirectory = rootProject.layout.projectDirectory.dir("rustySDK/crates/ffi")
    // Optional NDK pin (CI sets ORG_GRADLE_PROJECT_ndkVersion). The plugin uses <sdk>/ndk/<ndkVersion> only if that
    // folder exists, otherwise it silently falls back to the newest installed NDK; CI guards that. Unset or blank
    // means no pin (a blank value would resolve to <sdk>/ndk itself).
    providers
        .gradleProperty("ndkVersion")
        .orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { ndkVersion = it }
}

uniffi {
    generateFromLibrary {
        packageName = "com.p2.apps.rustyqr.rust"
    }
}

ktlint {
    filter {
        exclude { it.file.path.contains("/generated/") }
    }
}

// Type resolution: detekt 1.x only wires a classpath for the JVM/Android targets, so detektJvmTest is the type-resolved
// task. commonTest is added to it so the shared tests are type-checked against the JVM classpath.
// The plugin registers its per-compilation tasks and sets their sources late, so add commonTest afterwards.
afterEvaluate {
    if (!isReleaseBuild) {
        tasks.named<io.gitlab.arturbosch.detekt.Detekt>("detektJvmTest") {
            source(file("src/commonTest/kotlin"))
        }
    }
}

// `detekt` is the entry point for lintAll and the pre-commit hook. It also runs the type-resolved task.
tasks.named("detekt") {
    if (!isReleaseBuild) {
        dependsOn("detektJvmTest")
    }
}
