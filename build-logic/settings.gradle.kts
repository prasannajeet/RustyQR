rootProject.name = "build-logic"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        // One catalog for the whole repository: plugin versions are never repeated here.
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
