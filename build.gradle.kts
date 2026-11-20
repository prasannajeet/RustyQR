plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader (detekt and ktlint come with the build-logic convention plugin)
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.uniffi) apply false
    id("rustyqr.kotlin-lint") apply false
}