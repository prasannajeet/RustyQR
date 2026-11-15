plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.uniffi)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
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
    iosArm64()
    iosSimulatorArm64()
    jvm()

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

cargo {
    packageDirectory = rootProject.layout.projectDirectory.dir("rustySDK/crates/ffi")
}

uniffi {
    generateFromLibrary {
        packageName = "com.p2.apps.rustyqr.rust"
    }
}

dependencies {
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:${libs.versions.detekt.get()}")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("${rootProject.projectDir}/config/detekt/detekt.yml"))
    // The plain `detekt` task defaults to src/main/kotlin, which does not exist in a KMP layout. Point it at the
    // hand-written KMP source sets only; the UniFFI bindings are generated under build/.
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

ktlint {
    android.set(true)
    verbose.set(true)
    filter {
        exclude { it.file.path.contains("/build/") }
        exclude { it.file.path.contains("/generated/") }
    }
}

// Type resolution: detekt 1.x only wires a classpath for the JVM/Android targets, so detektJvmTest is the type-resolved
// task. commonTest is added to it so the shared tests are type-checked against the JVM classpath.
// The plugin registers its per-compilation tasks and sets their sources late, so add commonTest afterwards.
afterEvaluate {
    tasks.named<io.gitlab.arturbosch.detekt.Detekt>("detektJvmTest") {
        source(file("src/commonTest/kotlin"))
    }
}

// `detekt` is the entry point for lintAll and the pre-commit hook. It also runs the type-resolved task.
tasks.named("detekt") {
    dependsOn("detektJvmTest")
}
