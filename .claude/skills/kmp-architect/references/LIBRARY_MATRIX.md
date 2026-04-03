# KMP Library Replacement Matrix and Version Management

## Android → KMP Library Replacement

| Android Library    | KMP Replacement             | Artifact                                                                   | Migration Notes                                                                       |
|--------------------|-----------------------------|----------------------------------------------------------------------------|---------------------------------------------------------------------------------------|
| Retrofit           | Ktor HttpClient             | `io.ktor:ktor-client-core`                                                 | Ktorfit adds Retrofit-like annotations if needed                                      |
| OkHttp             | Ktor engines                | `io.ktor:ktor-client-okhttp` (Android), `io.ktor:ktor-client-darwin` (iOS) | Use OkHttp engine on Android during transition                                        |
| Hilt / Dagger      | Koin 4.x                    | `io.insert-koin:koin-core`                                                 | Pure Kotlin, no code generation. `kotlin-inject` is the compile-time-safe alternative |
| Room               | SQLDelight or Room KMP      | `app.cash.sqldelight:runtime` or `androidx.room:room-runtime`              | Room KMP stable since 2.7.0. SQLDelight has longer KMP track record                   |
| Gson / Moshi       | kotlinx.serialization       | `org.jetbrains.kotlinx:kotlinx-serialization-json`                         | Compile-time safe with `@Serializable`                                                |
| Jetpack ViewModel  | lifecycle-viewmodel-compose | `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose`             | Official JetBrains multiplatform ViewModel                                            |
| Coil 2             | Coil 3                      | `io.coil-kt.coil3:coil-compose`                                            | Coil v3 is multiplatform                                                              |
| Timber             | Kermit                      | `co.touchlab:kermit`                                                       | KMP logging with crash reporting integration                                          |
| SharedPreferences  | multiplatform-settings      | `com.russhwolf:multiplatform-settings`                                     | Encrypted variants available                                                          |
| DataStore          | DataStore KMP               | `androidx.datastore:datastore-preferences`                                 | KMP-compatible since 1.1.0                                                            |
| ExoPlayer / Media3 | Interface + DI              | N/A                                                                        | ExoPlayer (Android) + AVPlayer (iOS), shared playback state machine                   |
| Paging 3           | Paging KMP                  | `androidx.paging:paging-common`                                            | KMP since I/O 2025. Use `PagingDataPresenter` (not `AsyncPagingDataDiffer`)           |
| WorkManager        | Interface + DI              | N/A                                                                        | WorkManager (Android) + BGTaskScheduler (iOS)                                         |

## Canonical libs.versions.toml

```toml
[versions]
# ═══ COUPLING GROUP — update together in ONE PR ═══
kotlin = "2.1.20"
ksp = "2.1.20-1.0.31"
# Compose Compiler = kotlin version (merged since 2.0)

# ═══ COMPOSE ═══
compose-multiplatform = "1.7.3"
compose-navigation = "2.9.0-alpha01"

# ═══ NETWORKING ═══
ktor = "3.1.0"
kotlinx-serialization = "1.7.3"

# ═══ PERSISTENCE ═══
sqldelight = "2.0.2"
store5 = "5.1.0-alpha06"
multiplatform-settings = "1.3.0"
datastore = "1.1.3"

# ═══ DI ═══
koin = "4.0.4"

# ═══ LIFECYCLE ═══
lifecycle = "2.9.0-alpha01"

# ═══ ASYNC ═══
kotlinx-coroutines = "1.10.1"
kotlinx-datetime = "0.6.1"

# ═══ LOGGING ═══
kermit = "2.0.5"

# ═══ IMAGES ═══
coil = "3.0.4"

# ═══ iOS INTEROP ═══
skie = "0.10.1"

# ═══ TESTING ═══
turbine = "1.2.0"
mokkery = "2.7.0"

[libraries]
# Ktor
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-okhttp = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktor" }
ktor-client-darwin = { module = "io.ktor:ktor-client-darwin", version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-client-auth = { module = "io.ktor:ktor-client-auth", version.ref = "ktor" }
ktor-client-logging = { module = "io.ktor:ktor-client-logging", version.ref = "ktor" }
ktor-serialization-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }

# Serialization
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }

# Coroutines
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinx-coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinx-coroutines" }

# Datetime
kotlinx-datetime = { module = "org.jetbrains.kotlinx:kotlinx-datetime", version.ref = "kotlinx-datetime" }

# SQLDelight
sqldelight-runtime = { module = "app.cash.sqldelight:runtime", version.ref = "sqldelight" }
sqldelight-coroutines = { module = "app.cash.sqldelight:coroutines-extensions", version.ref = "sqldelight" }
sqldelight-android-driver = { module = "app.cash.sqldelight:android-driver", version.ref = "sqldelight" }
sqldelight-native-driver = { module = "app.cash.sqldelight:native-driver", version.ref = "sqldelight" }

# Store5
store5 = { module = "org.mobilenativefoundation.store:store5", version.ref = "store5" }

# Koin
koin-core = { module = "io.insert-koin:koin-core", version.ref = "koin" }
koin-compose = { module = "io.insert-koin:koin-compose", version.ref = "koin" }
koin-compose-viewmodel = { module = "io.insert-koin:koin-compose-viewmodel", version.ref = "koin" }
koin-android = { module = "io.insert-koin:koin-android", version.ref = "koin" }

# Lifecycle
lifecycle-viewmodel-compose = { module = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
lifecycle-runtime-compose = { module = "org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }

# Logging
kermit = { module = "co.touchlab:kermit", version.ref = "kermit" }

# Images
coil-compose = { module = "io.coil-kt.coil3:coil-compose", version.ref = "coil" }
coil-network-ktor = { module = "io.coil-kt.coil3:coil-network-ktor3", version.ref = "coil" }

# Settings
multiplatform-settings = { module = "com.russhwolf:multiplatform-settings", version.ref = "multiplatform-settings" }

# Testing
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }

[plugins]
kotlinMultiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlinSerialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "compose-multiplatform" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
sqldelight = { id = "app.cash.sqldelight", version.ref = "sqldelight" }
skie = { id = "co.touchlab.skie", version.ref = "skie" }
```

## Version Coupling Rules

### Rule 1: Kotlin = KMP Plugin

They are the same artifact. `org.jetbrains.kotlin.multiplatform` plugin version equals the Kotlin
compiler version.

### Rule 2: Kotlin = Compose Compiler

Since Kotlin 2.0, the Compose compiler plugin (`org.jetbrains.kotlin.plugin.compose`) uses the *
*same version as Kotlin**. They were merged into the Kotlin repository.

### Rule 3: KSP tracks Kotlin

KSP versions are formatted as `kotlinVersion-kspPatch` (e.g., `2.1.20-1.0.31` for Kotlin `2.1.20`).
Find the latest KSP version for your Kotlin version at: https://github.com/google/ksp/releases

### Rule 4: Compose Multiplatform lags independently

Compose Multiplatform is published by JetBrains independently from Kotlin. Check the compatibility
matrix: https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html

## Upgrade Procedure

1. Check the Compose Multiplatform compatibility matrix for your target Kotlin version
2. Find the matching KSP release
3. Update `kotlin`, `ksp`, and verify `compose-multiplatform` compatibility in ONE PR
4. Run full test suite on ALL targets (`./gradlew allTests`)
5. Verify iOS compilation (`./gradlew :shared:iosSimulatorArm64Test`)
6. Never bundle version upgrades with feature work

## Renovate Configuration (Recommended)

```json
{
  "packageRules": [
    {
      "groupName": "Kotlin + KSP",
      "matchPackageNames": [
        "org.jetbrains.kotlin.**",
        "com.google.devtools.ksp"
      ]
    },
    {
      "groupName": "Compose",
      "matchPackageNames": [
        "org.jetbrains.compose",
        "org.jetbrains.androidx.**"
      ]
    },
    {
      "groupName": "Ktor",
      "matchPackageNames": ["io.ktor:**"]
    }
  ],
  "schedule": ["on the first day of the month"]
}
```
