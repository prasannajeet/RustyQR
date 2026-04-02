---
name: android-code-standards
description: Enforces Android/KMM code standards — MVI architecture, ArrowKT Either for error handling, KMM conventions, and Compose Multiplatform patterns.
user-invocable: false
---

# Android & KMM Code Standards

These standards MUST be followed in all Kotlin code across `commonMain`, `androidMain`, and
`iosMain` source sets.

---

## 1. Error Handling — ArrowKT Either

**All operations that can fail MUST return `Either<ErrorType, SuccessType>` instead of throwing
exceptions.**

### Dependency

```toml
# gradle/libs.versions.toml
[versions]
arrow = "2.2.1.1"

[libraries]
arrow-core = { module = "io.arrow-kt:arrow-core", version.ref = "arrow" }
```

```kotlin
// composeApp/build.gradle.kts — in commonMain.dependencies
commonMain.dependencies {
    implementation(libs.arrow.core)
}
```

### Core Pattern — Either<Left, Right>

`Left` = error, `Right` = success. Arrow is **right-biased** — `map`, `flatMap`, and `bind()`
operate on the Right value.

```kotlin
import arrow.core.Either
import arrow.core.left
import arrow.core.right

// Define typed errors as sealed hierarchy
sealed interface QrError {
    data class InvalidInput(val reason: String) : QrError
    data class EncodingFailed(val reason: String) : QrError
    data class DecodingFailed(val reason: String) : QrError
}

// Functions return Either — never throw for domain errors
fun generateQr(content: String, size: Int): Either<QrError, ByteArray> {
    if (content.isBlank()) {
        return QrError.InvalidInput("content must not be blank").left()
    }
    return try {
        val bytes = QrBridge.generateQrPng(content, size)
        bytes.right()
    } catch (e: Exception) {
        QrError.EncodingFailed(e.message ?: "unknown").left()
    }
}
```

### Good Code vs Bad Code — Either

```kotlin
// BAD: Throwing exceptions for domain errors
fun generateQr(content: String, size: Int): ByteArray {
    if (content.isBlank()) throw IllegalArgumentException("content blank")
    return QrBridge.generateQrPng(content, size) // can throw
}

// BAD: Kotlin Result type (untyped error — just Throwable)
fun generateQr(content: String, size: Int): Result<ByteArray> {
    return runCatching { QrBridge.generateQrPng(content, size) }
}

// GOOD: Either with typed error — caller knows EXACTLY what can go wrong
fun generateQr(content: String, size: Int): Either<QrError, ByteArray> = either {
    ensure(content.isNotBlank()) { QrError.InvalidInput("content must not be blank") }
    catch({ QrBridge.generateQrPng(content, size) }) { e: Exception ->
        raise(QrError.EncodingFailed(e.message ?: "unknown"))
    }
}
```

### Composing Either — the `either {}` DSL with `bind()`

```kotlin
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.catch

// BAD: Nested when/fold chains
fun processQr(content: String): Either<QrError, UIResult> {
    val bytesResult = generateQr(content, 256)
    return when (bytesResult) {
        is Either.Left -> bytesResult
        is Either.Right -> {
            val decodeResult = decodeQr(bytesResult.value)
            when (decodeResult) {
                is Either.Left -> decodeResult
                is Either.Right -> UIResult(decodeResult.value).right()
            }
        }
    }
}

// GOOD: either {} block with bind() — flat, readable, fail-first
fun processQr(content: String): Either<QrError, UIResult> = either {
    val bytes = generateQr(content, 256).bind()       // short-circuits on Left
    val decoded = decodeQr(bytes).bind()               // short-circuits on Left
    UIResult(decoded)                                   // Right is implicit
}
```

### Consuming Either in ViewModel — fold()

```kotlin
// BAD: Pattern matching with when (verbose, error-prone)
when (val result = generateQr(content, 256)) {
    is Either.Left -> _state.update { it.copy(error = result.value.toString()) }
    is Either.Right -> _state.update { it.copy(qrImage = decode(result.value)) }
}

// GOOD: fold() — exhaustive, concise
generateQr(content, 256).fold(
    ifLeft = { error ->
        _state.update { it.copy(error = error.toUserMessage(), isLoading = false) }
    },
    ifRight = { bytes ->
        val bitmap = bytes.toImageBitmap()
        _state.update { it.copy(qrImage = bitmap, isLoading = false) }
    },
)
```

### Either in ViewModel — full pattern

```kotlin
class GenerateViewModel : ViewModel() {
    private val _state = MutableStateFlow(GenerateScreenState())
    val state: StateFlow<GenerateScreenState> = _state.asStateFlow()

    fun onIntent(intent: GenerateIntent) {
        when (intent) {
            is GenerateIntent.UpdateText -> _state.update { it.copy(inputText = intent.text) }
            is GenerateIntent.Generate -> generate()
            is GenerateIntent.ClearError -> _state.update { it.copy(error = null) }
        }
    }

    private fun generate() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val result = withContext(Dispatchers.IO) {
                generateQr(state.value.inputText, 256)
            }
            result.fold(
                ifLeft = { error ->
                    _state.update { it.copy(error = error.toUserMessage(), isLoading = false) }
                },
                ifRight = { bytes ->
                    _state.update { it.copy(qrImage = bytes.toImageBitmap(), isLoading = false) }
                },
            )
        }
    }
}
```

### Rules

- **NEVER** throw exceptions for domain/business errors — use `Either.left()`
- **NEVER** use Kotlin's `Result<T>` — it erases error types to `Throwable`
- **DO** use `either {}` DSL with `.bind()` to compose operations
- **DO** use `fold()` to consume Either at the ViewModel/UI boundary
- **DO** use `ensure()` for precondition checks inside `either {}` blocks
- **DO** use `catch()` to wrap foreign code that throws into Either
- **OK** to throw for truly exceptional/programmer errors (null assertion on non-null, index OOB on
  trusted data)
- Typed error sealed hierarchies live alongside their feature (e.g., `QrError` in the QR module)

---

## 2. MVI Architecture

**All UI follows MVI (Model-View-Intent). This is the same pattern used on iOS (SwiftUI) and mirrors
the event-based architecture in Rust. The entire stack is predictable: Intent → Command → Rust pure
function → Response → State → View.**

### End-to-End Flow

```
┌──────────────────────────────────────────────────────────┐
│  View (@Composable)                                       │
│  Renders state. Emits intents. NO logic.                  │
│                                                           │
│  Intent ──→ ViewModel.onIntent() ──→ QrBridge (FFI call)  │
│                                                           │
│  State  ←── ViewModel ←── Either<Error, Result> from Rust │
│    ↓                                                      │
│  View re-renders                                          │
└──────────────────────────────────────────────────────────┘
```

### What Lives Where

| Component     | Location                            | Responsibility                                   | Can Import                                      |
|---------------|-------------------------------------|--------------------------------------------------|-------------------------------------------------|
| **State**     | `<Name>ScreenState.kt` (commonMain) | Immutable data class holding all screen data     | Kotlin stdlib, Compose ImageBitmap only         |
| **Intent**    | `<Name>Intent.kt` (commonMain)      | Sealed interface of every user action            | Kotlin stdlib only                              |
| **ViewModel** | `<Name>ViewModel.kt` (commonMain)   | Processes intents, calls QrBridge, updates state | State, Intent, QrBridge, Coroutines, ArrowKT    |
| **Screen**    | `<Name>Screen.kt` (commonMain)      | Wires ViewModel to Content + collects StateFlow  | ViewModel, Compose, Lifecycle                   |
| **Content**   | `<Name>Screen.kt` (commonMain)      | Pure render function (no ViewModel, previewable) | State, Intent, Compose only — **NOT** ViewModel |

### Side Effects — Where They Go

```kotlin
// BAD: Side effect in composable
Button(onClick = {
    val bytes = QrBridge.generateQrPng(text, 256) // side effect in view!
})

// BAD: Side effect in state copy
data class State(val count: Int) {
    fun copy(count: Int): State {
        println("State changed") // side effect in state!
        return State(count)
    }
}

// GOOD: Side effects ONLY in ViewModel's intent handler, wrapped in IO
private fun generate() {
    viewModelScope.launch {
        _state.update { it.copy(isLoading = true) }         // 1. Pure state update
        val result = withContext(Dispatchers.IO) {            // 2. Side effect on IO
            generateQr(state.value.inputText, 256)
        }
        result.fold(                                          // 3. Pure state update
            ifLeft = { _state.update { s -> s.copy(error = it.toUserMessage()) } },
            ifRight = { _state.update { s -> s.copy(qrImage = it.toImageBitmap()) } },
        )
    }
}
```

### State — Immutable data classes

```kotlin
// BAD: Mutable state, multiple sources of truth
class GenerateViewModel {
    var qrText by mutableStateOf("")
    var qrImage: ImageBitmap? = null
    var isLoading = false
    var errorMessage: String? = null
}

// GOOD: Immutable state data class, single StateFlow
data class GenerateScreenState(
    val inputText: String = "",
    val qrImage: ImageBitmap? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)
```

### Intent — Sealed interfaces

```kotlin
sealed interface GenerateIntent {
    data class UpdateText(val text: String) : GenerateIntent
    data object Generate : GenerateIntent
    data object ClearError : GenerateIntent
}
```

### Composables — Render state, emit intents, NO logic

```kotlin
// BAD: Business logic and side effects inside composable
@Composable
fun GenerateScreen() {
    var text by remember { mutableStateOf("") }
    Button(onClick = {
        val pngBytes = QrBridge.generateQrPng(text, 256) // BAD: Rust call in composable
    }) { Text("Generate") }
}

// GOOD: Composable only renders state and emits intents
@Composable
fun GenerateScreen(
    state: GenerateScreenState,
    onIntent: (GenerateIntent) -> Unit,
) {
    Column {
        TextField(
            value = state.inputText,
            onValueChange = { onIntent(GenerateIntent.UpdateText(it)) },
        )
        Button(
            onClick = { onIntent(GenerateIntent.Generate) },
            enabled = !state.isLoading,
        ) { Text("Generate") }
        state.qrImage?.let { Image(bitmap = it, contentDescription = "QR Code") }
        state.error?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
    }
}
```

### MVI Rules

- **State**: Immutable `data class` with `val` properties. Single `MutableStateFlow`, exposed as
  `StateFlow`.
- **Intent**: Sealed interface with one `data class`/`data object` per user action. No lambdas, no
  callbacks.
- **ViewModel**: Extends `ViewModel()`, single `onIntent()` entry point, exhaustive `when` on
  intents.
- **Side effects**: ONLY in ViewModel intent handlers, wrapped in `withContext(Dispatchers.IO)`.
  Never in State, Composables, or Intents.
- **Screen split**: `<Name>Screen` (owns ViewModel via `viewModel { }`) + `<Name>Content` (pure
  render, previewable).
- **NO** business logic in composables — only render state and emit intents.
- **NO** direct Rust/FFI calls outside ViewModel — everything goes through `onIntent()`.
- **NO** `mutableStateOf` or `remember` for screen-level state — use ViewModel's StateFlow.

---

## 3. Threading

```kotlin
// BAD: Blocking the main thread with Rust call
fun onGenerate() {
    val bytes = QrBridge.generateQrPng(state.value.inputText, 256)
    _state.update { it.copy(qrImage = decodeBitmap(bytes)) }
}

// GOOD: Dispatched to IO
private fun generate() {
    viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        val result = withContext(Dispatchers.IO) {
            generateQr(state.value.inputText, 256) // returns Either
        }
        result.fold(
            ifLeft = { _state.update { s -> s.copy(error = it.toUserMessage(), isLoading = false) } },
            ifRight = { _state.update { s -> s.copy(qrImage = it.toImageBitmap(), isLoading = false) } },
        )
    }
}
```

---

## 4. KMM Conventions

### expect/actual file naming

```kotlin
// BAD: Actual file named after the class
// File: androidMain/kotlin/.../QrBridge.kt  <-- wrong
actual object QrBridge { ... }

// GOOD: Actual file named with platform suffix
// File: androidMain/kotlin/.../QrBridge.android.kt  <-- correct
actual object QrBridge { ... }
```

### QrBridge — UniFFI type conversion in platform layer only

```kotlin
// BAD: Leaking UniFFI types into common code
// commonMain
expect object QrBridge {
    fun generateQrPng(content: String, size: Int): List<UByte> // UniFFI type leaked
}

// GOOD: Common declares ByteArray, actual handles conversion
// commonMain/QrBridge.kt
expect object QrBridge {
    fun generateQrPng(content: String, size: Int): ByteArray
}

// androidMain/QrBridge.android.kt
actual object QrBridge {
    actual fun generateQrPng(content: String, size: Int): ByteArray {
        val ubyteList: List<UByte> = RustyQrFfi.generatePng(content, size.toUInt())
        return ubyteList.toUByteArray().asByteArray()
    }
}
```

---

## 5. Linting & Formatting

- Max line length: 120 characters
- Continuation indent: 4 (not 8) per `.editorconfig`
- `@Composable` functions exempt from function naming rules
- `MagicNumber` and `WildcardImport` detekt rules disabled
- All linters must pass: `./gradlew :composeApp:lintAll`
