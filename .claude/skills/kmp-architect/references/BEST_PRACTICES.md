# KMP Best Practices Reference

## expect/actual Elimination Checklist

Before writing `expect fun` or `expect class`, check this list:

| Need               | Library                                       | expect/actual needed? |
|--------------------|-----------------------------------------------|-----------------------|
| UUID               | `kotlin.uuid.Uuid` (stdlib since 2.0)         | NO                    |
| Date/time          | `kotlinx-datetime`                            | NO                    |
| File I/O           | `okio` or `kotlinx-io`                        | NO                    |
| JSON               | `kotlinx.serialization`                       | NO                    |
| HTTP               | `ktor-client-core`                            | NO (engine only)      |
| Logging            | `kermit` (Touchlab)                           | NO                    |
| Key-value storage  | `multiplatform-settings`                      | NO                    |
| Encrypted storage  | `KSafe` or `multiplatform-settings` encrypted | NO                    |
| Coroutines         | `kotlinx-coroutines-core`                     | NO                    |
| Image loading      | `coil3`                                       | NO                    |
| Paging             | `androidx.paging` (KMP since I/O 2025)        | NO                    |
| Database driver    | `SqlDriver`                                   | YES — factory only    |
| HTTP engine        | `HttpClientEngine`                            | YES — factory only    |
| DI platform module | `Koin Module`                                 | YES — wiring only     |

## Ktor Token Refresh — Full Implementation

```kotlin
// commonMain
class TokenManager(
    private val tokenStorage: TokenStorage,
    private val authApi: AuthApi, // separate HttpClient, NO Auth plugin
) {
    private val mutex = Mutex()

    suspend fun refreshIfNeeded(invalidToken: String): BearerTokens? {
        return mutex.withLock {
            // Another coroutine may have already refreshed
            val current = tokenStorage.getAccessToken()
            if (current != null && current != invalidToken) {
                return@withLock BearerTokens(current, tokenStorage.getRefreshToken()!!)
            }

            val refreshToken = tokenStorage.getRefreshToken()
                ?: return@withLock null

            try {
                val response = authApi.refresh(refreshToken)
                tokenStorage.save(response.accessToken, response.refreshToken)
                BearerTokens(response.accessToken, response.refreshToken)
            } catch (e: Exception) {
                tokenStorage.clear()
                null // forces re-login
            }
        }
    }
}

// AuthApi uses a SEPARATE HttpClient with NO Auth plugin
class AuthApi(engine: HttpClientEngine) {
    private val client = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        // NO Auth plugin here — prevents circular refresh
    }

    suspend fun refresh(refreshToken: String): TokenResponse =
        client.post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(refreshToken))
        }.body()
}

// Main HttpClient configuration
fun createHttpClient(
    engine: HttpClientEngine,
    tokenManager: TokenManager,
    tokenStorage: TokenStorage,
): HttpClient = HttpClient(engine) {

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }

    install(Auth) {
        bearer {
            loadTokens {
                tokenStorage.getAccessToken()?.let {
                    BearerTokens(it, tokenStorage.getRefreshToken()!!)
                }
            }
            refreshTokens {
                tokenManager.refreshIfNeeded(
                    invalidToken = oldTokens?.accessToken ?: ""
                )
            }
        }
    }

    // KEEP SEPARATE from Auth — mixing causes infinite loops
    install(HttpRequestRetry) {
        maxRetries = 3
        retryIf { _, response -> response.status.value in 500..599 }
        delayMillis { retry -> retry * 1000L }
    }

    install(Logging) {
        logger = object : Logger {
            override fun log(message: String) { Kermit.d { message } }
        }
        level = LogLevel.HEADERS
    }
}
```

## Store5 — Full Configuration

```kotlin
// commonMain
val tripStore: MutableStore<TripKey, Trip> =
    MutableStoreBuilder.from<TripKey, NetworkTrip, Trip>(
        fetcher = Fetcher.of { key -> tripApi.getTrip(key.id) },
        sourceOfTruth = SourceOfTruth.of(
            reader = { key -> tripDao.observeById(key.id) },
            writer = { _, network -> tripDao.upsert(network.toLocal()) },
            delete = { key -> tripDao.delete(key.id) },
            deleteAll = { tripDao.deleteAll() },
        ),
    ).build(
        updater = Updater.by(
            post = { key, local ->
                val response = tripApi.updateTrip(key.id, local.toNetwork())
                UpdaterResult.Success.Typed(response)
            }
        ),
        bookkeeper = Bookkeeper.by(
            getLastFailedSync = { key -> failedSyncDao.getTimestamp(key.id) },
            setLastFailedSync = { key, ts -> failedSyncDao.upsert(key.id, ts); true },
            clear = { key -> failedSyncDao.delete(key.id); true },
            clearAll = { failedSyncDao.deleteAll(); true },
        ),
    )

// Usage in ViewModel
fun loadTrip(id: String) {
    viewModelScope.launch {
        tripStore.stream(StoreReadRequest.cached(TripKey(id), refresh = true))
            .collect { response ->
                when (response) {
                    is StoreReadResponse.Data -> _state.update {
                        it.copy(trip = response.value, isLoading = false)
                    }
                    is StoreReadResponse.Loading -> _state.update {
                        it.copy(isLoading = true)
                    }
                    is StoreReadResponse.Error.Exception -> _state.update {
                        it.copy(error = response.error.message, isLoading = false)
                    }
                    is StoreReadResponse.NoNewData -> { /* stale data is fine */ }
                    else -> {}
                }
            }
    }
}

// Offline write
fun updateTrip(trip: Trip) {
    viewModelScope.launch {
        tripStore.write(
            StoreWriteRequest.of(
                key = TripKey(trip.id),
                value = trip,
            )
        )
    }
}
```

## Koin DI Setup

```kotlin
// commonMain — shared module definition
val sharedModule = module {
    // Network
    single { createHttpClient(get(), get(), get()) }
    single { TokenManager(get(), get()) }
    single { AuthApi(get()) }

    // Repositories
    singleOf(::TripRepositoryImpl).bind<TripRepository>()

    // Use cases
    factoryOf(::GetTripsListUseCase)
    factoryOf(::SyncTripsUseCase)

    // ViewModels
    viewModelOf(::TripListViewModel)
}

// App initialization (Android)
class MyApplication : Application(), KoinApplication {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@MyApplication)
            modules(sharedModule, platformModule)
        }
    }
}

// App initialization (iOS via Swift)
// KoinHelper.kt in iosMain
fun initKoin() {
    startKoin {
        modules(sharedModule, platformModule)
    }
}
```

## Error Handling Across Platforms

```kotlin
// DO: Use sealed class hierarchies
sealed interface AppError {
    data class Network(val code: Int, val message: String) : AppError
    data class Database(val cause: String) : AppError
    data object Unauthorized : AppError
    data class Unknown(val throwable: Throwable) : AppError
}

// DO: Annotate functions callable from Swift
@Throws(CancellationException::class)
suspend fun getTrips(): List<Trip>

// DON'T: Use Result<T> in public API (inline class → Any? in Swift)
// DON'T: Use runCatching in suspend functions (swallows CancellationException)
```

## Testing Patterns

```kotlin
// Flow testing with Turbine
@Test
fun `emits loading then data`() = runTest {
    val viewModel = TripListViewModel(FakeGetTripsUseCase(testTrips))

    viewModel.state.test {
        assertEquals(TripListState(), awaitItem()) // initial
        viewModel.onAction(TripListAction.Load)
        assertEquals(true, awaitItem().isLoading)
        assertEquals(testTrips, awaitItem().trips)
        cancelAndIgnoreRemainingEvents()
    }
}

// Manual fake over mocking framework
class FakeTripApi(
    private val shouldFail: Boolean = false,
    private val trips: List<NetworkTrip> = emptyList(),
) : TripApi {
    override suspend fun getTrips(): List<NetworkTrip> {
        if (shouldFail) throw IOException("Network error")
        return trips
    }
}
```

## SKIE Configuration

```kotlin
// build.gradle.kts
plugins {
    id("co.touchlab.skie") version "0.10.1"
}

skie {
    features {
        enableSwiftUIObservingPreview = true  // ObservableObject for ViewModels
        coroutinesInterop.set(true)           // suspend → async throws
    }
}
```

## GitHub Actions CI Template

```yaml
name: KMP CI
on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  android:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '17', distribution: 'temurin' }
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew :shared:testDebugUnitTest
      - run: ./gradlew :androidApp:assembleDebug

  ios:
    runs-on: macos-latest
    if: |
      contains(github.event.pull_request.labels.*.name, 'ios')
      || github.ref == 'refs/heads/main'
    steps:
      - uses: actions/checkout@v4
      - uses: actions/cache@v4
        with:
          path: ~/.konan
          key: konan-${{ hashFiles('**/libs.versions.toml') }}
      - uses: actions/setup-java@v4
        with: { java-version: '17', distribution: 'temurin' }
      - run: ./gradlew :shared:iosSimulatorArm64Test
      - run: |
          cd iosApp
          xcodebuild -scheme iosApp \
            -sdk iphonesimulator \
            -destination 'platform=iOS Simulator,name=iPhone 16' \
            build
```
