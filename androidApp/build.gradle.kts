plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    id("rustyqr.kotlin-lint")
}

// Compose HotSwan hot reload, for development only. Builds that ship pass -PreleaseBuild=true (verifyReleaseRustProfile
// enforces it), so they never carry the HotSwan compiler plugin or its runtime. :composeApp applies it the same way.
if (providers.gradleProperty("releaseBuild").orNull != "true") {
    pluginManager.apply(libs.plugins.hotswan.get().pluginId)
}

val appVersionName = gradle.extra["appVersionName"] as String
val appBuildNumber = gradle.extra["appBuildNumber"] as Int

// The ABIs that ship librusty_qr_ffi.so. Release builds package exactly these.
val shippedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

// Debug builds package the ABIs the Rust library was built for: gradle.properties `androidAbis`, which the UniFFI
// plugin also reads, so `-PandroidAbis=arm64-v8a` shrinks the Rust build and the APK together. Other ABIs from CameraX
// or JNA would install and then crash on the first Rust call, so every listed ABI must be one that ships.
val debugAbis =
    providers
        .gradleProperty("androidAbis")
        .map { list -> list.split(',').map { it.trim() }.filter { it.isNotEmpty() } }
        .getOrElse(shippedAbis)
require(debugAbis.isNotEmpty() && shippedAbis.containsAll(debugAbis)) {
    "androidAbis=$debugAbis must be a non-empty subset of the shipped ABIs $shippedAbis"
}

// One JVM target for the Java/Kotlin compile (built-in Kotlin follows compileOptions).
val jvmTargetVersion = JavaVersion.VERSION_11

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
        versionCode = appBuildNumber
        versionName = appVersionName
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("debug") {
            ndk { abiFilters += debugAbis }
        }
        getByName("release") {
            isMinifyEnabled = false
            ndk { abiFilters += shippedAbis }
        }
    }
    // Built-in Kotlin takes its jvmTarget from these.
    compileOptions {
        sourceCompatibility = jvmTargetVersion
        targetCompatibility = jvmTargetVersion
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

detekt {
    source.setFrom(files("src/main/kotlin"))
}

// `detekt` is the entry point for lintAll and the pre-commit hook. It also runs the type-resolved debug task, which
// detekt 2 registers itself for the built-in Kotlin variants.
tasks.named("detekt") {
    dependsOn("detektDebug")
}

androidComponents {
    // Guard only packaging: the native-library merge is shared by APK and AAB, while test, check and lint tasks never
    // reach it. Covers every non-debuggable variant, including future flavours and release-like build types.
    onVariants { variant ->
        if (!variant.debuggable) {
            val mergeTaskName = "merge${variant.name.replaceFirstChar { it.uppercase() }}NativeLibs"
            tasks
                .named { it == mergeTaskName }
                .configureEach { dependsOn(":composeApp:verifyReleaseRustProfile") }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(":installGitHooks")
}
