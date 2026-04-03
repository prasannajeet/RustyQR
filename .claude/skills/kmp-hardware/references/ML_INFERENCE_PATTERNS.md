# On-Device ML Inference Patterns for KMP

## Architecture Overview

```
┌─────────────────────────────────────────────────┐
│                  commonMain                      │
│  ┌──────────────┐  ┌─────────────────────────┐  │
│  │ Feature      │  │ MLInferenceEngine       │  │
│  │ Extraction   │  │ (interface)             │  │
│  │ (pure Kotlin)│  └─────────────────────────┘  │
│  └──────────────┘  ┌─────────────────────────┐  │
│  ┌──────────────┐  │ Result Interpretation   │  │
│  │ Model Version│  │ (pure Kotlin)           │  │
│  │ Management   │  └─────────────────────────┘  │
│  └──────────────┘                                │
├──────────────────────┬──────────────────────────┤
│     androidMain      │       iosMain             │
│  ┌────────────────┐  │  ┌──────────────────┐    │
│  │ TFLite         │  │  │ Core ML          │    │
│  │ Interpreter    │  │  │ VNCoreMLModel    │    │
│  └────────────────┘  │  └──────────────────┘    │
│  ┌────────────────┐  │  ┌──────────────────┐    │
│  │ ML Kit         │  │  │ Vision           │    │
│  │ MediaPipe      │  │  │ Create ML        │    │
│  └────────────────┘  │  └──────────────────┘    │
└──────────────────────┴──────────────────────────┘
```

## Inference Engine Interface

```kotlin
// commonMain
interface MLInferenceEngine {
    suspend fun loadModel(modelName: String)
    suspend fun classify(features: FloatArray): ClassificationResult
    suspend fun detectObjects(imageData: ByteArray): List<Detection>
    fun isLoaded(): Boolean
    fun close()
}

data class ClassificationResult(
    val label: String,
    val confidence: Float,
    val allScores: Map<String, Float>,
)

data class Detection(
    val label: String,
    val confidence: Float,
    val boundingBox: BoundingBox,
)

data class BoundingBox(
    val left: Float, val top: Float,
    val right: Float, val bottom: Float,
)
```

## Android Implementation (TensorFlow Lite)

```kotlin
// androidMain
class TfLiteInferenceEngine(
    private val context: Context,
) : MLInferenceEngine {

    private var interpreter: Interpreter? = null

    override suspend fun loadModel(modelName: String) {
        withContext(Dispatchers.IO) {
            val modelBuffer = context.assets.open("$modelName.tflite").use {
                val bytes = it.readBytes()
                ByteBuffer.allocateDirect(bytes.size).apply {
                    order(ByteOrder.nativeOrder())
                    put(bytes)
                    rewind()
                }
            }
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                // Enable GPU delegate if available
                try {
                    addDelegate(GpuDelegate())
                } catch (e: Exception) {
                    // Fallback to CPU
                }
            }
            interpreter = Interpreter(modelBuffer, options)
        }
    }

    override suspend fun classify(features: FloatArray): ClassificationResult {
        return withContext(Dispatchers.Default) {
            val input = arrayOf(features)
            val output = Array(1) { FloatArray(NUM_CLASSES) }
            interpreter!!.run(input, output)

            val scores = output[0]
            val maxIndex = scores.indices.maxBy { scores[it] }
            ClassificationResult(
                label = LABELS[maxIndex],
                confidence = scores[maxIndex],
                allScores = LABELS.zip(scores.toList()).toMap(),
            )
        }
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
    }
}
```

## iOS Implementation (Core ML)

```kotlin
// iosMain
class CoreMLInferenceEngine : MLInferenceEngine {

    private var model: MLModel? = null

    override suspend fun loadModel(modelName: String) {
        withContext(Dispatchers.Default) {
            val bundle = NSBundle.mainBundle
            val modelUrl = bundle.URLForResource(modelName, withExtension = "mlmodelc")
                ?: throw IllegalStateException("Model $modelName not found in bundle")
            model = MLModel.modelWithContentsOfURL(modelUrl, error = null)
        }
    }

    override suspend fun classify(features: FloatArray): ClassificationResult {
        return withContext(Dispatchers.Default) {
            val mlArray = try {
                MLMultiArray(
                    shape = nsArrayOf(NSNumber(features.size)),
                    dataType = MLMultiArrayDataType.MLMultiArrayDataTypeFloat32,
                    error = null,
                )!!
            } catch (e: Exception) {
                throw IllegalStateException("Failed to create MLMultiArray: ${e.message}")
            }

            features.forEachIndexed { i, value ->
                mlArray.setObject(NSNumber(value), forKey = NSNumber(i))
            }

            val input = MLDictionaryFeatureProvider(
                dictionary = mapOf("input" to mlArray),
                error = null,
            )!!

            val prediction = model!!.predictionFromFeatures(input, error = null)!!
            // Parse prediction output — model-specific
            parseClassificationOutput(prediction)
        }
    }
}
```

## Feature Engineering (Pure Kotlin — commonMain)

```kotlin
// commonMain — all preprocessing is shared across platforms
class TelematicsFeatureExtractor {

    fun extractDrivingFeatures(
        accelerometerWindow: List<AccelerometerReading>,
        gpsWindow: List<Location>,
    ): FloatArray {
        val accelMagnitudes = accelerometerWindow.map {
            sqrt(it.x * it.x + it.y * it.y + it.z * it.z)
        }

        return floatArrayOf(
            // Accelerometer features
            accelMagnitudes.average().toFloat(),
            accelMagnitudes.max().toFloat(),
            accelMagnitudes.min().toFloat(),
            accelMagnitudes.standardDeviation().toFloat(),
            accelerometerWindow.map { it.x }.standardDeviation().toFloat(), // lateral variance
            accelerometerWindow.map { it.y }.standardDeviation().toFloat(), // longitudinal variance

            // GPS features
            gpsWindow.map { it.speed }.average().toFloat(),
            gpsWindow.map { it.speed }.max().toFloat(),
            gpsWindow.speedVariance().toFloat(),
            gpsWindow.maxDeceleration().toFloat(),
            gpsWindow.averageBearing().toFloat(),
            gpsWindow.bearingVariance().toFloat(),

            // Derived features
            gpsWindow.stopsCount().toFloat(),
            gpsWindow.idleTimeRatio().toFloat(),
        )
    }

    fun normalizeFeatures(features: FloatArray, means: FloatArray, stds: FloatArray): FloatArray {
        return FloatArray(features.size) { i ->
            if (stds[i] != 0f) (features[i] - means[i]) / stds[i] else 0f
        }
    }
}

// Extension functions for GPS feature extraction
private fun List<Location>.speedVariance(): Double {
    val speeds = map { it.speed }
    val mean = speeds.average()
    return speeds.map { (it - mean).pow(2) }.average()
}

private fun List<Location>.maxDeceleration(): Double {
    if (size < 2) return 0.0
    return zipWithNext { a, b ->
        val dt = (b.timestamp - a.timestamp) / 1000.0
        if (dt > 0) (b.speed - a.speed) / dt else 0.0
    }.min()
}
```

## Model Version Management (commonMain)

```kotlin
// commonMain
class ModelManager(
    private val api: ModelApi,
    private val storage: ModelStorage,
    private val settings: Settings,
) {
    suspend fun ensureLatestModel(modelName: String): ModelInfo {
        val remoteVersion = api.getLatestVersion(modelName)
        val localVersion = settings.getIntOrNull("model_version_$modelName")

        return if (localVersion == null || remoteVersion > localVersion) {
            val modelBytes = api.downloadModel(modelName, remoteVersion)
            storage.saveModel(modelName, modelBytes)
            settings.putInt("model_version_$modelName", remoteVersion)
            ModelInfo(modelName, remoteVersion, isUpdated = true)
        } else {
            ModelInfo(modelName, localVersion, isUpdated = false)
        }
    }
}

// A/B test assignment for model variants
class ModelABTest(
    private val settings: Settings,
) {
    fun getModelVariant(experimentId: String): String {
        val existingAssignment = settings.getStringOrNull("ab_$experimentId")
        if (existingAssignment != null) return existingAssignment

        // Deterministic assignment based on device ID hash
        val variant = if (settings.getString("device_id", "").hashCode() % 2 == 0) {
            "model_v1"
        } else {
            "model_v2"
        }
        settings.putString("ab_$experimentId", variant)
        return variant
    }
}
```

## Alternative: moko-tensorflow (TFLite from commonMain)

```kotlin
// commonMain — if you want a unified TFLite API
// Dependency: dev.icerock.moko:tensorflow
class MokoTfLiteEngine : MLInferenceEngine {
    private var interpreter: TensorflowInterpreter? = null

    override suspend fun loadModel(modelName: String) {
        // moko-tensorflow provides a common API
        interpreter = TensorflowInterpreter(modelName)
    }

    override suspend fun classify(features: FloatArray): ClassificationResult {
        val output = interpreter!!.run(features)
        // Parse output
        return parseOutput(output)
    }
}
```

Note: moko-tensorflow covers text classification and basic inference. For vision tasks requiring
Core ML acceleration or ML Kit features, the interface + DI pattern is required.
