# Offline-First Architecture Patterns for KMP

## Store5 — Complete Configuration

### MutableStore with Write-Through and Retry

```kotlin
// commonMain — full Store5 setup
class TripStoreFactory(
    private val tripApi: TripApi,
    private val tripDao: TripDao,
    private val failedSyncDao: FailedSyncDao,
) {
    fun create(): MutableStore<TripKey, Trip> =
        MutableStoreBuilder.from<TripKey, NetworkTrip, Trip>(
            fetcher = Fetcher.of { key ->
                tripApi.getTrip(key.id)
            },
            sourceOfTruth = SourceOfTruth.of(
                reader = { key ->
                    tripDao.observeById(key.id)
                        .map { it?.toDomain() }
                },
                writer = { _, network ->
                    tripDao.upsert(network.toLocal())
                },
                delete = { key ->
                    tripDao.delete(key.id)
                },
                deleteAll = {
                    tripDao.deleteAll()
                },
            ),
        ).build(
            updater = Updater.by(
                post = { key, local ->
                    try {
                        val response = tripApi.updateTrip(key.id, local.toNetwork())
                        UpdaterResult.Success.Typed(response)
                    } catch (e: Exception) {
                        UpdaterResult.Error.Exception(e)
                    }
                }
            ),
            bookkeeper = Bookkeeper.by(
                getLastFailedSync = { key ->
                    failedSyncDao.getTimestamp(key.id)
                },
                setLastFailedSync = { key, timestamp ->
                    failedSyncDao.upsert(key.id, timestamp)
                    true
                },
                clear = { key ->
                    failedSyncDao.delete(key.id)
                    true
                },
                clearAll = {
                    failedSyncDao.deleteAll()
                    true
                },
            ),
        )
}
```

### Consuming Store in ViewModel

```kotlin
// commonMain
class TripDetailViewModel(
    private val tripStore: MutableStore<TripKey, Trip>,
) : ViewModel() {

    private val _state = MutableStateFlow(TripDetailState())
    val state: StateFlow<TripDetailState> = _state

    fun loadTrip(id: String) {
        viewModelScope.launch {
            tripStore.stream(
                StoreReadRequest.cached(TripKey(id), refresh = true)
            ).collect { response ->
                _state.update { current ->
                    when (response) {
                        is StoreReadResponse.Loading ->
                            current.copy(isLoading = true)
                        is StoreReadResponse.Data ->
                            current.copy(
                                trip = response.value,
                                isLoading = false,
                                isStale = response.origin == StoreReadResponseOrigin.Cache,
                            )
                        is StoreReadResponse.Error.Exception ->
                            current.copy(
                                error = response.error.message,
                                isLoading = false,
                            )
                        is StoreReadResponse.NoNewData ->
                            current.copy(isLoading = false)
                        else -> current
                    }
                }
            }
        }
    }

    // Offline write — Store5 handles retry via Bookkeeper
    fun updateTrip(trip: Trip) {
        viewModelScope.launch {
            try {
                tripStore.write(
                    StoreWriteRequest.of(
                        key = TripKey(trip.id),
                        value = trip,
                    )
                )
            } catch (e: Exception) {
                _state.update { it.copy(error = "Save failed: ${e.message}") }
            }
        }
    }
}

data class TripDetailState(
    val trip: Trip? = null,
    val isLoading: Boolean = false,
    val isStale: Boolean = false,
    val error: String? = null,
)
```

## SQLDelight Schema and Queries

```sql
-- src/commonMain/sqldelight/com/example/db/Trip.sq

CREATE TABLE trip (
    id TEXT NOT NULL PRIMARY KEY,
    driver_id TEXT NOT NULL,
    start_lat REAL NOT NULL,
    start_lng REAL NOT NULL,
    end_lat REAL,
    end_lng REAL,
    distance_km REAL NOT NULL DEFAULT 0.0,
    duration_minutes INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'active',
    driving_score INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    synced INTEGER NOT NULL DEFAULT 0,
    version INTEGER NOT NULL DEFAULT 1
);

-- Reactive observation
observeById:
SELECT * FROM trip WHERE id = ?;

observeAll:
SELECT * FROM trip ORDER BY created_at DESC;

observeUnsynced:
SELECT * FROM trip WHERE synced = 0 ORDER BY created_at ASC;

-- CRUD
upsert:
INSERT OR REPLACE INTO trip VALUES ?;

updateSyncStatus:
UPDATE trip SET synced = ?, version = version + 1, updated_at = ? WHERE id = ?;

delete:
DELETE FROM trip WHERE id = ?;

deleteAll:
DELETE FROM trip;
```

### SQLDelight Driver Creation (expect/actual)

```kotlin
// commonMain
expect class DriverFactory {
    fun create(): SqlDriver
}

// androidMain
actual class DriverFactory(private val context: Context) {
    actual fun create(): SqlDriver =
        AndroidSqliteDriver(AppDatabase.Schema, context, "app.db")
}

// iosMain
actual class DriverFactory {
    actual fun create(): SqlDriver =
        NativeSqliteDriver(AppDatabase.Schema, "app.db")
}
```

## Conflict Resolution Strategies

### Last-Write-Wins (Simple)

```kotlin
// commonMain
class LastWriteWinsResolver : ConflictResolver {
    override fun resolve(local: Trip, remote: Trip): Trip {
        return if (local.updatedAt > remote.updatedAt) {
            local.copy(version = remote.version + 1)
        } else {
            remote
        }
    }
}
```

### Field-Level Merge

```kotlin
// commonMain
class FieldLevelMergeResolver : ConflictResolver {
    override fun resolve(local: Trip, remote: Trip): Trip {
        return Trip(
            id = local.id,
            // User-editable fields: prefer local (user intent)
            driverNotes = local.driverNotes,
            drivingScore = local.drivingScore,
            // System fields: prefer remote (authoritative)
            status = remote.status,
            distanceKm = remote.distanceKm,
            // Timestamps: take latest
            updatedAt = maxOf(local.updatedAt, remote.updatedAt),
            version = remote.version + 1,
        )
    }
}
```

### Conflict Detection with Version Vectors

```kotlin
// commonMain
data class VersionedEntity<T>(
    val data: T,
    val version: Long,
    val lastModifiedBy: String, // "client" or "server"
    val timestamp: Long,
)

class ConflictDetector {
    fun <T> detectConflict(
        local: VersionedEntity<T>,
        remote: VersionedEntity<T>,
    ): ConflictType {
        return when {
            local.version == remote.version -> ConflictType.NONE
            local.lastModifiedBy == "client" && remote.version > local.version - 1 ->
                ConflictType.UPDATE_CONFLICT
            else -> ConflictType.NONE
        }
    }
}

enum class ConflictType { NONE, UPDATE_CONFLICT, DELETE_CONFLICT }
```

## Command Queue Pattern (Without Store5)

For cases where Store5 is overkill or you need fine-grained control:

```kotlin
// commonMain
@Serializable
data class SyncCommand(
    val id: String = Uuid.random().toString(),
    val type: CommandType,
    val entityType: String,
    val entityId: String,
    val payload: String, // JSON-serialized
    val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    val status: CommandStatus = CommandStatus.PENDING,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
)

enum class CommandType { CREATE, UPDATE, DELETE }
enum class CommandStatus { PENDING, IN_PROGRESS, COMPLETED, FAILED, ABANDONED }

class CommandQueue(
    private val commandDao: CommandDao,
    private val json: Json,
) {
    suspend fun enqueue(command: SyncCommand) {
        commandDao.insert(command)
    }

    fun pendingCommands(): Flow<List<SyncCommand>> =
        commandDao.observePending()

    suspend fun markInProgress(id: String) =
        commandDao.updateStatus(id, CommandStatus.IN_PROGRESS)

    suspend fun markCompleted(id: String) =
        commandDao.updateStatus(id, CommandStatus.COMPLETED)

    suspend fun markFailed(id: String, retryCount: Int) {
        if (retryCount >= 3) {
            commandDao.updateStatus(id, CommandStatus.ABANDONED)
        } else {
            commandDao.updateStatusAndRetry(id, CommandStatus.PENDING, retryCount + 1)
        }
    }
}
```

### Sync Engine

```kotlin
// commonMain
class SyncEngine(
    private val commandQueue: CommandQueue,
    private val apiClient: ApiClient,
    private val connectivityMonitor: ConnectivityMonitor,
) {
    fun startSync(): Flow<SyncProgress> = flow {
        connectivityMonitor.isOnline
            .filter { it } // only when online
            .collect {
                val pending = commandQueue.pendingCommands().first()
                emit(SyncProgress(total = pending.size, completed = 0))

                pending.forEachIndexed { index, command ->
                    commandQueue.markInProgress(command.id)
                    try {
                        executeCommand(command)
                        commandQueue.markCompleted(command.id)
                        emit(SyncProgress(total = pending.size, completed = index + 1))
                    } catch (e: Exception) {
                        commandQueue.markFailed(command.id, command.retryCount)
                    }
                }
            }
    }

    private suspend fun executeCommand(command: SyncCommand) {
        when (command.type) {
            CommandType.CREATE -> apiClient.create(command.entityType, command.payload)
            CommandType.UPDATE -> apiClient.update(command.entityType, command.entityId, command.payload)
            CommandType.DELETE -> apiClient.delete(command.entityType, command.entityId)
        }
    }
}

data class SyncProgress(val total: Int, val completed: Int)
```

## Background Sync Scheduling

### Android (WorkManager)

```kotlin
// androidMain
class AndroidSyncScheduler(
    private val context: Context,
) : BackgroundSyncScheduler {

    override fun schedulePeriodicSync(intervalMinutes: Long) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            intervalMinutes, TimeUnit.MINUTES,
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
        ).setBackoffCriteria(
            BackoffPolicy.EXPONENTIAL,
            WorkRequest.MIN_BACKOFF_MILLIS,
            TimeUnit.MILLISECONDS,
        ).build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("periodic_sync", KEEP, request)
    }

    override fun scheduleOneTimeSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            ).build()
        WorkManager.getInstance(context).enqueue(request)
    }

    override fun cancelAll() {
        WorkManager.getInstance(context).cancelUniqueWork("periodic_sync")
    }
}

class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val syncEngine = KoinJavaComponent.get<SyncEngine>(SyncEngine::class.java)
        return try {
            syncEngine.startSync().collect()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
```

### iOS (BGTaskScheduler)

```kotlin
// iosMain
class IosSyncScheduler : BackgroundSyncScheduler {
    override fun schedulePeriodicSync(intervalMinutes: Long) {
        val request = BGAppRefreshTaskRequest(identifier = "com.example.sync")
        request.earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(
            intervalMinutes * 60.0
        )
        BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error = null)
    }

    override fun scheduleOneTimeSync() {
        val request = BGProcessingTaskRequest(identifier = "com.example.sync.processing")
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error = null)
    }

    override fun cancelAll() {
        BGTaskScheduler.sharedScheduler.cancelAllTaskRequests()
    }
}
```

## Sync Status UI Pattern

```kotlin
// commonMain
data class SyncStatus(
    val pendingCount: Int = 0,
    val lastSyncTimestamp: Long? = null,
    val isSyncing: Boolean = false,
    val lastError: String? = null,
) {
    val displayText: String get() = when {
        isSyncing -> "Syncing..."
        pendingCount > 0 -> "$pendingCount changes pending"
        lastSyncTimestamp != null -> "Last synced ${formatRelativeTime(lastSyncTimestamp)}"
        else -> "Not synced"
    }
}

class SyncStatusMonitor(
    private val commandQueue: CommandQueue,
    private val connectivityMonitor: ConnectivityMonitor,
    private val settings: Settings,
) {
    val syncStatus: Flow<SyncStatus> = combine(
        commandQueue.pendingCommands().map { it.size },
        connectivityMonitor.isOnline,
    ) { pendingCount, isOnline ->
        SyncStatus(
            pendingCount = pendingCount,
            lastSyncTimestamp = settings.getLongOrNull("last_sync"),
            isSyncing = isOnline && pendingCount > 0,
        )
    }
}
```
