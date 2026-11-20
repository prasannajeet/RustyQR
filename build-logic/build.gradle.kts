plugins {
    `kotlin-dsl`
    alias(libs.plugins.ktlint)
}

ktlint {
    android.set(true)
    verbose.set(true)
    filter {
        exclude { it.file.path.contains("/build/") }
    }
}

dependencies {
    // The Gradle plugin artifacts behind the `detekt` and `ktlint` entries in gradle/libs.versions.toml.
    implementation("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:${libs.versions.detekt.get()}")
    implementation("org.jlleitschuh.gradle:ktlint-gradle:${libs.versions.ktlint.gradle.get()}")
}
