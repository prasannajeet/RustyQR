---
name: ios-code-standards
description: Enforces iOS/Swift code standards — MVI architecture, error handling, concurrency patterns, and SwiftUI conventions.
user-invocable: false
---

# iOS & Swift Code Standards

These standards MUST be followed in all Swift code in `iosApp/`.

---

## 1. Error Handling — Swift 6 Typed Throws

**All functions that can fail MUST use Swift 6 typed throws (`throws(ErrorType)`) with
domain-specific error enums. Never use untyped `throws`, `try!`, or force-unwrap.**

Swift 6's typed throws is the native equivalent of ArrowKT's `Either<Error, Success>` on the Kotlin
side — the compiler enforces which error types a function can produce, and callers get exhaustive
error handling.

### Error Type Definition

```swift
// Define typed errors as enums — one per domain/feature
enum QrError: Error {
    case invalidInput(reason: String)
    case encodingFailed(reason: String)
    case decodingFailed(reason: String)
    case invalidImageData
}

// App-level errors wrap lower-level errors
enum AppError: Error {
    case qr(QrError)
    case network(reason: String)
    case unknown(underlying: Error)
}
```

### Good Code vs Bad Code — Typed Throws

```swift
// BAD: Untyped throws — caller doesn't know what errors to expect
func generateQr(content: String) throws -> UIImage {
    let data = try RustyQrFfi.generatePng(content: content, size: 256)
    return UIImage(data: data)!
}

// BAD: Force unwrapping — crashes the app
func generateQr(content: String) -> UIImage {
    let data = try! RustyQrFfi.generatePng(content: content, size: 256)
    return UIImage(data: data)!
}

// GOOD: Typed throws — compiler enforces error handling
func generateQr(content: String) throws(QrError) -> UIImage {
    guard !content.isEmpty else {
        throw .invalidInput(reason: "content must not be empty")
    }
    let data: Data
    do {
        data = try RustyQrFfi.generatePng(content: content, size: 256)
    } catch {
        throw .encodingFailed(reason: error.localizedDescription)
    }
    guard let image = UIImage(data: data) else {
        throw .invalidImageData
    }
    return image
}
```

### Composing Typed Throws — Error Wrapping Across Layers

```swift
// BAD: Catching generic Error, losing type information
func processQr(content: String) throws -> String {
    let image = try generateQr(content: content) // what errors?
    let decoded = try decodeQr(image: image)      // what errors?
    return decoded
}

// GOOD: Each layer wraps lower-level errors into its own typed error
func processQr(content: String) throws(AppError) -> String {
    let image: UIImage
    do {
        image = try generateQr(content: content)
    } catch {
        throw .qr(error) // QrError wrapped into AppError.qr
    }

    let decoded: String
    do {
        decoded = try decodeQr(image: image)
    } catch {
        throw .qr(error)
    }
    return decoded
}
```

### Typed Throws with Async — Rust FFI Pattern

```swift
// BAD: Untyped async throws
func onGenerate() async throws {
    let data = try await Task.detached { try RustyQrFfi.generatePng(content: text, size: 256) }.value
}

// GOOD: Typed throws with async + proper threading
func generateQrAsync(content: String) async throws(QrError) -> UIImage {
    let data: Data
    do {
        data = try await Task.detached(priority: .userInitiated) {
            try RustyQrFfi.generatePng(content: content, size: 256)
        }.value
    } catch {
        throw .encodingFailed(reason: error.localizedDescription)
    }
    guard let image = UIImage(data: data) else {
        throw .invalidImageData
    }
    return image
}
```

### Consuming Typed Errors in ViewModel

```swift
// BAD: Generic catch with no type safety
do {
    let image = try await generateQr(content: text)
    state = state.with(resultImage: image)
} catch {
    state = state.with(error: .some(error.localizedDescription)) // what was the error?
}

// GOOD: Typed catch — exhaustive handling of each error case
do {
    let image = try await generateQrAsync(content: state.inputText)
    state = state.with(resultImage: image, isLoading: false)
} catch {
    // `error` is guaranteed to be QrError — compiler enforced
    let message: String
    switch error {
    case .invalidInput(let reason):
        message = "Invalid input: \(reason)"
    case .encodingFailed(let reason):
        message = "QR generation failed: \(reason)"
    case .decodingFailed(let reason):
        message = "QR decoding failed: \(reason)"
    case .invalidImageData:
        message = "Generated image data was invalid"
    }
    state = state.with(isLoading: false, error: .some(message))
}
```

### Result Type — When to Use Instead of Typed Throws

Use `Result<Success, Failure>` when you need to **store** or **pass** a result value (e.g., in
state, collections, callbacks). Use typed throws for **control flow**.

```swift
// Store result in state — Result is appropriate here
struct ScanScreenState {
    let scanResult: Result<String, QrError>?
}

// Convert between typed throws and Result
let result: Result<UIImage, QrError> = Result { try generateQr(content: text) }

// Consume Result with switch
switch result {
case .success(let image):
    state = state.with(resultImage: image)
case .failure(let error):
    state = state.with(error: .some(error.toUserMessage()))
}
```

### Rules

- **NEVER** use untyped `throws` — always `throws(SpecificErrorType)`
- **NEVER** force-unwrap (`!`) or `try!` — crashes the app
- **NEVER** catch generic `Error` and discard type info — wrap into your domain error
- **DO** define error enums per feature/domain (`QrError`, `AppError`)
- **DO** use `throws(ErrorType)` for control flow, `Result<T, ErrorType>` for storage
- **DO** wrap lower-level errors at layer boundaries (`throw .qr(error)`)
- **DO** handle each error case exhaustively in the ViewModel — no generic "something went wrong"
- UniFFI maps Rust `QrError` to a Swift enum conforming to `Error` — wrap it in your `AppError` at
  the boundary

---

## 2. Threading & Concurrency

**Rust FFI calls are synchronous and MUST run off the main thread.**

```swift
// BAD: Blocking the main thread with synchronous Rust call

@MainActor
func onGenerateTapped() {
    let data = RustyQrFfi.generatePng(content: inputText, size: 256) // blocks UI
    qrImage = UIImage(data: data)
}

// GOOD: Detached task for synchronous Rust work, update UI on main

func onGenerateTapped() async {
    state = state.with(isLoading: true)
    do {
        let data = try await Task.detached(priority: .userInitiated) {
            try RustyQrFfi.generatePng(content: self.inputText, size: 256)
        }.value
        await MainActor.run {
            state = state.with(resultImage: UIImage(data: data), isLoading: false)
        }
    } catch {
        await MainActor.run {
            state = state.with(error: .some(error.localizedDescription), isLoading: false)
        }
    }
}
```

### Rules

- **`Task.detached(priority: .userInitiated)`** for synchronous Rust/FFI calls — does NOT inherit
  actor context
- **`Task { }`** inherits actor context — use for work that needs MainActor
- **`await MainActor.run { }`** to update state from detached tasks
- **Capture values** in closures to avoid accessing `state` from detached task context
- **`[weak self]`** in async callbacks to avoid retain cycles

---

## 3. MVI Architecture

**All iOS UI follows MVI (Model-View-Intent). This matches the Kotlin/Android side exactly, ensuring
the same data flow pattern across both platforms.**

### End-to-End Flow

```
┌──────────────────────────────────────────────────┐
│  View (SwiftUI)                                   │
│  Renders state. Emits intents. NO logic.          │
│                                                   │
│  Intent ──→ ViewModel.send() ──→ Rust FFI call    │
│                                                   │
│  State  ←── ViewModel ←── Result from Rust        │
│    ↓                                              │
│  View re-renders                                  │
└──────────────────────────────────────────────────┘
```

### What Lives Where

| Component       | Location                | Responsibility                                | Can Import                                                |
|-----------------|-------------------------|-----------------------------------------------|-----------------------------------------------------------|
| **State**       | `<Name>State.swift`     | Immutable value type holding all screen data  | Foundation only                                           |
| **Intent**      | `<Name>State.swift`     | Enum of every user action                     | Foundation only                                           |
| **ViewModel**   | `<Name>ViewModel.swift` | Processes intents, calls Rust, updates state  | State, Intent, RustyQrFfi, Foundation                     |
| **View**        | `<Name>View.swift`      | Renders state, emits intents                  | SwiftUI, State, Intent only — **NOT** ViewModel internals |
| **ContentView** | `<Name>View.swift`      | Pure render function (no ViewModel, testable) | SwiftUI, State, Intent only                               |

### State — Immutable value types (structs)

```swift
// BAD: Mutable class-based state with scattered @Published properties
class GenerateViewModel: ObservableObject {
    @Published var inputText = ""
    @Published var qrImage: UIImage?
    @Published var isLoading = false
    @Published var errorMessage: String?

    func generate() {
        isLoading = true   // multiple mutations, no single state update
        errorMessage = nil
    }
}

// GOOD: Immutable struct state, single @Published, explicit intents
struct GenerateScreenState {
    let inputText: String
    let resultImage: UIImage?
    let isLoading: Bool
    let error: String?

    static let initial = GenerateScreenState(
        inputText: "", resultImage: nil, isLoading: false, error: nil
    )

    // Copy-with-modification helpers (Swift has no data class copy())
    func with(
        inputText: String? = nil,
        resultImage: UIImage?? = nil,
        isLoading: Bool? = nil,
        error: String?? = nil
    ) -> GenerateScreenState {
        GenerateScreenState(
            inputText: inputText ?? self.inputText,
            resultImage: resultImage ?? self.resultImage,
            isLoading: isLoading ?? self.isLoading,
            error: error ?? self.error
        )
    }
}
```

### Intent — Enum-based, one case per user action

```swift
// BAD: Callbacks and closures instead of explicit intents
struct GenerateView: View {
    var onTextChanged: (String) -> Void
    var onGenerate: () -> Void
    var onClear: () -> Void
}

// GOOD: Sealed enum — every action is explicit and exhaustive
enum GenerateIntent {
    case updateText(String)
    case generate
    case clearError
}
```

### ViewModel — @MainActor, single @Published state, send() method

```swift
// BAD: Multiple @Published properties, no intent dispatch
class GenerateViewModel: ObservableObject {
    @Published var text = ""
    @Published var image: UIImage?

    func generate() { ... }        // direct method calls
    func updateText(_ t: String) { ... }
}

// GOOD: Single state, single entry point, exhaustive intent handling
@MainActor
final class GenerateViewModel: ObservableObject {
    @Published private(set) var state = GenerateScreenState.initial

    func send(_ intent: GenerateIntent) {
        switch intent {
        case .updateText(let text):
            state = state.with(inputText: text)
        case .generate:
            handleGenerate()
        case .clearError:
            state = state.with(error: .some(nil))
        }
    }

    private func handleGenerate() {
        state = state.with(isLoading: true, error: .some(nil))
        Task.detached(priority: .userInitiated) { [inputText = state.inputText] in
            do {
                let data = try RustyQrFfi.generatePng(content: inputText, size: 256)
                let image = UIImage(data: data)
                await MainActor.run { [weak self] in
                    self?.state = self?.state.with(resultImage: image, isLoading: false) ?? .initial
                }
            } catch {
                let message: String
                switch error {
                case .invalidInput(let reason): message = "Invalid input: \(reason)"
                case .encodingFailed(let reason): message = "Encoding failed: \(reason)"
                default: message = error.localizedDescription
                }
                await MainActor.run { [weak self] in
                    self?.state = self?.state.with(isLoading: false, error: .some(message)) ?? .initial
                }
            }
        }
    }
}
```

### Side Effects — Where They Go

```swift
// BAD: Side effect in the view
Button("Generate") {
    let data = try? RustyQrFfi.generatePng(content: text, size: 256) // side effect in view!
}

// BAD: Side effect in state copy
func with(isLoading: Bool) -> State {
    print("Loading state changed") // side effect in state!
    return State(...)
}

// GOOD: Side effects ONLY in ViewModel's intent handler methods
private func handleGenerate() {
    // 1. Update state to loading (pure)
    state = state.with(isLoading: true)
    // 2. Side effect: call Rust (in detached task)
    Task.detached(priority: .userInitiated) { ... }
    // 3. Update state with result (pure, on MainActor)
}
```

---

## 4. SwiftUI Views — Render state, emit intents

```swift
// BAD: Logic and state management in the view
struct GenerateView: View {
    @State private var text = ""
    @State private var image: UIImage?

    var body: some View {
        VStack {
            TextField("Enter text", text: $text)
            Button("Generate") {
                // BAD: Direct Rust call in view
                let data = try? RustyQrFfi.generatePng(content: text, size: 256)
                image = data.flatMap { UIImage(data: $0) }
            }
        }
    }
}

// GOOD: Split into wiring view + pure content view
struct GenerateView: View {
    @StateObject private var viewModel = GenerateViewModel()

    var body: some View {
        GenerateContentView(
            state: viewModel.state,
            onIntent: { viewModel.send($0) }
        )
    }
}

// Pure render — receives state, emits intents, testable without ViewModel
struct GenerateContentView: View {
    let state: GenerateScreenState
    let onIntent: (GenerateIntent) -> Void

    var body: some View {
        VStack {
            TextField("Enter text", text: Binding(
                get: { state.inputText },
                set: { onIntent(.updateText($0)) }
            ))
            Button("Generate") {
                onIntent(.generate)
            }
            .disabled(state.isLoading)

            if let image = state.resultImage {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
            }
            if let error = state.error {
                Text(error).foregroundColor(.red)
            }
        }
    }
}
```

### MVI Rules

- **State**: Immutable struct with `let` properties. Single `static let initial`. Copy via `with()`
  helpers.
- **Intent**: Enum with one case per user action. No closures, no callbacks, no delegate methods.
- **ViewModel**: `@MainActor final class`, `@Published private(set) var state`, single
  `send(_ intent:)` entry point.
- **Side effects**: ONLY in ViewModel intent handler methods. Never in State, View, or Intent.
- **View split**: `<Name>View` (owns ViewModel via `@StateObject`) + `<Name>ContentView` (pure
  render, testable).
- `Binding(get:set:)` to bridge SwiftUI's Binding requirement with MVI intent pattern.
- `@StateObject` (not `@ObservedObject`) when the View owns the ViewModel lifecycle.
- **NO** business logic in views — only render state and emit intents.
- **NO** direct Rust/FFI calls outside ViewModel — everything goes through `send()`.

---

## 5. Data Handling

```swift
// BAD: Manual byte buffer management

func decodeQr(bytes: [UInt8]) -> String {
    let pointer = UnsafePointer(bytes)
    // ... unsafe manipulation

    // GOOD: Use Data consistently (UniFFI maps Vec<u8> to Data)
    func decodeQr(imageData: Data) throws -> String {
        return try RustyQrFfi.decode(imageData: imageData)
    }
```

### Rules

- **`Data`** for all byte buffers from Rust (UniFFI maps `Vec<u8>` to `Data`)
- **Never** use `UnsafePointer` or `[UInt8]` for FFI data
- **`UIImage(data:)`** to convert PNG bytes to images

---

## 6. KMM Interop

```swift
// BAD: Mixing KMM and Rust calls in the same view hierarchy

struct ContentView: View {
    var body: some View {
        ComposeView().onAppear {
            let version = RustyQrFfi.getLibraryVersion() // tangled concerns
        }
    }
}

// GOOD: Clear separation — ComposeView for KMM, dedicated views for Rust features

struct ContentView: View {
    @StateObject private var viewModel = GenerateViewModel()

    var body: some View {
        NavigationStack {
            GenerateView(
                state: viewModel.state,
                onIntent: {
                    viewModel.send($0)
                }
            )
        }
    }
}
```

---

## 7. Linting

- SwiftLint must pass: `swiftlint lint --path iosApp/`
- `trailing_whitespace` disabled
- `line_length` disabled
- Follow Swift API Design Guidelines (clear naming, fluent usage)
