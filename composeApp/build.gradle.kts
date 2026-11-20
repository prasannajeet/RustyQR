import ch.ubique.uniffi.plugin.model.RustHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    id("rustyqr.kotlin-lint")
}

kotlin {
    compilerOptions {
        // Explicit backing fields (KEEP-278) — lets ViewModels expose
        // `val state: StateFlow<T>` with a private `field = MutableStateFlow(...)`,
        // eliminating the `_state` / `state` duplication. Still experimental in 2.3.x.
        freeCompilerArgs.add("-Xexplicit-backing-fields")
    }

    android {
        namespace = "com.p2.apps.rustyqr.composeapp"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
        // Compose Multiplatform resources and res/xml/file_paths.xml are Android resources; off by default here.
        androidResources {
            enable = true
        }
        // Runs commonTest on the host JVM.
        withHostTest {}
    }

    if (RustHost.Platform.MacOS.isCurrent) {
        iosArm64().apply {
            binaries.framework {
                baseName = "ComposeApp"
                isStatic = true
            }
        }

        iosSimulatorArm64().apply {
            binaries.framework {
                baseName = "ComposeApp"
                isStatic = true
            }
        }
    }

    sourceSets {
        androidMain.dependencies {
            // api: ComponentActivity is in the public API through BridgeHostActivity's supertype.
            api(libs.androidx.activity.compose)
            implementation(libs.androidx.camera.core)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.androidx.navigationevent.compose)
            implementation(libs.compose.components.resources)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.arrow.core)
            implementation(project(":rustyqr-bindings"))
            implementation(libs.compose.material.icons.extended)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.testJunit)
        }
    }
}

// Type resolution: detekt 1.23.x routes the Android KMP library compilation through its generic path, which gives
// `detektAndroidMain` a compile classpath (androidJvm) over the androidMain sources. commonMain is added to it so
// shared code is type-checked against the Android classpath, and the SDK boot classpath makes `android.*` resolve.
// detekt 1.x cannot resolve Kotlin/Native types, so iosMain and commonTest only get the untyped source-set scan
// that the convention plugin configures.
// The plugin registers its per-compilation tasks late, so adjust it afterwards (this fails loudly if the task is gone).
afterEvaluate {
    tasks.named<io.gitlab.arturbosch.detekt.Detekt>("detektAndroidMain") {
        source(file("src/commonMain/kotlin"))
        classpath.from(androidComponents.sdkComponents.bootClasspath)
    }
}

// `detekt` is the entry point for lintAll and the pre-commit hook. It also runs the type-resolved task, which
// compiles the Android target, which needs the UniFFI bindings from :rustyqr-bindings.
tasks.named("detekt") {
    dependsOn("detektAndroidMain")
}

// Anything that ships (non-debuggable Android variants, wired from :androidApp, and Kotlin/Native release frameworks)
// must carry the optimised Rust library, which only -PreleaseBuild=true builds. This task has no outputs, so it always
// runs when it is in the graph and can never be skipped or restored from the build cache.
val verifyReleaseRustProfile =
    tasks.register("verifyReleaseRustProfile") {
        group = "verification"
        description = "Fails unless -PreleaseBuild=true, so shipped artifacts never embed the debug Rust library."
        val flag = providers.gradleProperty("releaseBuild")
        doLast {
            if (flag.orNull != "true") {
                throw GradleException(
                    "Release builds need -PreleaseBuild=true so shipped Android and iOS artifacts " +
                        "embed the optimised Rust library.",
                )
            }
        }
    }

// Release frameworks reach Xcode through paths that skip the Xcode script (the IDE-support override,
// direct Gradle runs). dependsOn on a no-output task, not doFirst: link tasks are cacheable and could be FROM-CACHE.
tasks.withType<KotlinNativeLink>().configureEach {
    if (binary.buildType == NativeBuildType.RELEASE) {
        dependsOn(verifyReleaseRustProfile)
    }
}
