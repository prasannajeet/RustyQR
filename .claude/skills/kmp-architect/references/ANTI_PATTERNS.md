# KMP Anti-Patterns Catalog

Every anti-pattern includes: what the mistake looks like, why it fails, and the correct approach.

---

## 1. expect/actual for everything

### Bad

```kotlin
// commonMain
expect fun generateUUID(): String
expect fun getDeviceName(): String
expect fun isNetworkAvailable(): Boolean
expect fun getSecureStorage(): KeyValueStorage
expect fun formatDate(millis: Long): String
// 15+ expect/actual pairs scattered across the codebase
```

### Why it fails

Each expect/actual requires implementations in EVERY platform source set. 15 declarations × 2
platforms = 30 files to maintain. Most of these have mature library equivalents.

### Good

```kotlin
// Use libraries — zero expect/actual needed
import kotlin.uuid.Uuid           // stdlib since Kotlin 2.0
import kotlinx.datetime.Instant    // kotlinx-datetime
import io.ktor.client.*            // ktor
import com.russhwolf.settings.*    // multiplatform-settings

// Only expect/actual for DI wiring
expect val platformModule: Module
```

---

## 2. Using Result<T> in public API

### Bad

```kotlin
// commonMain — exposed to Swift
suspend fun getTrips(): Result<List<Trip>>
```

### Why it fails

`Result` is an inline class. In Kotlin/Native → Objective-C → Swift, it becomes `Any?`. iOS
developers lose all type information. SKIE cannot enhance it.

### Good

```kotlin
// Sealed class hierarchy — maps to Swift enum via SKIE
sealed interface DataResult<out T> {
    data class Success<T>(val data: T) : DataResult<T>
    data class Error(val error: AppError) : DataResult<Nothing>
}

suspend fun getTrips(): DataResult<List<Trip>>
```

---

## 3. runCatching in suspend functions

### Bad

```kotlin
suspend fun fetchTrips(): Result<List<Trip>> = runCatching {
    api.getTrips()
}
```

### Why it fails

`runCatching` catches ALL exceptions including `CancellationException`. Swallowing
`CancellationException` breaks structured concurrency — coroutines won't cancel properly.

### Good

```kotlin
suspend fun fetchTrips(): DataResult<List<Trip>> {
    return try {
        DataResult.Success(api.getTrips())
    } catch (e: CancellationException) {
        throw e // ALWAYS rethrow
    } catch (e: Exception) {
        DataResult.Error(AppError.Network(e.message ?: "Unknown"))
    }
}
```

---

## 4. runBlocking in shared code

### Bad

```kotlin
// commonMain
class TripRepository {
    fun getTripsSync(): List<Trip> = runBlocking {
        api.getTrips()
    }
}
```

### Why it fails

`runBlocking` blocks the calling thread. On iOS (Main/UI thread), this freezes the app. On Android,
it causes ANRs. It also defeats the purpose of coroutines.

### Good

```kotlin
class TripRepository {
    suspend fun getTrips(): List<Trip> = api.getTrips()
    fun observeTrips(): Flow<List<Trip>> = tripDao.observeAll()
}
```

---

## 5. Android framework imports in commonMain

### Bad

```kotlin
// commonMain
import android.util.Log
import android.content.Context
import androidx.lifecycle.ViewModel // Android-only ViewModel
```

### Why it fails

commonMain compiles for ALL targets. Android-specific imports break iOS compilation.

### Good

```kotlin
// commonMain
import co.touchlab.kermit.Logger           // KMP logging
import org.koin.core.component.KoinComponent // KMP DI
import androidx.lifecycle.ViewModel         // OK — this IS multiplatform since lifecycle-viewmodel-compose
```

---

## 6. Mixing HttpRequestRetry with Auth

### Bad

```kotlin
install(Auth) {
    bearer {
        refreshTokens { /* refresh logic */ }
    }
}
install(HttpRequestRetry) {
    maxRetries = 3
    retryIf { _, response -> response.status.value >= 400 } // includes 401!
}
```

### Why it fails

401 triggers both Auth refresh AND HttpRequestRetry. Auth refreshes the token, then retry re-sends
with the OLD token (retry happens before auth completes). Infinite loop.

### Good

```kotlin
install(Auth) {
    bearer {
        refreshTokens { /* Mutex-guarded refresh */ }
    }
}
install(HttpRequestRetry) {
    maxRetries = 3
    retryIf { _, response -> response.status.value in 500..599 } // 5xx ONLY
    delayMillis { retry -> retry * 1000L }
}
```

---

## 7. Single HttpClient for everything including refresh

### Bad

```kotlin
val client = HttpClient(engine) {
    install(Auth) { bearer { refreshTokens { client.post("/refresh") } } }
}
```

### Why it fails

The refresh call goes through the same Auth plugin. If the refresh endpoint returns 401 (expired
refresh token), it tries to refresh again → infinite recursion.

### Good

```kotlin
// Dedicated refresh client — NO Auth plugin
val refreshClient = HttpClient(engine) {
    install(ContentNegotiation) { json() }
}
// Main client
val mainClient = HttpClient(engine) {
    install(Auth) { bearer { refreshTokens { refreshClient.post("/refresh") } } }
}
```

---

## 8. Upgrading Kotlin without KSP and Compose Compiler

### Bad

```toml
# Updating only Kotlin, leaving others behind
kotlin = "2.1.20"
ksp = "2.0.10-1.0.24"        # MISMATCHED
compose-plugin = "1.6.0"      # STALE
```

### Why it fails

KSP version must track Kotlin version exactly (format: `kotlinVersion-kspPatch`). Compose Compiler
is merged into Kotlin since 2.0 — they MUST match. Mismatch → cryptic compilation errors in
annotation processors and Compose compiler.

### Good

```toml
kotlin = "2.1.20"
ksp = "2.1.20-1.0.31"         # MATCHES Kotlin
compose-plugin = "1.7.3"       # Compatible with Kotlin 2.1.20
```

Update ALL THREE in a single PR. Never bundle with feature work.

---

## 9. CocoaPods for framework distribution

### Bad

```kotlin
cocoapods {
    summary = "Shared module"
    homepage = "https://example.com"
    ios.deploymentTarget = "16.0"
    framework { baseName = "shared" }
}
```

### Why it fails

CocoaPods is in maintenance mode. Ruby version conflicts with Xcode 16+. `pod install` sync is
fragile and breaks after KMP framework changes. Apple is moving to SPM.

### Good

```kotlin
// Direct integration for local dev
listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
    target.binaries.framework {
        baseName = "shared"
        isStatic = true
    }
}
// KMMBridge + SPM for distribution
```

---

## 10. Not using SKIE

### Bad — What iOS developers see without SKIE

```swift
// Sealed class → open class hierarchy, manual casting
if let action = state as? TripListState.Loading {
    // no exhaustive check, easy to miss cases
}

// suspend fun → completion handler
viewModel.getTrips { result, error in
    // no async/await, no cancellation
}

// Flow → opaque KotlinFlowWrapper
// Can't iterate, no AsyncSequence
```

### Good — With SKIE

```swift
// Sealed class → Swift enum with exhaustive switch
switch onEnum(of: state) {
case .loading: showSpinner()
case .data(let s): showTrips(s.trips)
case .error(let e): showError(e.message)
}

// suspend → async throws
let trips = try await viewModel.getTrips()

// Flow → AsyncSequence
for await state in viewModel.state {
    updateUI(state)
}
```

---

## 11. Dynamic frameworks without dead code stripping

### Bad

```kotlin
target.binaries.framework {
    baseName = "shared"
    // isStatic defaults to false → dynamic framework
}
```

### Why it fails

Dynamic frameworks include ALL symbols, even unused ones. Framework size bloats. No dead code
elimination by the iOS linker. More complex to embed correctly.

### Good

```kotlin
target.binaries.framework {
    baseName = "shared"
    isStatic = true // enables iOS linker dead code stripping
    linkerOpts("-lsqlite3") // required for SQLDelight
    binaryOption("bundleId", "com.example.shared")
}
```

---

## 12. Unused Default Hierarchy Template source sets

### Bad

```kotlin
kotlin {
    androidTarget()
    iosArm64()
    iosSimulatorArm64()
    applyDefaultHierarchyTemplate() // creates nativeMain, appleMain, etc.
}
// nativeMain and appleMain are empty but generate Gradle tasks
```

### Why it fails

In 70+ module projects, unused intermediate source sets can balloon Gradle sync from 14 to 80+
minutes. Each empty source set generates compilation and metadata tasks.

### Good

```kotlin
// Option A: Disable and configure manually
kotlin {
    androidTarget()
    iosArm64()
    iosSimulatorArm64()
    // applyDefaultHierarchyTemplate() — DISABLED

    sourceSets {
        val iosMain by creating { dependsOn(commonMain.get()) }
        val iosArm64Main by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }
    }
}

// Option B: Use template but set flag in gradle.properties
// kotlin.mpp.applyDefaultHierarchyTemplate=false
```
