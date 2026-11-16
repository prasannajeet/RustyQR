import ch.ubique.uniffi.plugin.model.RustHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink
import java.util.Properties

// Read centralized version from version.properties — single source of truth for Android + iOS.
// Duplicated from settings.gradle.kts; buildSrc extraction is out of scope.
val versionPropsFile = rootProject.projectDir.resolve("version.properties")
if (!versionPropsFile.exists()) {
    throw GradleException("version.properties not found at ${versionPropsFile.absolutePath}")
}
val versionProps = Properties().apply { versionPropsFile.inputStream().use { load(it) } }
val rawVersionName = versionProps.getProperty("version_name") ?: ""
if (rawVersionName.isBlank()) {
    throw GradleException("version.properties: version_name must not be blank, got '$rawVersionName'")
}
val rawBuildNumber = versionProps.getProperty("build_number") ?: ""
val appBuildNumber: Int =
    rawBuildNumber.trim().toIntOrNull()
        ?: throw GradleException("version.properties: build_number must be an integer, got '$rawBuildNumber'")
if (appBuildNumber <= 0) {
    throw GradleException("version.properties: build_number must be positive, got $appBuildNumber")
}
val appVersionName: String = rawVersionName.trim()

// The ABIs that ship librusty_qr_ffi.so. Keep gradle.properties `androidAbis` in step with this list.
val shippedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

kotlin {
    compilerOptions {
        // Explicit backing fields (KEEP-278) — lets ViewModels expose
        // `val state: StateFlow<T>` with a private `field = MutableStateFlow(...)`,
        // eliminating the `_state` / `state` duplication. Still experimental in 2.3.x.
        freeCompilerArgs.add("-Xexplicit-backing-fields")
    }

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
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
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
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
            implementation(libs.navigationevent.compose)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.arrow.core)
            implementation(project(":rustyqr-bindings"))
            implementation(libs.compose.material.icons.extended)
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
        versionCode = appBuildNumber
        versionName = appVersionName
        ndk {
            // Only the ABIs that ship librusty_qr_ffi.so. Other ABIs from CameraX or JNA would install and then crash.
            abiFilters += shippedAbis
        }
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
    // The plain `detekt` task defaults to src/main/kotlin, which does not exist in a KMP layout. Point it at every
    // KMP Kotlin source set (commonMain, androidMain, iosMain, commonTest, ...).
    source.setFrom(
        fileTree("src") {
            include("*Main/kotlin/**/*.kt", "*Test/kotlin/**/*.kt")
        },
    )
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    // Match on the project-relative path so a checkout that lives under a /build/ directory is not skipped.
    val moduleRoot = project.projectDir
    exclude {
        val path = it.file.relativeTo(moduleRoot).invariantSeparatorsPath
        path.startsWith("build/") || path.contains("/build/")
    }
}

// Type resolution: detekt-gradle-plugin 1.23.x only wires a compile classpath for the JVM/Android targets, and
// detekt 1.x cannot resolve Kotlin/Native types. So detektAndroidDebug is the type-resolved task; commonMain is
// added to it so shared code is type-checked against the Android classpath. iosMain only gets the untyped
// source-set scan configured above, as does commonTest.
// The plugin registers its per-compilation tasks and sets their sources late, so add commonMain afterwards.
afterEvaluate {
    tasks.named<io.gitlab.arturbosch.detekt.Detekt>("detektAndroidDebug") {
        source(file("src/commonMain/kotlin"))
    }
}

// `detekt` is the entry point for lintAll and the pre-commit hook. It also runs the type-resolved task, which
// compiles the Android target, which needs the UniFFI bindings from :rustyqr-bindings.
tasks.named("detekt") {
    dependsOn("detektAndroidDebug")
}

ktlint {
    android.set(true)
    verbose.set(true)
    filter {
        exclude { it.file.path.contains("/build/") }
    }
}

val rootDir = rootProject.projectDir.absolutePath

tasks.register<Exec>("installGitHooks") {
    group = "git hooks"
    description = "Installs git hooks for conventional commits and pre-commit checks"
    commandLine(
        "sh",
        "-c",
        """
        mkdir -p "$rootDir/.git/hooks"
        for hook in commit-msg pre-commit; do
            src="$rootDir/.husky/${'$'}hook"
            dst="$rootDir/.git/hooks/${'$'}hook"
            if [ -f "${'$'}src" ]; then
                cp "${'$'}src" "${'$'}dst"
                chmod +x "${'$'}dst"
                echo "Git hook '${'$'}hook' installed successfully!"
            fi
        done
        """.trimIndent(),
    )
}

tasks.register<Delete>("cleanXcodeProject") {
    group = "xcode"
    description = "Deletes xcodegen-generated artifacts (iosApp.xcodeproj + Info.plist)"
    delete(
        File(rootDir, "iosApp/iosApp.xcodeproj"),
        File(rootDir, "iosApp/iosApp/Info.plist"),
    )
}

tasks.register<Exec>("generateXcodeProject") {
    group = "xcode"
    description = "Regenerates iosApp.xcodeproj from project.yml via XcodeGen"
    outputs.upToDateWhen { false }
    workingDir = File(rootDir, "iosApp")
    environment("VERSION_NAME", appVersionName)
    environment("BUILD_NUMBER", appBuildNumber.toString())
    commandLine("xcodegen", "generate")
    doLast {
        logger.lifecycle("\n⚠️  If the iOS run configuration is missing in Android Studio, run: File > Sync Project with Gradle Files")
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
    dependsOn("ktlintCheck", "detekt", "swiftlint", ":rustyqr-bindings:ktlintCheck", ":rustyqr-bindings:detekt")
}

// Anything that ships (non-debuggable Android variants, Kotlin/Native release frameworks) must carry the optimised
// Rust library, which only -PreleaseBuild=true builds. This task has no outputs, so it always runs when it is in the
// graph and can never be skipped or restored from the build cache.
val verifyReleaseRustProfile =
    tasks.register("verifyReleaseRustProfile") {
        group = "verification"
        description = "Fails unless -PreleaseBuild=true, so shipped artifacts never embed the debug Rust library."
        val flag = providers.gradleProperty("releaseBuild")
        val abis = shippedAbis.joinToString(", ")
        doLast {
            if (flag.orNull != "true") {
                throw GradleException(
                    "Release builds need -PreleaseBuild=true so the Rust library is built optimised for $abis.",
                )
            }
        }
    }

// Guard only packaging: the native-library merge is shared by APK and AAB, while test, check and lint tasks never
// reach it. Covers every non-debuggable variant, including future flavours and release-like build types.
androidComponents {
    onVariants { variant ->
        if (!variant.debuggable) {
            val mergeTaskName = "merge${variant.name.replaceFirstChar { it.uppercase() }}NativeLibs"
            tasks
                .matching { it.name == mergeTaskName }
                .configureEach { dependsOn(verifyReleaseRustProfile) }
        }
    }
}

// Release frameworks reach Xcode through paths that skip the Xcode script (OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED,
// direct Gradle runs). dependsOn on a no-output task, not doFirst: link tasks are cacheable and could be FROM-CACHE.
tasks.withType<KotlinNativeLink>().configureEach {
    if (binary.buildType == NativeBuildType.RELEASE) {
        dependsOn(verifyReleaseRustProfile)
    }
}

// Make both preBuild and project sync tasks depend on installGitHooks
tasks.named("preBuild") {
    dependsOn("installGitHooks")
}

// Add hook installation to project sync
tasks.named("clean") {
    dependsOn("installGitHooks")
}
