<div align="center">

# composeApp — Kotlin Multiplatform Module

### Shared Compose UI. One ViewModel per screen. Thin platform actuals call Rust through UniFFI.

<p>
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.3.20-7F52FF?logo=kotlin&logoColor=white" />
  <img alt="Compose Multiplatform" src="https://img.shields.io/badge/Compose%20Multiplatform-1.11.0-4285F4?logo=jetpackcompose&logoColor=white" />
  <img alt="KMP" src="https://img.shields.io/badge/KMP-commonMain%20%7C%20androidMain%20%7C%20iosMain-7F52FF?logo=kotlin&logoColor=white" />
  <img alt="MVI" src="https://img.shields.io/badge/Architecture-MVI-1976D2" />
  <img alt="ArrowKT" src="https://img.shields.io/badge/ArrowKT-Either-7B3FE4" />
  <img alt="UniFFI" src="https://img.shields.io/badge/UniFFI-auto--bindings-3B6FE0" />
</p>

<p><b>One Compose tree · Two native apps · MVI with explicit backing fields · ArrowKT <code>Either</code> at the FFI boundary</b></p>

<sub>This module holds shared Compose UI in <code>commonMain</code> plus platform actuals in
<code>androidMain</code> and <code>iosMain</code>. Every screen (Scan, Generate, result sheet) is
written once in Compose and rendered natively on both platforms through Jetpack Compose on Android
and SKIA on iOS.</sub>

</div>

---

> **Looking for platform specifics?** This README covers the shared KMP layer — source-set layout,
> MVI conventions, the `bridge/` expect/actual pattern, shared Gradle tasks.
>
> - Android pipeline, CameraX wiring, `.so` packaging, JNA loader → [`src/androidMain/README.md`](src/androidMain/README.md)
> - iOS pipeline, XCFramework, cinterop wiring, AVFoundation → [`../iosApp/README.md`](../iosApp/README.md)
> - Rust engine (QR encode/decode, UniFFI wrapper crate) → [`../rustySDK/README.md`](../rustySDK/README.md)
> - End-to-end architecture with sequence diagrams → [`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md)
> - Top-level project pitch → [`../README.md`](../README.md)

---

## Quick Start

Platform builds are documented in the platform READMEs — each has its own first-time setup:

```bash
./gradlew :composeApp:buildRustAndroid :composeApp:assembleDebug   # Android — see src/androidMain/README.md
./gradlew :composeApp:buildRustIos                                 # iOS — see ../iosApp/README.md
```

If you're only editing shared Compose UI or ViewModels in `commonMain`, skip the `buildRust*`
tasks and let Gradle reuse the existing `.so` / `.xcframework`.

---

## Module-Level Gradle Tasks

These apply to the whole module — they work the same way whether you're building for Android or iOS.

| Task                                    | What it does                                                            |
|-----------------------------------------|-------------------------------------------------------------------------|
| `:composeApp:lintAll`                   | Runs ktlint + detekt + SwiftLint in one go                              |
| `:composeApp:ktlintCheck` / `...Format` | Kotlin lint / auto-fix (Android mode, continuation indent 4)            |
| `:composeApp:detekt`                    | Static analysis (max line length 120)                                   |
| `:composeApp:swiftlint`                 | SwiftLint on `iosApp/` Swift sources                                    |
| `:composeApp:installGitHooks`           | Install pre-commit (`lintAll` + `cargo fmt --check`) and commit-msg hooks |

Platform-specific build tasks (`buildRustAndroid`, `buildRustIos`, `cleanBuildIos`,
`generateXcodeProject`) are documented in their respective platform READMEs.

---

<details>
<summary><b>Source Set Layout</b> — how commonMain, androidMain, and iosMain split work</summary>

<br/>

```
composeApp/src/
├── commonMain/
│   └── kotlin/com/p2/apps/rustyqr/
│       ├── App.kt                          # Root composable — hosts navigation + theme
│       ├── Platform.kt                     # expect fun Platform(): String
│       ├── model/                          # Shared data types (ScanResult, QrError, ...)
│       ├── ui/
│       │   ├── screen/                     # ScanScreen, GenerateScreen — shared Compose
│       │   ├── components/                 # PillTabBar, ScanResultSheet, AmberButton, ...
│       │   ├── mvi/                        # *State + *Intent sealed types per screen
│       │   ├── viewmodels/                 # ScanViewModel, GenerateViewModel
│       │   ├── navigation/                 # Tab sealed hierarchy
│       │   └── theme/                      # RustyQrTheme, Color, Type, Shape, Motion
│       └── bridge/                         # expect declarations for everything platform-specific
│           ├── QrBridge.kt                 # generatePng, decodeQr, decodeQrFromRaw, getLibraryVersion
│           ├── CameraPreview.kt            # @Composable — platform draws the live preview + decodes frames
│           ├── CameraPermission.kt         # expect funs — status check, request, permanent-denied, openAppSettings
│           ├── HapticFeedback.kt           # expect fun — triggerImpactHaptic
│           ├── OpenUrl.kt                  # expect fun — openUrlExternally
│           ├── ShareQrImage.kt             # expect fun — system share sheet with PNG bytes
│           ├── SaveQrImage.kt              # expect fun — save PNG to gallery / photos
│           └── ImageDecoder.kt             # expect fun — platform image bytes → ImageBitmap
│
├── commonTest/                             # Shared ViewModel tests
│
├── androidMain/                            # Android actuals — CameraX, JNA, ActivityResultLauncher
│                                           # → src/androidMain/README.md for details
│
├── iosMain/                                # iOS Kotlin/Native actuals — AVFoundation, cinterop to UniFFI C header
│                                           # → ../iosApp/README.md for details
│
└── nativeInterop/cinterop/                 # Kotlin/Native cinterop .def — iOS only
    └── RustyQrFFI.def                      # Points at iosApp/generated/headers/RustyQrFFIFFI.h
```

**Key rule — all expect/actual declarations live in `bridge/`.** Even camera and haptics, which
arguably belong in a `hardware/` package, live there so that every platform-specific touchpoint is
in one predictable place. See the inline package comment in `commonMain/.../bridge/` for the
reasoning.

</details>

<details>
<summary><b>MVI Pattern</b> — State sealed hierarchy, explicit backing field, Intent fan-in</summary>

<br/>

Every screen follows the same shape:

```
ScreenName/
├── *State.kt          # sealed interface — data classes for each UI state
├── *Intent.kt         # sealed interface — every user/system action that can mutate state
├── *ViewModel.kt      # onIntent(Intent): Unit + field = MutableStateFlow(initial)
└── *Screen.kt         # @Composable — collectAsState() + onIntent hooks
```

### Explicit backing fields (KEEP-278)

ViewModels expose state through a single `val state: StateFlow<State>` using the explicit backing
field syntax — no underscore-prefixed mutable twin, no `asStateFlow()` wrapper:

```kotlin
class ScanViewModel : ViewModel() {
    val state: StateFlow<ScanScreenState>
        field = MutableStateFlow(ScanScreenState.Idle)

    fun onIntent(intent: ScanScreenIntent) = when (intent) {
        is ScanScreenIntent.StartScanning -> state.update { /* ... */ }
        is ScanScreenIntent.FrameDecoded  -> state.update { /* ... */ }
        // ...
    }
}
```

Reads are type-safe `StateFlow<T>`; writes stay private to the class. No
`_state / state.asStateFlow()` boilerplate, no accidental double-publication of the same flow.

### Intent handling — `when` returning `Unit`

`onIntent` is a single `when` over the sealed `Intent` hierarchy. The compiler catches every missed
branch. No command pattern, no reducer lookup table — just code a human can read top to bottom.

### Tests live in `commonTest`

ViewModel tests stay in `commonTest` and run on both JVM and iOS simulator. State + Intent are pure
Kotlin, so the tests don't touch a Dispatcher, a Dispatcher Provider, or a Context.

</details>

<details>
<summary><b>Bridge Pattern</b> — how platform code reaches shared code</summary>

<br/>

Every time shared code needs platform hardware or a platform API, it goes through an
`expect / actual` declaration in the `bridge/` package:

```
commonMain/bridge/QrBridge.kt          expect object QrBridge { fun generatePng(...): Either<...> }
        │
        ├─► androidMain/bridge/QrBridge.android.kt   actual object QrBridge → UniFFI-generated Kotlin → JNA → .so
        └─► iosMain/bridge/QrBridge.ios.kt           actual object QrBridge → cinterop → UniFFI C header → .a
```

### Conventions

- **Return type is always `Either<DomainError, Result>`** — never throw across the FFI boundary.
  ArrowKT `Either` keeps the error path explicit and forces the caller to handle both sides.
- **Coroutine dispatchers stay out of `bridge/`** — the actual implementations are synchronous
  functions; ViewModels decide which `Dispatcher` to call them on.
- **Filename convention is `Name.kt` (expect) + `Name.android.kt` / `Name.ios.kt` (actuals).**
  Detekt's `MatchingDeclarationName` is disabled globally so the class inside `Name.android.kt` can
  still be called `QrBridge`.
- **Every platform actual lives under `bridge/`.** No scattering permission / camera / haptics /
  share actuals into feature folders. If it crosses the platform boundary, it's a bridge.

### What's behind each bridge

| Expect                | Android actual                             | iOS actual                                 |
|-----------------------|--------------------------------------------|--------------------------------------------|
| `QrBridge`            | UniFFI-generated Kotlin → JNA → `.so`      | Kotlin/Native cinterop → UniFFI C → `.a`   |
| `CameraPreview`       | `AndroidView` wrapping `PreviewView`       | `UIKitView` wrapping `AVCaptureVideoPreviewLayer` |
| `CameraPermission`    | `ActivityResultLauncher` (activity scope)  | `AVCaptureDevice.requestAccess(for: .video)` |
| `HapticFeedback`      | `View.performHapticFeedback`               | `UIImpactFeedbackGenerator`                |
| `OpenUrl`             | `Intent(ACTION_VIEW)`                      | `UIApplication.openURL`                    |
| `ShareQrImage`        | `Intent(ACTION_SEND)` via `FileProvider`   | `UIActivityViewController`                 |
| `SaveQrImage`         | `MediaStore.Images`                        | `PHPhotoLibrary`                           |
| `ImageDecoder`        | `BitmapFactory` → `ImageBitmap`            | `UIImage` → `ImageBitmap`                  |

</details>

<details>
<summary><b>Compose Multiplatform Notes</b> — where shared UI ends and platform rendering begins</summary>

<br/>

- **Rendering is identical across platforms.** Compose Multiplatform uses the same Compose runtime
  on Android and iOS; the difference is only the rendering backend (Jetpack Compose on Android,
  SKIA on iOS). There is no per-platform Compose code in this module outside of `bridge/`.
- **No native SwiftUI.** `iosApp/iosApp/ContentView.swift` is a `UIViewControllerRepresentable`
  that wraps `MainViewControllerKt.MainViewController()` — the Compose UI exported from
  `iosMain`. Every visible screen runs through the shared `commonMain` composables.
- **Resources live in `commonMain/composeResources/`.** Drawables, fonts, string catalogs (including
  `values-fr-rCA` and `values-es`) are resolved at runtime via the Compose Multiplatform resource
  generator. No Android `res/` duplication.
- **Theme is shared.** `ui/theme/RustyQrTheme.kt` owns colors, typography, shape, and motion tokens.
  Neither platform overrides them.

</details>

<details>
<summary><b>Linting & Formatting</b> — one task, three tools</summary>

<br/>

`./gradlew :composeApp:lintAll` runs ktlint, detekt, and SwiftLint in a single invocation. The same
three tools also run automatically as a pre-commit hook (installed by
`./gradlew :composeApp:installGitHooks`, which also runs on `preBuild` / `clean`).

| Tool       | Scope                   | Config                                           |
|------------|-------------------------|--------------------------------------------------|
| ktlint     | Kotlin sources          | `composeApp/.editorconfig` — Android mode, continuation indent 4 |
| detekt     | Kotlin sources          | `config/detekt/detekt.yml` — max line length 120, `MagicNumber` + `WildcardImport` off |
| SwiftLint  | `iosApp/` Swift sources | `.swiftlint.yml` — `trailing_whitespace` and `line_length` disabled |
| rustfmt    | `rustySDK/` Rust sources | `rustySDK/rustfmt.toml` — edition 2024, max line width 120 (separate pre-commit step) |

Auto-fix Kotlin formatting with `./gradlew :composeApp:ktlintFormat`.

</details>
