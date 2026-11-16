import io.gitlab.arturbosch.detekt.Detekt

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

val appVersionName = gradle.extra["appVersionName"] as String
val appBuildNumber = gradle.extra["appBuildNumber"] as Int

// The ABIs that ship librusty_qr_ffi.so. Keep gradle.properties `androidAbis` in step with this list.
val shippedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

// One JVM target for the Java/Kotlin compile (built-in Kotlin follows compileOptions) and for detekt.
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
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:${libs.versions.detekt.get()}")
}

val detektConfigFile = files("${rootProject.projectDir}/config/detekt/detekt.yml")

detekt {
    buildUponDefaultConfig = true
    config.setFrom(detektConfigFile)
    source.setFrom(files("src/main/kotlin"))
}

tasks.withType<Detekt>().configureEach {
    // Match on the project-relative path so a checkout that lives under a /build/ directory is not skipped.
    val moduleRoot = project.projectDir
    exclude {
        val path = it.file.relativeTo(moduleRoot).invariantSeparatorsPath
        path.startsWith("build/") || path.contains("/build/")
    }
}

androidComponents {
    // detekt 1.23.x registers its type-resolved Android tasks only next to the kotlin-android plugin, which built-in
    // Kotlin replaces. This registers the one task by hand. Delete it once a stable detekt 2.x with built-in Kotlin
    // support is adopted.
    onVariants(selector().withBuildType("debug")) { variant ->
        val detektTaskName = "detekt${variant.name.replaceFirstChar { it.uppercase() }}"
        val typeResolvedDetekt =
            tasks.register<Detekt>(detektTaskName) {
                group = "verification"
                description = "Runs detekt with type resolution on src/main/kotlin (${variant.name} compile classpath)."
                setSource(files("src/main/kotlin"))
                include("**/*.kt", "**/*.kts")
                classpath.setFrom(variant.compileClasspath, sdkComponents.bootClasspath)
                jvmTarget = jvmTargetVersion.majorVersion
                buildUponDefaultConfig = true
                config.setFrom(detektConfigFile)
                // Explicit paths: unset locations fall back to detekt.<ext>, which the plain `detekt` task also writes.
                reports {
                    listOf(xml, html, txt, sarif, md).forEach {
                        it.outputLocation.set(
                            layout.buildDirectory.file("reports/detekt/${variant.name}.${it.type.extension}"),
                        )
                    }
                }
            }
        // `detekt` is the entry point for lintAll and the pre-commit hook; it also runs the type-resolved task.
        tasks.named("detekt") { dependsOn(typeResolvedDetekt) }
    }
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

ktlint {
    android.set(true)
    verbose.set(true)
    filter {
        exclude { it.file.path.contains("/build/") }
    }
}

tasks.named("preBuild") {
    dependsOn(":composeApp:installGitHooks")
}
