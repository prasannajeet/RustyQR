plugins {
    `kotlin-dsl`
    alias(libs.plugins.ktlint)
}

ktlint {
    android.set(true)
    verbose.set(true)
    filter {
        // Only this project's own build output; a checkout under some /build/ directory must still be linted.
        val buildDir = layout.buildDirectory.get().asFile
        exclude { it.file.startsWith(buildDir) }
    }
}

dependencies {
    // The Gradle plugin artifacts behind the `detekt` and `ktlint` entries in gradle/libs.versions.toml.
    implementation("dev.detekt:detekt-gradle-plugin:${libs.versions.detekt.get()}")
    // detekt 2's plugin only compiles against the Kotlin compiler-plugin API, but the generated accessors for the
    // convention plugin need the Kotlin Gradle plugin classes (KotlinBasePlugin) on this classpath.
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    implementation("org.jlleitschuh.gradle:ktlint-gradle:${libs.versions.ktlint.gradle.get()}")
}
