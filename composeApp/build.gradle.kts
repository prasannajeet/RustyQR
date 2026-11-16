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

/**
 * Fails when a file under composeResources/font is not a real font. Saved GitHub HTML pages named *.ttf once shipped
 * and the app silently fell back to the system font. Compose resources cannot be read from host tests, so this
 * checks the files' magic numbers directly.
 */
abstract class VerifyFontFiles : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fonts: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val magics = listOf("00010000", "4f54544f", "74727565", "774f4646", "774f4632") // sfnt, OTTO, true, wOFF, wOF2
        val bad =
            fonts.files.filter { file ->
                val header = file.inputStream().use { it.readNBytes(MAGIC_SIZE_BYTES) }
                header.joinToString("") { "%02x".format(it) } !in magics
            }
        if (bad.isNotEmpty()) {
            throw GradleException(
                "Not font files (bad magic number): ${bad.joinToString { it.name }}. " +
                    "Replace them with real TrueType/OpenType/WOFF fonts.",
            )
        }
    }

    private companion object {
        const val MAGIC_SIZE_BYTES = 4
    }
}

tasks.register<VerifyFontFiles>("verifyFontFiles") {
    group = "verification"
    description = "Fails if any file under composeResources/font is not a real font."
    fonts.from(fileTree("src/commonMain/composeResources/font"))
}

kotlin {
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
        listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
            target.binaries.framework {
                baseName = "ComposeApp"
                isStatic = true
                // Without an explicit id the Kotlin/Native link warns that it cannot infer one from the packages.
                binaryOption("bundleId", "com.p2.apps.rustyqr.composeapp")
            }
        }
    }

    sourceSets {
        androidMain.dependencies {
            // api: ComponentActivity is in the public API through BridgeHostActivity's supertype.
            api(libs.androidx.activity.compose)
            implementation(libs.androidx.startup.runtime)
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
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.testJunit)
        }
    }
}

// Type resolution: detekt 2 registers a type-resolved task per Android compilation (`detektMainAndroid` covers
// androidMain and commonMain, `detektHostTestAndroid` the host tests) next to the untyped per-source-set tasks.
// detekt cannot resolve Kotlin/Native types, so iosMain only gets the untyped source-set scan.
// `detekt` is the entry point for lintAll and the pre-commit hook. It also runs the type-resolved tasks, which
// compile the Android target, which needs the UniFFI bindings from :rustyqr-bindings.
tasks.named("detekt") {
    dependsOn("detektMainAndroid", "detektHostTestAndroid")
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
