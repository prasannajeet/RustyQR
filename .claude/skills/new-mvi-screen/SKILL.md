---
name: new-mvi-screen
description: Scaffold a new MVI screen with State, Intent, ViewModel, and View for Compose Multiplatform (Kotlin) and optionally SwiftUI.
user-invocable: false
---

# New MVI Screen

Scaffold a complete MVI screen following project conventions for both platforms.

## Input

The invoking agent provides: screen name (e.g., `Generate`, `Scan`) and a brief description of the
screen's purpose.

## Kotlin (Compose Multiplatform) — commonMain

### 1. State data class

`composeApp/src/commonMain/kotlin/com/p2/apps/rustyqr/<Name>ScreenState.kt`:

```kotlin
package com.p2.apps.rustyqr

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Immutable state for the <Name> screen.
 */
data class <Name>ScreenState(
    // Input fields
    val inputText: String = "",
    // Output / result fields
    val resultImage: ImageBitmap? = null,
    // Loading / error
    val isLoading: Boolean = false,
    val error: String? = null,
)
```

### 2. Intent sealed interface

`composeApp/src/commonMain/kotlin/com/p2/apps/rustyqr/<Name>Intent.kt`:

```kotlin
package com.p2.apps.rustyqr

/**
 * User intents for the <Name> screen.
 * Each sealed variant represents one user action.
 */
sealed interface <Name>Intent {
    data class UpdateText(val text: String) : <Name>Intent
    data object Submit : <Name>Intent
    data object ClearError : <Name>Intent
}
```

### 3. ViewModel

`composeApp/src/commonMain/kotlin/com/p2/apps/rustyqr/<Name>ViewModel.kt`:

```kotlin
package com.p2.apps.rustyqr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Processes [<Name>Intent]s and updates [<Name>ScreenState].
 * All side effects (Rust calls, I/O) run on [Dispatchers.IO].
 */
class <Name>ViewModel : ViewModel() {

    private val _state = MutableStateFlow(<Name>ScreenState())
    val state: StateFlow<<Name>ScreenState> = _state.asStateFlow()

    fun onIntent(intent: <Name>Intent) {
        when (intent) {
            is <Name>Intent.UpdateText -> {
                _state.update { it.copy(inputText = intent.text) }
            }
            is <Name>Intent.Submit -> handleSubmit()
            is <Name>Intent.ClearError -> {
                _state.update { it.copy(error = null) }
            }
        }
    }

    private fun handleSubmit() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val result = withContext(Dispatchers.IO) {
                    // TODO: Call QrBridge.<method> here
                    // Rust calls are synchronous and must be off main thread
                }
                _state.update { it.copy(/* update with result */, isLoading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }
}
```

**Key points:**

- ViewModel lives in `commonMain` — shared across Android and iOS
- Uses `androidx.lifecycle.ViewModel` from `lifecycle-viewmodel-compose:2.10.0`
- `viewModelScope` uses `Dispatchers.Main.immediate` by default
- All Rust/native calls wrapped in `withContext(Dispatchers.IO)`
- State updates via `_state.update { it.copy(...) }` — never mutate directly

### 4. Screen composable

`composeApp/src/commonMain/kotlin/com/p2/apps/rustyqr/<Name>Screen.kt`:

```kotlin
package com.p2.apps.rustyqr

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * <Name> screen — <brief description of what this screen does>.
 *
 * Renders [<Name>ScreenState] and emits [<Name>Intent] actions.
 * Contains NO business logic — all processing happens in [<Name>ViewModel].
 */
@Composable
fun <Name>Screen(
    viewModel: <Name>ViewModel = viewModel { <Name>ViewModel() },
) {
    val state by viewModel.state.collectAsState()

    <Name>Content(
        state = state,
        onIntent = viewModel::onIntent,
    )
}

@Composable
private fun <Name>Content(
    state: <Name>ScreenState,
    onIntent: (<Name>Intent) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        TextField(
            value = state.inputText,
            onValueChange = { onIntent(<Name>Intent.UpdateText(it)) },
            label = { Text("Enter text") },
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = { onIntent(<Name>Intent.Submit) },
            enabled = !state.isLoading && state.inputText.isNotBlank(),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text("Submit")
        }

        if (state.isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
        }

        state.error?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // Render QR image from ByteArray (if applicable)
        // Use Skia for cross-platform PNG decoding in commonMain:
        // val bitmap = org.jetbrains.skia.Image.makeFromEncoded(bytes).asImageBitmap()
        // Image(bitmap = bitmap, contentDescription = "QR Code")
    }
}
```

**Image handling note:** To display a PNG `ByteArray` in shared composable code, use Skia (the
graphics engine backing Compose Multiplatform):

```kotlin
import org.jetbrains.skia.Image as SkiaImage
import androidx.compose.ui.graphics.asImageBitmap

fun ByteArray.toImageBitmap(): ImageBitmap {
    return SkiaImage.makeFromEncoded(this).asImageBitmap()
}
```

This works on both Android and iOS — no expect/actual needed for image rendering.

**Key points:**

- `viewModel { <Name>ViewModel() }` — initializer lambda required for non-JVM platforms
- Split into `<Name>Screen` (wires ViewModel) and `<Name>Content` (pure render) for testability
- `<Name>Content` takes state + onIntent — stateless, previewable
- No business logic in composables — only render state and emit intents

## Swift (SwiftUI) — iosApp (optional, for Approach B)

Only scaffold if the screen needs direct Swift-side Rust integration (Approach B).

### 5. Swift State + Intent

`iosApp/iosApp/<Name>/<Name>State.swift`:

```swift
import Foundation
import UIKit

/// Immutable state for the <Name> screen.
struct <Name>ScreenState {
    let inputText: String
    let resultImage: UIImage?
    let isLoading: Bool
    let error: String?

    static let initial = <Name>ScreenState(
        inputText: "",
        resultImage: nil,
        isLoading: false,
        error: nil
    )

    // Copy-with-modification helpers (Swift has no data class copy())
    func with(inputText: String? = nil, resultImage: UIImage?? = nil,
              isLoading: Bool? = nil, error: String?? = nil) -> <Name>ScreenState {
        <Name>ScreenState(
            inputText: inputText ?? self.inputText,
            resultImage: resultImage ?? self.resultImage,
            isLoading: isLoading ?? self.isLoading,
            error: error ?? self.error
        )
    }
}

/// User intents for the <Name> screen.
enum <Name>Intent {
    case updateText(String)
    case submit
    case clearError
}
```

### 6. Swift ViewModel

`iosApp/iosApp/<Name>/<Name>ViewModel.swift`:

```swift
import Foundation
import SwiftUI

/// Processes [<Name>Intent]s and updates [<Name>ScreenState].
@MainActor
final class <Name>ViewModel: ObservableObject {
    @Published private(set) var state = <Name>ScreenState.initial

    func send(_ intent: <Name>Intent) {
        switch intent {
        case .updateText(let text):
            state = state.with(inputText: text)
        case .submit:
            handleSubmit()
        case .clearError:
            state = state.with(error: .some(nil))
        }
    }

    private func handleSubmit() {
        state = state.with(isLoading: true, error: .some(nil))

        Task.detached(priority: .userInitiated) { [inputText = state.inputText] in
            do {
                // Rust FFI calls are synchronous — must run off MainActor
                let result = try RustyQrFfi.generatePng(content: inputText, size: 256)
                let image = UIImage(data: result)
                await MainActor.run { [weak self] in
                    self?.state = self?.state.with(resultImage: image, isLoading: false)
                        ?? .initial
                }
            } catch {
                await MainActor.run { [weak self] in
                    self?.state = self?.state.with(
                        isLoading: false,
                        error: .some(error.localizedDescription)
                    )
                        ?? .initial
                }
            }
        }
    }
}
```

**Key points:**

- `@MainActor` on ViewModel — all state mutations on main thread
- `Task.detached(priority: .userInitiated)` for synchronous Rust FFI calls — does NOT inherit actor
  context
- Capture `inputText` in closure to avoid accessing `state` from detached task
- `@Published private(set)` — views can observe but not mutate directly
- `[weak self]` in async callbacks to avoid retain cycles

### 7. Swift View

`iosApp/iosApp/<Name>/<Name>View.swift`:

```swift
import SwiftUI

/// <Name> screen view — renders state and emits intents.
struct <Name>View: View {
    @StateObject private var viewModel = <Name>ViewModel()

    var body: some View {
        <Name>ContentView(
            state: viewModel.state,
            onIntent: { viewModel.send($0) }
        )
    }
}

/// Pure render function — takes state, emits intents.
struct <Name>ContentView: View {
    let state: <Name>ScreenState
    let onIntent: (<Name>Intent) -> Void

    var body: some View {
        VStack(spacing: 16) {
            TextField(
                "Enter text",
                text: Binding(
                    get: { state.inputText },
                    set: { onIntent(.updateText($0)) }
                )
            )
            .textFieldStyle(.roundedBorder)

            Button("Submit") {
                onIntent(.submit)
            }
            .disabled(state.isLoading || state.inputText.isEmpty)

            if state.isLoading {
                ProgressView()
            }

            if let error = state.error {
                Text(error)
                    .foregroundColor(.red)
            }

            // TODO: Render result (e.g., QR image)
        }
        .padding()
    }
}
```

**Key points:**

- Split into `<Name>View` (wires ViewModel) and `<Name>ContentView` (pure render) — matches Kotlin
  pattern
- `Binding(get:set:)` for TextField — bridges SwiftUI's Binding requirement with MVI intent pattern
- `@StateObject` (not `@ObservedObject`) — View owns the ViewModel lifecycle

## Verify

```bash
# Kotlin
./gradlew :composeApp:lintAll && ./gradlew :composeApp:assembleDebug

# Swift (if created)
swiftlint lint --path iosApp/
```
