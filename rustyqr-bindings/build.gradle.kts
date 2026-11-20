import ch.ubique.uniffi.plugin.model.RustHost
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet

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
    logger.lifecycle(
        "releaseBuild=true: the JVM target and the type-resolved detektMainJvm and detektTestJvm tasks are skipped.",
    )
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

    // These tests call the native library through JNA / cinterop, so they run on the host JVM and on the iOS
    // simulator. They are not in commonTest: that would also be an Android host test, which cannot load the host
    // library (the Android JNA artifact has no desktop dispatcher), and AGP warns when commonTest exists without
    // Android host tests. The Android packaging is checked by building :androidApp.
    // Wired next to the targets that create the source sets, through accessors that fail if a source set is missing.
    val ffiTestSources = layout.projectDirectory.dir("src/ffiTest/kotlin")
    val addFfiTests: KotlinSourceSet.() -> Unit = {
        kotlin.srcDir(ffiTestSources)
        dependencies { implementation(libs.kotlin.test) }
    }
    if (RustHost.Platform.MacOS.isCurrent) {
        iosArm64()
        iosSimulatorArm64()
        sourceSets.iosTest { addFfiTests() }
    }
    if (!isReleaseBuild) {
        // Exists only so the host can run the bindings tests.
        jvm()
        sourceSets.jvmTest { addFfiTests() }
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

// Type resolution: detekt only wires a classpath for the JVM and Android targets, so `detektTestJvm` is the
// type-resolved task for the shared tests. src/ffiTest is part of jvmTest, so it is checked against the JVM classpath.
// `detekt` is the entry point for lintAll and the pre-commit hook. It also runs the type-resolved tasks.
// `detektMainJvm` has no hand-written sources (main is the UniFFI bindings under build/, which detekt excludes), so it
// runs as NO-SOURCE today. It stays wired so hand-written commonMain or jvmMain sources would be type-checked.
tasks.named("detekt") {
    if (!isReleaseBuild) {
        dependsOn("detektMainJvm", "detektTestJvm")
    }
}
