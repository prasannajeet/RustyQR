---
name: kmp-architect
description: "Kotlin Multiplatform architecture, patterns, and code generation for production mobile apps. Use when: scaffolding KMP modules, reviewing KMP code, writing shared Kotlin for Android+iOS, configuring Gradle for KMP, setting up Ktor/Koin/SQLDelight/Store5, designing expect/actual boundaries, creating convention plugins, managing Kotlin/KSP/Compose version coupling, migrating Android-only codebases to KMP, or making architectural decisions about shared code boundaries. Also triggers for: MVI/UDF state management in commonMain, SKIE configuration, XCFramework distribution, CI/CD for dual-platform builds, and KMP code review."
---

# KMP Architect Skill

Staff-level architectural guidance for Kotlin Multiplatform mobile projects. Covers project
structure, shared code boundaries, library selection, Gradle configuration, testing, CI/CD, and
migration patterns.

## Core Architectural Principle

Share everything logically identical across platforms. Abstract everything platform-specific behind
interfaces. Hardware APIs, inference engines, and OS-level schedulers stay in platform source sets.
Business logic, networking, persistence, state management, and data models go in `commonMain`.

## When Scaffolding KMP Modules

### Project Structure

```
project-root/
├── build-logic/
│   └── convention/             # Convention plugins (REQUIRED at scale)
├── core/
│   ├── domain/                 # Use cases, entities (commonMain ONLY)
│   ├── data/                   # Repositories, DTOs, mappers
│   ├── network/                # Ktor client, API definitions
│   └── database/               # SQLDelight .sq files or Room entities
├── feature/
│   ├── <feature-name>/         # ViewModel + UI per feature
│   └── ...
├── androidApp/
└── iosApp/
```

### Convention Plugin Pattern

Every KMP project with 3+ modules MUST use convention plugins. Write the target matrix once:

```kotlin
// build-logic/convention/src/main/kotlin/KmpLibraryPlugin.kt
class KmpLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("org.jetbrains.kotlin.multiplatform")
            apply("com.android.kotlin.multiplatform.library")
        }
        extensions.configure<KotlinMultiplatformExtension> {
            androidTarget()
            iosArm64()
            iosSimulatorArm64()
            // Skip iosX64() if team is fully Apple Silicon
            applyDefaultHierarchyTemplate()
        }
    }
}
```

Module build files become minimal:

```kotlin
plugins { id("com.example.kmp.library") }
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.domain)
        }
    }
}
```

## expect/actual Minimization

**CRITICAL**: Before writing `expect fun` or `expect class`, check if a multiplatform library
exists. See [BEST_PRACTICES.md](references/BEST_PRACTICES.md) for the full checklist.

The ONLY valid uses of expect/actual:

1. Database driver creation (`SqlDriver`)
2. HTTP client engine creation (`HttpClientEngine`)
3. Platform DI module wiring (`Koin Module`)

Everything else uses **interface + dependency injection**:

```kotlin
// commonMain — define the contract
interface ConnectivityMonitor {
    val isOnline: StateFlow<Boolean>
}

// Wire via Koin — the single expect/actual point
expect val platformModule: Module

// androidMain
actual val platformModule = module {
    singleOf(::AndroidConnectivityMonitor).bind<ConnectivityMonitor>()
    single<HttpClientEngine> { OkHttp.create() }
    single<SqlDriver> { AndroidSqliteDriver(AppDb.Schema, get(), "app.db") }
}

// iosMain
actual val platformModule = module {
    singleOf(::IosConnectivityMonitor).bind<ConnectivityMonitor>()
    single<HttpClientEngine> { Darwin.create() }
    single<SqlDriver> { NativeSqliteDriver(AppDb.Schema, "app.db") }
}
```

## Library Stack

See [LIBRARY_MATRIX.md](references/LIBRARY_MATRIX.md) for the full replacement matrix and version
coupling rules.

**Canonical KMP stack:**

- **Networking**: Ktor HttpClient + kotlinx.serialization
- **DI**: Koin 4.x (pure Kotlin, no codegen)
- **Local DB**: SQLDelight (SQL-first) or Room KMP 2.8+ (annotation-driven)
- **State mgmt**: lifecycle-viewmodel-compose + MVI/UDF
- **Caching/sync**: Store5 (Mobile Native Foundation)
- **Logging**: Kermit (Touchlab)
- **Key-value storage**: multiplatform-settings
- **Image loading**: Coil 3
- **iOS interop**: SKIE (Touchlab)
- **Framework distribution**: KMMBridge + SPM

## MVI State Management Pattern

All ViewModels follow this structure in commonMain:

```kotlin
class FeatureViewModel(
    private val useCase: FeatureUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(FeatureState())
    val state: StateFlow<FeatureState> = _state.asStateFlow()

    fun onAction(action: FeatureAction) {
        when (action) {
            is FeatureAction.Load -> load()
            is FeatureAction.Retry -> retry()
            // exhaustive when
        }
    }
}

data class FeatureState(
    val items: List<Item> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

sealed interface FeatureAction {
    data object Load : FeatureAction
    data object Retry : FeatureAction
}
```

Consumed via `collectAsState()` in Compose and `AsyncSequence` (via SKIE) in SwiftUI.

## Ktor Token Refresh

Use Mutex-guarded refresh with check-after-lock.
See [BEST_PRACTICES.md](references/BEST_PRACTICES.md) for the full implementation.

Key rules:

1. Use a **separate HttpClient** for refresh calls (no Auth plugin)
2. Read tokens from **live state** in `defaultRequest`, not `loadTokens`
3. Keep `HttpRequestRetry` (5xx) separate from `Auth` (401s)

## Version Management

**Three coupling rules (violations break builds):**

1. **Kotlin = KMP plugin** (same artifact)
2. **Kotlin = Compose Compiler** (merged since Kotlin 2.0)
3. **KSP tracks Kotlin** (format: `kotlinVersion-kspPatch`)

See [LIBRARY_MATRIX.md](references/LIBRARY_MATRIX.md) for the canonical `libs.versions.toml`
template.

## Testing Strategy

```kotlin
// commonTest — kotlin.test framework
class TripRepositoryTest {
    @Test
    fun `returns cached trips when offline`() = runTest {
        val repo = TripRepository(
            api = FakeTripApi(shouldFail = true),
            dao = FakeTripDao(trips = listOf(testTrip)),
        )
        val result = repo.getTrips().first()
        assertEquals(listOf(testTrip), result)
    }
}
```

- Use `kotlin.test` (runs on all targets)
- Use **Turbine** (Cash App) for Flow testing
- Use **manual fakes/stubs** over mocking frameworks (MockK is JVM-only)
- Use **Mokkery** if you need a mocking framework (compiler plugin, KMP-compatible)

## iOS Framework Configuration

```kotlin
kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "shared"
            isStatic = true
            linkerOpts("-lsqlite3")
        }
    }
}
```

Use **SKIE** for idiomatic Swift APIs. Use **SPM** over CocoaPods. Use **KMMBridge** for binary
distribution.

## Code Review Checklist

When reviewing KMP code, check for:

- [ ] expect/actual used only for engine/driver creation
- [ ] No Android framework imports in commonMain
- [ ] State is a single `StateFlow<ScreenState>` per ViewModel
- [ ] Ktor client uses `ContentNegotiation` with `kotlinx.serialization`
- [ ] SQLDelight queries return `Flow` for reactive observation
- [ ] No `runBlocking` in shared code (use `runTest` in tests only)
- [ ] Error types are sealed classes, not `Result<T>` (inline class breaks Swift)
- [ ] Functions callable from Swift are annotated with `@Throws`
- [ ] libs.versions.toml has Kotlin = Compose Compiler = KSP aligned

See [ANTI_PATTERNS.md](references/ANTI_PATTERNS.md) for the full anti-pattern catalog with fixes.

## Migration from Android-Only

**Phase 1**: Replace libraries while Android-only (Retrofit→Ktor, Hilt→Koin,
Gson→kotlinx.serialization)
**Phase 2**: Extract domain layer to KMP module (pure Kotlin, no Android deps)
**Phase 3**: Share data layer (Ktor, SQLDelight/Room KMP, Store5)
**Phase 4**: Share ViewModels (lifecycle-viewmodel-compose), optionally share UI

## Reference Documents

- [BEST_PRACTICES.md](references/BEST_PRACTICES.md) — Do's and don'ts with full code examples
- [ANTI_PATTERNS.md](references/ANTI_PATTERNS.md) — Common mistakes with fixes and explanations
- [LIBRARY_MATRIX.md](references/LIBRARY_MATRIX.md) — Library replacements, version catalog
  template, coupling rules
