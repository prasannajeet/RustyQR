---
name: kmp-hardware
description: "Kotlin Multiplatform hardware integration patterns for BLE, GPS, sensors, on-device ML, camera, audio, and offline-first architecture. Use when: building KMP apps that interact with Bluetooth devices (OBD-II, IoT, beacons), implementing GPS/location tracking, accessing accelerometer/gyroscope for telematics or fitness, integrating TensorFlow Lite or Core ML for on-device inference, building camera or barcode scanning features, implementing audio/media playback, designing offline-first sync with Store5, or scheduling background work across platforms. Also triggers for: telematics apps, fleet management, driving behavior analysis, sensor fusion, and platform permission handling in KMP."
---

# KMP Hardware Integration Skill

Patterns for integrating platform hardware into Kotlin Multiplatform apps. Every hardware domain
follows the same architectural principle: **define contracts in commonMain, inject platform-native
implementations via Koin**.

## Core Pattern: Interface + Dependency Inversion

Hardware APIs differ fundamentally between Android and iOS. Never attempt to unify them behind a
single cross-platform API — this creates leaky abstractions. Instead:

1. Define an interface in `commonMain` describing the **capability** (not the platform API)
2. Implement concretely in native code (Android Kotlin, iOS via Kotlin/Native)
3. Inject the implementation via Koin when initializing the KMP module

```kotlin
// commonMain — the contract
interface HardwareCapability {
    val dataStream: Flow<DataPoint>
    fun start()
    fun stop()
}

// androidMain — platform implementation
class AndroidHardwareCapability(context: Context) : HardwareCapability { ... }

// iosMain — platform implementation
class IosHardwareCapability() : HardwareCapability { ... }

// Wired via platformModule (the only expect/actual)
```

This pattern is documented by Workday's engineering team in their public KMP adoption case study and
is the standard approach at scale.

## BLE / Bluetooth Low Energy

See [BLE_TELEMATICS_PATTERNS.md](references/BLE_TELEMATICS_PATTERNS.md) for full implementation.

**Libraries:**

- **Kable** (JuulLabs): Coroutines-powered BLE. Production-grade. Android, iOS, JS.
- **Nordic BLE Library** (Nordic Semiconductor): Wraps BLEK (Android), native CBCentralManager (
  iOS).

**Architecture:**

```kotlin
// commonMain — OBD-II / IoT device interface
interface DeviceConnector {
    val connectionState: StateFlow<ConnectionState>
    val dataStream: Flow<DeviceDataFrame>
    suspend fun connect(deviceId: String)
    suspend fun sendCommand(command: ByteArray): ByteArray
    fun disconnect()
}

// commonMain — data parsing is PURE KOTLIN (no platform deps)
class ObdPidDecoder {
    fun decode(pid: ObdPid, response: ByteArray): VehicleMetric { ... }
}
```

BLE transport uses Kable in `commonMain`. All data parsing (PID decoding, DTC interpretation, trip
computation) is pure Kotlin shared code.

## GPS / Location Tracking

See [BLE_TELEMATICS_PATTERNS.md](references/BLE_TELEMATICS_PATTERNS.md) for full implementation.

```kotlin
// commonMain
interface LocationProvider {
    val locationUpdates: Flow<Location>
    fun startTracking(config: TrackingConfig)
    fun stopTracking()
}

data class TrackingConfig(
    val intervalMs: Long = 5000,
    val minDistanceMeters: Float = 10f,
    val priority: LocationPriority = LocationPriority.HIGH_ACCURACY,
)

// androidMain: FusedLocationProviderClient + callbackFlow
// iosMain: CLLocationManager + continuation-based wrapper
```

Trip recording, geofence evaluation, distance calculation, and route optimization are pure Kotlin in
`commonMain`. Only the raw location stream requires platform sourcing.

**Library alternative:** Compass (KMP location toolkit for geocoding, geolocation, autocomplete).

## Sensors (Accelerometer, Gyroscope, Magnetometer)

See [BLE_TELEMATICS_PATTERNS.md](references/BLE_TELEMATICS_PATTERNS.md) for full implementation.

```kotlin
// commonMain
interface MotionSensorProvider {
    val accelerometer: Flow<AccelerometerReading>
    val gyroscope: Flow<GyroscopeReading>
    fun start(samplingRate: SamplingRate)
    fun stop()
}

// commonMain — signal processing is pure Kotlin
class DrivingBehaviorAnalyzer(private val sensor: MotionSensorProvider) {
    fun events(): Flow<DrivingEvent> =
        sensor.accelerometer
            .windowed(size = 50, step = 10)
            .map { window -> detectEvent(window) }
            .filter { it != DrivingEvent.Normal }
}
```

Platform implementations: `SensorManager` on Android, `CMMotionManager` on iOS. All signal
processing, FFT, threshold detection, and scoring runs in shared code.

## On-Device Machine Learning

See [ML_INFERENCE_PATTERNS.md](references/ML_INFERENCE_PATTERNS.md) for full implementation.

**Share in commonMain:** Feature extraction, normalization, tokenization, result interpretation,
model version management, A/B test assignment.

**Keep platform-native:** Model loading, tensor creation, inference execution, GPU/NPU delegate
configuration.

```kotlin
// commonMain
interface MLInferenceEngine {
    suspend fun loadModel(name: String)
    suspend fun classify(features: FloatArray): ClassificationResult
    fun close()
}

// androidMain: TensorFlow Lite Interpreter
// iosMain: Core ML VNCoreMLModel
// Alternative: moko-tensorflow for TFLite bindings from commonMain
```

## Camera

```kotlin
// commonMain
interface CameraController {
    val previewFrames: Flow<ImageFrame>
    suspend fun capturePhoto(): ImageData
    fun startPreview()
    fun stopPreview()
}

// androidMain: CameraX + ImageAnalysis
// iosMain: AVCaptureSession + AVCaptureVideoDataOutput
```

**Library:** Camposer provides Compose Multiplatform camera support for both platforms.

For barcode/QR scanning, the detection logic can run in shared code (e.g., rxing for QR via
Kotlin/Native) while camera frame sourcing stays platform-native.

## Audio / Media Playback

```kotlin
// commonMain
interface MediaPlayer {
    val playbackState: StateFlow<PlaybackState>
    val currentPosition: StateFlow<Duration>
    fun play(url: String)
    fun pause()
    fun stop()
    fun seekTo(position: Duration)
}

// androidMain: ExoPlayer / Media3
// iosMain: AVPlayer
```

**Libraries:** KMedia (coroutine-based audio player, Android + iOS), MediaPlayer-KMP (video +
audio + YouTube, all platforms).

Playback state machine, playlist management, and analytics live in `commonMain`. Only the native
player engine stays platform-specific.

## Background Processing

See [OFFLINE_FIRST_PATTERNS.md](references/OFFLINE_FIRST_PATTERNS.md) for full implementation.

```kotlin
// commonMain
interface BackgroundSyncScheduler {
    fun schedulePeriodicSync(intervalMinutes: Long)
    fun scheduleOneTimeSync()
    fun cancelAll()
}

// androidMain: WorkManager (persistent, constraint-aware, survives reboots)
// iosMain: BGTaskScheduler (opportunistic, strict limits, killed on force-quit)
```

**Library alternative:** kmpworkmanager provides a unified API.

## Offline-First Architecture

See [OFFLINE_FIRST_PATTERNS.md](references/OFFLINE_FIRST_PATTERNS.md) for full Store5
implementation.

**Stack:**

- **Local DB:** SQLDelight or Room KMP
- **Sync engine:** Store5 (MutableStore with Updater + Bookkeeper)
- **Conflict resolution:** Last-write-wins → Hybrid Logical Clocks → CRDTs (Synk library)
- **Background sync:** Interface + DI (WorkManager / BGTaskScheduler)

## Platform Permissions

```kotlin
// commonMain
interface PermissionHandler {
    suspend fun requestPermission(permission: AppPermission): PermissionResult
    fun checkPermission(permission: AppPermission): PermissionStatus
}

enum class AppPermission {
    LOCATION_FINE, LOCATION_COARSE, LOCATION_BACKGROUND,
    BLUETOOTH_SCAN, BLUETOOTH_CONNECT,
    CAMERA, MICROPHONE,
    NOTIFICATIONS,
}

sealed interface PermissionResult {
    data object Granted : PermissionResult
    data object Denied : PermissionResult
    data object PermanentlyDenied : PermissionResult
}
```

Platform implementations handle the different permission models (Android runtime permissions, iOS
Info.plist + authorization requests).

## Reference Documents

- [BLE_TELEMATICS_PATTERNS.md](references/BLE_TELEMATICS_PATTERNS.md) — BLE communication, GPS
  tracking, sensor fusion, driving behavior analysis
- [ML_INFERENCE_PATTERNS.md](references/ML_INFERENCE_PATTERNS.md) — On-device ML inference, feature
  engineering, model management
- [OFFLINE_FIRST_PATTERNS.md](references/OFFLINE_FIRST_PATTERNS.md) — Store5, command queue,
  conflict resolution, background sync
