<div align="center">

# composeApp — Kotlin Multiplatform Library

### Shared Compose UI. One ViewModel per screen. Common `QrBridge` code calls Rust through the generated UniFFI bindings.

<p>
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?logo=kotlin&logoColor=white" />
  <img alt="Compose Multiplatform" src="https://img.shields.io/badge/Compose%20Multiplatform-1.12.2-4285F4?logo=jetpackcompose&logoColor=white" />
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
> - iOS pipeline, cinterop linking, AVFoundation → [`../iosApp/README.md`](../iosApp/README.md)
> - Rust engine (QR encode/decode, UniFFI wrapper crate) → [`../rustySDK/README.md`](../rustySDK/README.md)
> - End-to-end architecture with sequence diagrams → [`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md)
> - Top-level project pitch → [`../README.md`](../README.md)

---

## Quick Start

The Rust library is built automatically by the `:rustyqr-bindings` Gradle module, which also generates the
UniFFI bindings. Platform builds are documented in the platform READMEs:

```bash
./gradlew :androidApp:assembleDebug   # Android — see src/androidMain/README.md
```

iOS builds go through Xcode — see `../iosApp/README.md`.

### Release builds

The Rust profile follows the `releaseBuild` Gradle property, not the build type. Release builds
need `-PreleaseBuild=true`:

```bash
./gradlew :androidApp:assembleRelease -PreleaseBuild=true   # or bundleRelease
```

Android release packaging (`assembleRelease`, `bundleRelease`, `assemble`, `build`) and Kotlin/Native release
framework links fail fast without the flag; `test` and `check` do not need it. Xcode passes the flag for every
configuration except Debug. With `-PreleaseBuild=true`, `:rustyqr-bindings:jvmTest` does not exist, so use the
`*Debug` tasks day to day.

---

## Module-Level Gradle Tasks

`composeApp` is a KMP library: it has no launcher of its own. The Android app shell (`MainActivity`, `Application`,
manifest, icons, APK packaging) lives in `:androidApp`, which depends on this module. `:composeApp:lintAll` also runs
`:androidApp:ktlintCheck` and `:androidApp:detekt` (the type-resolved `:androidApp:detektDebug`). Host tests:
`:composeApp:testAndroidHostTest`.

| Task                                    | What it does                                                            |
|-----------------------------------------|-------------------------------------------------------------------------|
| `:composeApp:lintAll`                   | Runs ktlint + detekt + SwiftLint in one go                              |
| `:composeApp:ktlintCheck` / `...Format` | Kotlin lint / auto-fix (Android mode, continuation indent 4)            |
| `:composeApp:detekt`                    | Static analysis (max line length 120)                                   |
| `:composeApp:swiftlint`                 | SwiftLint on `iosApp/` Swift sources                                    |
| `:composeApp:installGitHooks`           | Install pre-commit (`lintAll` + `cargo fmt --check`) and commit-msg hooks |

The Xcode project tasks (`generateXcodeProject`, `cleanXcodeProject`) are documented in the iOS
README. Rust builds and binding generation have no tasks of their own here: the `:rustyqr-bindings`
module runs them whenever an app build needs them (`./gradlew :rustyqr-bindings:buildBindings` installs the
bindgen, builds the host library and generates the Kotlin bindings, without cross-compiling the
Android or iOS libraries).

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
│       └── bridge/                         # platform contracts: expect declarations, plus the QrBridge object
│           ├── QrBridge.kt                 # object (not an expect): generateQrPng, decodeQr, decodeQrFromRaw, getLibraryVersion
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
├── androidMain/                            # Android actuals — CameraX, ActivityResultLauncher
│                                           # → src/androidMain/README.md for details
│
└── iosMain/                                # iOS Kotlin/Native actuals — AVFoundation, UIKit, Photos
                                            # → ../iosApp/README.md for details
```

The Rust bindings are not in this module. They are generated into the `:rustyqr-bindings` module
(`rustyqr-bindings/build/uniffi/bindings/`), which `composeApp` depends on.

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
`expect / actual` declaration in the `bridge/` package. The one exception is Rust:

```
commonMain/bridge/QrBridge.kt     object QrBridge { fun generateQrPng(...): Either<QrError, ByteArray> }
        │
        └─► :rustyqr-bindings generated Kotlin (commonMain API, one actual per platform)
              ├─ Android: JNA → librusty_qr_ffi.so
              └─ iOS:     Kotlin/Native cinterop → librusty_qr_ffi.a (linked into the framework)
```

`QrBridge` is **one `commonMain` object over the generated bindings**, not an `expect`. The bindings
module already contains the per-platform actuals, so `composeApp` needs none. `QrBridge` calls the
generated functions, wraps `generateQrPng`, `decodeQr` and `decodeQrFromRaw` in `Either.catch`, and
maps `FfiQrException` to the app's `QrError`. `getLibraryVersion()` is not wrapped: it returns a
plain `String` and can throw.

### Conventions

- **Return type is always `Either<DomainError, Result>`** — never throw across the FFI boundary.
  ArrowKT `Either` keeps the error path explicit and forces the caller to handle both sides.
- **Bridge actuals do not choose a dispatcher** — they are plain blocking (or callback) functions;
  the ViewModel owns an injectable `ioDispatcher` (default `Dispatchers.IO`) and wraps the calls
  that must stay off the main thread.
- **Filename convention is `Name.kt` (expect) + `Name.android.kt` / `Name.ios.kt` (actuals).**
  Detekt's `MatchingDeclarationName` is enabled and accepts the `.android.kt` / `.ios.kt` suffixes,
  so the declaration inside `Name.android.kt` can still carry the name of the `expect`.
- **Every platform actual lives under `bridge/`.** No scattering permission / camera / haptics /
  share actuals into feature folders. If it crosses the platform boundary, it's a bridge.

### What's behind each bridge

| Expect                | Android actual                             | iOS actual                                 |
|-----------------------|--------------------------------------------|--------------------------------------------|
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
  that wraps `MainViewControllerKt.mainViewController()` — the Compose UI exported from
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
`./gradlew :composeApp:installGitHooks`, which also runs on `:androidApp:preBuild` and `clean`).

| Tool       | Scope                   | Config                                           |
|------------|-------------------------|--------------------------------------------------|
| ktlint     | Kotlin sources          | root `.editorconfig` (every module) — Android mode, continuation indent 4; the shared setup is the `rustyqr.kotlin-lint` convention plugin in `build-logic/` |
| detekt     | Kotlin sources          | `config/detekt/detekt.yml` — detekt defaults (max line length 120) plus the Compose `FunctionNaming` allowance; no suppressions. Type resolution covers androidMain and commonMain (`detektAndroidMain`) and `androidApp/src/main` (`detektDebug`) (detekt 1.x cannot type-resolve Kotlin/Native), iosMain and commonTest get the untyped scan |
| SwiftLint  | `iosApp/` Swift sources | `.swiftlint.yml` — `trailing_whitespace` and `line_length` enabled |
| rustfmt    | `rustySDK/` Rust sources | `rustySDK/rustfmt.toml` — edition 2024, max line width 120 (separate pre-commit step) |

Auto-fix Kotlin formatting with `./gradlew :composeApp:ktlintFormat`.

**Prerequisite:** `detekt` (and therefore `lintAll` and the pre-commit hook) also runs the type-resolved
`detektAndroidMain` task (and `:androidApp:detektDebug`), which compiles the Android target. On a fresh checkout the
gitignored
`rustyqr-bindings/build/uniffi/bindings` UniFFI bindings do not exist yet, so run
`./gradlew :rustyqr-bindings:assemble` once before linting.

</details>
