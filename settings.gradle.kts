import java.util.Properties

rootProject.name = "RustyQR"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Read centralized version from version.properties — single source of truth for Android + iOS.
val versionPropsFile = settingsDir.resolve("version.properties")
if (!versionPropsFile.exists()) {
    throw GradleException("version.properties not found at ${versionPropsFile.absolutePath}")
}
val versionProps = Properties().apply { versionPropsFile.inputStream().use { load(it) } }
val rawVersionName = versionProps.getProperty("version_name") ?: ""
if (rawVersionName.isBlank()) {
    throw GradleException("version.properties: version_name must not be blank, got '$rawVersionName'")
}
val rawBuildNumber = versionProps.getProperty("build_number") ?: ""
val appBuildNumber: Int = rawBuildNumber.trim().toIntOrNull()
    ?: throw GradleException("version.properties: build_number must be an integer, got '$rawBuildNumber'")
if (appBuildNumber <= 0) {
    throw GradleException("version.properties: build_number must be positive, got $appBuildNumber")
}
val appVersionName: String = rawVersionName.trim()

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
                    environment("VERSION_NAME", appVersionName)
                    environment("BUILD_NUMBER", appBuildNumber.toString())
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