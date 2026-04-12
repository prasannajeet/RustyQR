# ADR-002: Scan-to-Result Navigation Flow

**Status:** Accepted
**Date:** 2026-04-02
**Context:** Rusty-QR needs a navigation flow from the live QR camera scanner to a result screen displaying decoded content. Both Android (Compose Multiplatform) and iOS (SwiftUI) follow MVI.

---

## Decision Summary

| Aspect | Decision |
|--------|----------|
| UX on detection | Immediate navigate, no confirmation dialog |
| Debounce | `AtomicBoolean` scan gate (first-write-wins), not time-based |
| Haptic feedback | Single short tap on first decode |
| Camera on navigate | Pause frame analysis, keep session alive |
| Navigation lib (Android) | Compose Navigation (`org.jetbrains.androidx.navigation:navigation-compose`) |
| Navigation lib (iOS) | SwiftUI `NavigationStack` (native, iOS 17+) |
| Navigation events | One-shot `SharedFlow` (Kotlin) / `@Published` optional (Swift) — NOT in screen state |
| Result screen v1 | Raw text + URL detection + "Open in Browser" / "Copy" / "Scan Again" |
| Content type detection | Simple prefix match in commonMain, shared across platforms |

---

## 1. UX Behavior on QR Detection

Industry convention (Google Lens, iOS Camera app, WeChat, CashApp): navigate immediately on first successful decode.

**Flow:**
1. Frame decoded successfully — ViewModel receives `ScanResult`
2. Scan gate locks (`AtomicBoolean.compareAndSet(false, true)`) — prevents double-navigation
3. Haptic feedback — `HapticFeedbackType.LongPress` (Android) / `UIImpactFeedbackGenerator(.medium)` (iOS)
4. Camera pauses — `isScanning = false`, analyzer skips frames but session stays alive (no black flash)
5. Navigate to result screen — push onto nav stack
6. "Scan Again" — pops back, unlocks scan gate, resumes analysis

**Why AtomicBoolean over time-based debounce:** The gate locks on first decode and only unlocks on explicit user action ("Scan Again"). Simpler and more correct — no second navigation while result screen is showing, regardless of timing.

**Duplicate handling:** No "already scanned" filtering. Same QR scanned again after "Scan Again" shows the result again. Scan history is out of scope for v1.

---

## 2. Navigation Architecture

### Android/commonMain — Compose Navigation

```kotlin
// navigation/AppNavGraph.kt
sealed interface Route {
    @Serializable data object Scan : Route
    @Serializable data class Result(val content: String, val format: String) : Route
    @Serializable data object Generate : Route
}
```

**Why not Voyager/Decompose:** The app has 3 screens. Compose Multiplatform 1.11+ ships with the official multiplatform port of Jetpack Navigation. Adding a third-party lib is over-engineering.

**Dependency:**
```toml
# gradle/libs.versions.toml
[versions]
navigation-compose = "2.9.0-alpha01"

[libraries]
navigation-compose = { module = "org.jetbrains.androidx.navigation:navigation-compose", version.ref = "navigation-compose" }
```

### iOS — SwiftUI NavigationStack

```swift
enum ScanNavigationTarget: Hashable {
    case result(content: String)
}
```

Navigation state is a `@Published` optional on the ViewModel. SwiftUI `navigationDestination(item:)` observes it. Requires iOS 17+.

**No shared navigation layer.** Each platform uses its native navigation. The shared code is the ViewModels and state types in commonMain.

---

## 3. Navigation Events — One-Shot, Not State

Navigation events are fire-once side effects. Putting them in the State data class creates bugs (recomposition re-triggers navigation on back-press).

### Kotlin pattern

```kotlin
// ScanViewModel.kt
private val _navigationEvent = MutableSharedFlow<ScanNavigationEvent>(
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
)
val navigationEvent: SharedFlow<ScanNavigationEvent> = _navigationEvent.asSharedFlow()
```

Consumed in the composable:

```kotlin
LaunchedEffect(Unit) {
    viewModel.navigationEvent.collect { event ->
        when (event) {
            is ScanNavigationEvent.ShowResult ->
                onNavigateToResult(event.content, event.format)
        }
    }
}
```

### Swift pattern

```swift
@Published private(set) var navigationTarget: ScanNavigationTarget?
```

Set to `.result(content:)` on decode, set to `nil` on "Scan Again".

---

## 4. State Flow: Camera Thread to Navigation

```
Camera thread                    ViewModel (Main)              View (Main)
    |                                |                             |
    |-- scanGate.get() == false? -->|                             |
    |-- decode raw frame            |                             |
    |-- ScanIntent.FrameDecoded --->|                             |
    |   (via viewModelScope.launch) |                             |
    |                                |-- scanGate.compareAndSet -->|
    |                                |-- state.isScanning = false  |
    |                                |-- emit NavigationEvent ---->|
    |                                |                             |-- navController.navigate()
    |                                |                             |
    |-- next frame (skipped) ------>|                             |
    |   isScanning == false          |                             |
```

### Android: analyzer callback

```kotlin
override fun analyze(imageProxy: ImageProxy) {
    if (!viewModel.state.value.isScanning) {
        imageProxy.close()
        return
    }
    val result = QrBridge.decodeQrFromRaw(yBytes, width, height)
    result.fold(
        ifLeft = { /* ignore -- most frames won't have QR */ },
        ifRight = { scanResult ->
            viewModelScope.launch { onIntent(ScanIntent.FrameDecoded(scanResult)) }
        },
    )
    imageProxy.close()
}
```

### iOS: delegate callback

```swift
func captureOutput(_ output: AVCaptureOutput,
                   didOutput sampleBuffer: CMSampleBuffer,
                   from connection: AVCaptureConnection) {
    guard viewModel.state.isScanning else { return }
    // extract Y plane, call decodeQrFromRaw
    Task { @MainActor in viewModel.send(.frameDecoded(content: decoded)) }
}
```

---

## 5. Result Screen Content (v1)

| Element | Detail |
|---------|--------|
| Decoded text | Full content, selectable and copyable |
| Content type badge | "URL" or "Text" (prefix detection) |
| Primary action | URL: "Open in Browser". Text: "Copy to Clipboard" |
| Copy button | Always present |
| Scan Again | Pops back to scanner, resumes frame processing |

### Content type detection (commonMain, shared)

```kotlin
enum class QrContentType { URL, PLAIN_TEXT }

fun detectContentType(content: String): QrContentType = when {
    content.startsWith("http://", ignoreCase = true) ||
        content.startsWith("https://", ignoreCase = true) -> QrContentType.URL
    else -> QrContentType.PLAIN_TEXT
}
```

Extensible later for WiFi (`WIFI:T:`), vCard (`BEGIN:VCARD`), etc.

---

## 6. Camera Lifecycle

| Event | Android (CameraX) | iOS (AVFoundation) |
|-------|--------------------|--------------------|
| QR detected | Stop analysis (skip frames via `isScanning` flag) | Stop delegate processing (guard on `isScanning`) |
| Navigate to result | Preview stays live briefly during animation | Same |
| "Scan Again" return | Resume analysis, no camera restart | Same |
| Screen disposal | Unbind all use cases via `DisposableEffect` | Stop capture session via `onDisappear` |

---

## 7. File Layout

### commonMain

```
composeApp/src/commonMain/kotlin/com/p2/apps/rustyqr/
    App.kt                              -- NavHost root
    navigation/
        AppNavGraph.kt                  -- Route sealed interface
    scan/
        ScanScreenState.kt
        ScanIntent.kt
        ScanNavigationEvent.kt
        ScanViewModel.kt
        ScanScreen.kt                   -- ScanScreen + ScanContent
    result/
        ResultScreenState.kt
        ResultIntent.kt
        ResultViewModel.kt
        ResultScreen.kt                 -- ResultScreen + ResultContent
        ContentTypeDetector.kt
    generate/
        GenerateScreenState.kt
        GenerateIntent.kt
        GenerateViewModel.kt
        GenerateScreen.kt
```

### iOS (SwiftUI, Approach B)

```
iosApp/iosApp/
    Scan/
        ScanState.swift                 -- state + intent + nav target
        ScanViewModel.swift
        ScanView.swift                  -- ScanView + ScanContentView
        CameraPreviewView.swift         -- UIViewRepresentable
    Result/
        ResultState.swift
        ResultViewModel.swift
        ResultView.swift                -- ResultView + ResultContentView
```

---

## 8. Deferred (Not v1)

- Scan history / local database
- QR bounding box overlay on camera preview
- Success animation / checkmark
- WiFi auto-connect / vCard import / calendar event parsing
- Deep linking to result screen
- Bottom tab bar (Generate vs Scan as tabs)

---

## Alternatives Considered

| Alternative | Rejected because |
|-------------|-----------------|
| Confirmation dialog before navigating | Adds friction; not industry standard for QR scanners |
| Time-based debounce (500ms) | More complex, edge cases around timing; gate pattern is simpler |
| Voyager / Decompose for navigation | Over-engineering for 3 screens; official Compose Navigation suffices |
| Shared navigation in commonMain for iOS | iOS uses Approach B (Swift-side); native NavigationStack is simpler |
| Navigation state in screen State data class | Causes recomposition bugs; one-shot SharedFlow is correct pattern |
| Stop camera session on navigate | Black flash on return; pause analysis instead |

---

## Consequences

- Navigation is platform-native (Compose Navigation + SwiftUI NavigationStack) — no abstraction leaks
- Scan gate pattern is testable: mock a ViewModel, send two `FrameDecoded` intents, assert only one nav event
- Camera stays warm — users can "Scan Again" instantly
- Content type detection is extensible — add enum cases + prefix patterns as needed
- iOS requires iOS 17+ for `navigationDestination(item:)` — acceptable for this project
