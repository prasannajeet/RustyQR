plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    id("rustyqr.kotlin-lint")
}

val appVersionName = gradle.extra["appVersionName"] as String
val appBuildNumber = gradle.extra["appBuildNumber"] as Int

// The ABIs that ship librusty_qr_ffi.so. Keep gradle.properties `androidAbis` in step with this list.
val shippedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

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
        ndk {
            // Only the ABIs that ship librusty_qr_ffi.so. Other ABIs from CameraX or JNA would install and then crash.
            abiFilters += shippedAbis
        }
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
        getByName("release") {
            isMinifyEnabled = false
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
