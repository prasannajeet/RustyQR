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
    implementation("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:${libs.versions.detekt.get()}")
    implementation("org.jlleitschuh.gradle:ktlint-gradle:${libs.versions.ktlint.gradle.get()}")
}
