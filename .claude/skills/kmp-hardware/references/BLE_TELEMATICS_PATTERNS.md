# BLE, GPS, and Sensor Integration Patterns for KMP

## BLE Communication with Kable

### Scanning and Connecting

```kotlin
// commonMain — full BLE communication via Kable
class KableDeviceConnector(
    private val scope: CoroutineScope,
) : DeviceConnector {

    private val scanner = Scanner {
        filters {
            match { services = listOf(uuidFrom(OBD_SERVICE_UUID)) }
        }
    }

    private var peripheral: Peripheral? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    override suspend fun connect(deviceId: String) {
        _connectionState.value = ConnectionState.CONNECTING

        val advertisement = scanner.advertisements
            .first { it.identifier == deviceId }

        peripheral = scope.peripheral(advertisement) {
            // Connection configuration
            onServicesDiscovered {
                _connectionState.value = ConnectionState.CONNECTED
            }
        }.also { p ->
            p.state.onEach { state ->
                when (state) {
                    is State.Disconnected -> _connectionState.value = ConnectionState.DISCONNECTED
                    is State.Connected -> _connectionState.value = ConnectionState.CONNECTED
                    else -> {}
                }
            }.launchIn(scope)

            p.connect()
        }
    }

    override val dataStream: Flow<DeviceDataFrame> =
        peripheral?.observe(
            characteristicOf(
                service = OBD_SERVICE_UUID,
                characteristic = OBD_NOTIFY_UUID,
            )
        )?.map { bytes ->
            DeviceDataFrame.parse(bytes) // pure Kotlin parsing
        } ?: emptyFlow()

    override suspend fun sendCommand(command: ByteArray): ByteArray {
        val writeChar = characteristicOf(
            service = OBD_SERVICE_UUID,
            characteristic = OBD_WRITE_UUID,
        )
        peripheral!!.write(writeChar, command)

        // Read response
        val readChar = characteristicOf(
            service = OBD_SERVICE_UUID,
            characteristic = OBD_READ_UUID,
        )
        return peripheral!!.read(readChar)
    }

    override fun disconnect() {
        scope.launch { peripheral?.disconnect() }
    }
}
```

### OBD-II PID Decoding (Pure Kotlin — commonMain)

```kotlin
// commonMain — no platform dependencies
enum class ObdPid(val code: String, val bytes: Int) {
    ENGINE_RPM("010C", 2),
    VEHICLE_SPEED("010D", 1),
    COOLANT_TEMP("0105", 1),
    FUEL_LEVEL("012F", 1),
    ENGINE_LOAD("0104", 1),
    THROTTLE_POS("0111", 1),
}

class ObdPidDecoder {
    fun decode(pid: ObdPid, rawResponse: ByteArray): VehicleMetric {
        // Strip header bytes (mode + PID echo)
        val data = rawResponse.drop(2)
        return when (pid) {
            ObdPid.ENGINE_RPM -> {
                val value = ((data[0].toInt() and 0xFF) * 256 + (data[1].toInt() and 0xFF)) / 4.0
                VehicleMetric.Rpm(value)
            }
            ObdPid.VEHICLE_SPEED -> {
                VehicleMetric.SpeedKmh(data[0].toInt() and 0xFF)
            }
            ObdPid.COOLANT_TEMP -> {
                VehicleMetric.Temperature((data[0].toInt() and 0xFF) - 40)
            }
            ObdPid.FUEL_LEVEL -> {
                val percent = ((data[0].toInt() and 0xFF) * 100) / 255.0
                VehicleMetric.FuelPercent(percent)
            }
            else -> VehicleMetric.Raw(data.toByteArray())
        }
    }
}

sealed interface VehicleMetric {
    data class Rpm(val value: Double) : VehicleMetric
    data class SpeedKmh(val value: Int) : VehicleMetric
    data class Temperature(val celsius: Int) : VehicleMetric
    data class FuelPercent(val percent: Double) : VehicleMetric
    data class Raw(val bytes: ByteArray) : VehicleMetric
}
```

## GPS / Location Tracking

### Android Implementation

```kotlin
// androidMain
class AndroidLocationProvider(
    private val context: Context,
) : LocationProvider {

    private val client = LocationServices.getFusedLocationProviderClient(context)

    override val locationUpdates: Flow<Location> = callbackFlow {
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    trySend(Location(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        altitude = loc.altitude,
                        speed = loc.speed.toDouble(),
                        bearing = loc.bearing.toDouble(),
                        accuracy = loc.accuracy.toDouble(),
                        timestamp = loc.time,
                    ))
                }
            }
        }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5000L // 5 second intervals
        ).setMinUpdateDistanceMeters(10f)
            .build()

        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }

    override fun startTracking(config: TrackingConfig) {
        // Managed by Flow collection lifecycle
    }

    override fun stopTracking() {
        // Managed by Flow cancellation
    }
}
```

### iOS Implementation

```kotlin
// iosMain
class IosLocationProvider : LocationProvider {
    private val manager = CLLocationManager()

    override val locationUpdates: Flow<Location> = callbackFlow {
        val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
            override fun locationManager(
                manager: CLLocationManager,
                didUpdateLocations: List<*>,
            ) {
                val loc = didUpdateLocations.lastOrNull() as? CLLocation ?: return
                trySend(Location(
                    latitude = loc.coordinate.latitude,
                    longitude = loc.coordinate.longitude,
                    altitude = loc.altitude,
                    speed = loc.speed,
                    bearing = loc.course,
                    accuracy = loc.horizontalAccuracy,
                    timestamp = (loc.timestamp.timeIntervalSince1970 * 1000).toLong(),
                ))
            }
        }
        manager.delegate = delegate
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 10.0
        manager.requestAlwaysAuthorization()
        manager.startUpdatingLocation()
        awaitClose { manager.stopUpdatingLocation() }
    }
}
```

### Trip Recording (Pure Kotlin — commonMain)

```kotlin
// commonMain — no platform dependencies
class TripRecorder(
    private val locationProvider: LocationProvider,
    private val tripDao: TripDao,
) {
    private val _tripState = MutableStateFlow<TripState>(TripState.Idle)
    val tripState: StateFlow<TripState> = _tripState

    private val waypoints = mutableListOf<Location>()

    fun startTrip(driverId: String): Flow<TripUpdate> = flow {
        val tripId = Uuid.random().toString()
        waypoints.clear()
        _tripState.value = TripState.Recording(tripId)

        locationProvider.locationUpdates.collect { location ->
            waypoints.add(location)
            val distance = calculateTotalDistance(waypoints)
            val update = TripUpdate(tripId, location, distance, waypoints.size)
            emit(update)

            // Persist incrementally
            tripDao.updateTrip(tripId, distance, location.latitude, location.longitude)
        }
    }

    private fun calculateTotalDistance(points: List<Location>): Double {
        if (points.size < 2) return 0.0
        return points.zipWithNext { a, b -> haversineDistance(a, b) }.sum()
    }

    private fun haversineDistance(a: Location, b: Location): Double {
        val R = 6371.0 // Earth radius in km
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * R * asin(sqrt(h))
    }
}
```

## Sensor Fusion for Telematics

### Accelerometer Provider (Android)

```kotlin
// androidMain
class AndroidMotionSensorProvider(
    private val context: Context,
) : MotionSensorProvider {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    override val accelerometer: Flow<AccelerometerReading> = callbackFlow {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                trySend(AccelerometerReading(
                    x = event.values[0].toDouble(),
                    y = event.values[1].toDouble(),
                    z = event.values[2].toDouble(),
                    timestamp = event.timestamp,
                ))
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sensorManager.unregisterListener(listener) }
    }

    override val gyroscope: Flow<GyroscopeReading> = callbackFlow {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                trySend(GyroscopeReading(
                    x = event.values[0].toDouble(),
                    y = event.values[1].toDouble(),
                    z = event.values[2].toDouble(),
                    timestamp = event.timestamp,
                ))
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sensorManager.unregisterListener(listener) }
    }

    override fun start(samplingRate: SamplingRate) { /* managed by Flow */ }
    override fun stop() { /* managed by cancellation */ }
}
```

### Driving Behavior Analysis (Pure Kotlin — commonMain)

```kotlin
// commonMain — all signal processing is shared
class DrivingBehaviorAnalyzer(
    private val motionSensor: MotionSensorProvider,
    private val locationProvider: LocationProvider,
) {
    companion object {
        const val HARSH_BRAKE_THRESHOLD = 3.0    // m/s² deceleration
        const val HARSH_ACCEL_THRESHOLD = 2.5    // m/s² acceleration
        const val HARSH_TURN_THRESHOLD = 4.0     // m/s² lateral
        const val ERRATIC_STD_DEV_THRESHOLD = 2.0
        const val SPEEDING_THRESHOLD_KMH = 120
    }

    fun analyzeDriving(): Flow<DrivingEvent> =
        motionSensor.accelerometer
            .windowed(size = 50, step = 10) // 50 readings, slide by 10
            .map { window -> detectEvents(window) }
            .flatMapConcat { events -> flowOf(*events.toTypedArray()) }

    private fun detectEvents(window: List<AccelerometerReading>): List<DrivingEvent> {
        val events = mutableListOf<DrivingEvent>()
        val magnitudes = window.map { sqrt(it.x * it.x + it.y * it.y + it.z * it.z) }

        // Harsh braking: sudden deceleration along forward axis
        val forwardDecel = window.map { it.y }.min()
        if (forwardDecel < -HARSH_BRAKE_THRESHOLD) {
            events.add(DrivingEvent.HarshBrake(abs(forwardDecel)))
        }

        // Harsh acceleration
        val forwardAccel = window.map { it.y }.max()
        if (forwardAccel > HARSH_ACCEL_THRESHOLD) {
            events.add(DrivingEvent.HarshAcceleration(forwardAccel))
        }

        // Harsh turning: lateral acceleration
        val lateralMax = window.map { abs(it.x) }.max()
        if (lateralMax > HARSH_TURN_THRESHOLD) {
            events.add(DrivingEvent.HarshTurn(lateralMax))
        }

        // Erratic driving: high variance
        val stdDev = magnitudes.standardDeviation()
        if (stdDev > ERRATIC_STD_DEV_THRESHOLD) {
            events.add(DrivingEvent.ErraticDriving(stdDev))
        }

        return events
    }

    fun computeDrivingScore(events: List<DrivingEvent>, tripDurationMinutes: Double): Int {
        var score = 100
        for (event in events) {
            score -= when (event) {
                is DrivingEvent.HarshBrake -> 5
                is DrivingEvent.HarshAcceleration -> 3
                is DrivingEvent.HarshTurn -> 4
                is DrivingEvent.ErraticDriving -> 2
                is DrivingEvent.Speeding -> 3
                else -> 0
            }
        }
        return score.coerceIn(0, 100)
    }
}

sealed interface DrivingEvent {
    data class HarshBrake(val magnitude: Double) : DrivingEvent
    data class HarshAcceleration(val magnitude: Double) : DrivingEvent
    data class HarshTurn(val magnitude: Double) : DrivingEvent
    data class ErraticDriving(val stdDeviation: Double) : DrivingEvent
    data class Speeding(val speedKmh: Int) : DrivingEvent
    data object Normal : DrivingEvent
}

// Extension function for standard deviation
fun List<Double>.standardDeviation(): Double {
    val mean = average()
    val variance = map { (it - mean).pow(2) }.average()
    return sqrt(variance)
}
```
