rootProject.name = "RustyQR"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Bootstrap iosApp.xcodeproj on fresh clones — runs before any build.gradle.kts is parsed,
// which beats the KMP plugin's configuration-time read of project.pbxproj.
// Why: on a fresh clone, project.pbxproj doesn't exist (it's a generated artifact), so Gradle
// sync fails before the generateXcodeProject task has a chance to run. Uses providers.exec
// (config-cache compatible) to short-circuit that chicken-and-egg problem.
run {
    val iosApp = rootDir.resolve("iosApp")
    val pbxproj = iosApp.resolve("iosApp.xcodeproj/project.pbxproj")
    val projectYml = iosApp.resolve("project.yml")
    if (projectYml.exists() && !pbxproj.exists()) {
        println("Bootstrapping iosApp.xcodeproj via xcodegen (fresh clone)...")
        val execResult =
            providers
                .exec {
                    workingDir = iosApp
                    commandLine("xcodegen", "generate")
                    isIgnoreExitValue = true
                }
        val exitCode = execResult.result.get().exitValue
        if (exitCode != 0) {
            throw GradleException(
                "xcodegen generate failed with exit code $exitCode. " +
                    "Ensure xcodegen is installed: brew install xcodegen\n" +
                    execResult.standardError.asText.get(),
            )
        }
    }
}

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":composeApp")