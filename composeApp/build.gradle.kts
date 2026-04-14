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

    val rootDirPath = rootProject.projectDir.absolutePath

    iosArm64().apply {
        binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
        compilations["main"].cinterops {
            create("RustyQrFFI") {
                defFile = file("src/nativeInterop/cinterop/RustyQrFFI.def")
                includeDirs(file("$rootDirPath/iosApp/generated/headers"))
                extraOpts("-libraryPath", "$rootDirPath/iosApp/Frameworks/RustyQR.xcframework/ios-arm64")
            }
        }
    }

    iosSimulatorArm64().apply {
        binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
        compilations["main"].cinterops {
            create("RustyQrFFI") {
                defFile = file("src/nativeInterop/cinterop/RustyQrFFI.def")
                includeDirs(file("$rootDirPath/iosApp/generated/headers"))
                extraOpts("-libraryPath", "$rootDirPath/iosApp/Frameworks/RustyQR.xcframework/ios-arm64-simulator")
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
            implementation(libs.compose.ui.backhandler)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.arrow.core)
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
    // artifact { type = "aar" } requires the standard DependencyHandlerScope, which is available here.
    // Do NOT move this into kotlin { sourceSets { androidMain.dependencies { } } } — the KMP
    // sourceSets DSL uses KotlinDependencyHandler which lacks the artifact{} closure overload.
    // Moving it there silently drops the @aar specifier and causes runtime class-not-found errors.
    "androidMainImplementation"(libs.jna) { artifact { type = "aar" } }
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

tasks.register<Exec>("buildRustAndroid") {
    group = "rust"
    description = "Cross-compiles Rust FFI for Android and generates Kotlin bindings (runs make android)"
    workingDir = File(rootDir, "rustySDK")
    commandLine("make", "android")
}

tasks.register<Exec>("buildRustIos") {
    group = "rust"
    description = "Cross-compiles Rust FFI for iOS, creates the XCFramework, and regenerates iosApp.xcodeproj"
    workingDir = File(rootDir, "rustySDK")
    commandLine("make", "ios")
    // Make owns Rust + bindings + XCFramework. Gradle owns the Xcode project.
    // Chain them so a single buildRustIos invocation still produces a fully buildable iOS tree.
    finalizedBy("generateXcodeProject")
}

tasks.register<Delete>("cleanXcodeProject") {
    group = "xcode"
    description = "Deletes xcodegen-generated artifacts (iosApp.xcodeproj + Info.plist)"
    delete(
        File(rootDir, "iosApp/iosApp.xcodeproj"),
        File(rootDir, "iosApp/iosApp/Info.plist"),
    )
}

tasks.register<Exec>("cleanBuildIos") {
    group = "rust"
    description = "Cleans all Rust/iOS artifacts (including the Xcode project) and rebuilds from scratch"
    workingDir = File(rootDir, "rustySDK")
    commandLine("make", "clean")
    dependsOn("cleanXcodeProject")
    finalizedBy("buildRustIos")
    doLast {
        println("Make sure to run a gradle sync after")
    }
}

tasks.register<Exec>("generateXcodeProject") {
    group = "xcode"
    description = "Regenerates iosApp.xcodeproj from project.yml via XcodeGen"
    workingDir = File(rootDir, "iosApp")
    commandLine("xcodegen", "generate")
    mustRunAfter("buildRustIos", "cleanBuildIos")
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
