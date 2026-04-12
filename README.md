# Rusty-QR

A cross-platform QR code generation and live scanning app built with **Kotlin Multiplatform**,
**Compose Multiplatform**, and a **Rust** core library. One Rust codebase handles all QR logic; one
Compose codebase renders the UI on both Android and iOS. Auto-generated Kotlin and Swift bindings
connect Rust to each platform with zero manual bridging code.

```
┌─────────────────────────────────────────────────────────┐
│                     Rusty-QR App                        │
│                                                         │
│       Compose Multiplatform UI  (commonMain)            │
│           Android + iOS from one codebase               │
│                     │                                   │
│              QrBridge (expect/actual)                    │
│                     │                                   │
│         ┌───── FFI Boundary ──────┐                     │
│         │  Kotlin Bindings (JNA)  │  Swift Bindings     │
│         └─────────────────────────┘                     │
│                     │                                   │
│            Rust SDK  (rusty-qr)                         │
│         Encode · Decode · Scan                          │
└─────────────────────────────────────────────────────────┘
```

---

## Why This Project Exists

Mobile teams writing cross-platform features often maintain the same logic in two languages. This
leads to subtle behavioural drift, double the bug surface, and double the maintenance burden.
Rusty-QR demonstrates a different approach: **write the business logic once in Rust, compile to
native binaries, and auto-generate idiomatic Kotlin and Swift bindings** via Mozilla's UniFFI.

The result is a single source of truth for QR encoding and decoding that both platforms share, with
native performance, compile-time memory safety, and a single Compose UI rendered identically on
both platforms.

---

## Features

### QR Code Generation

Enter text, pick a size, get a PNG rendered in real time. The Rust encoder supports four error
correction levels (Low, Medium, Quartile, High) trading density for damage resilience. Result card
offers share-sheet export and save-to-gallery.

### QR Code Scanning (Saved Images)

Decode QR codes from PNG or JPEG bytes picked from the photo library. Both platforms share the same
Rust decode path.

### Live Camera Scanning

Live camera scanner decodes QR codes from the device camera feed. Camera frames are passed directly
to Rust as raw grayscale (Y-plane) buffers — no image encoding overhead — enabling sub-20ms decode
latency per frame. On successful decode, a scan gate (atomic-style flag) prevents double-fire and
the camera session stays alive so dismissing the result returns instantly with no black flash.

### Scan Result Sheet

A successful decode surfaces a `ModalBottomSheet` with the decoded content, a content-type badge
(URL vs TEXT), and actions: **Open in Browser** (for URLs), **Copy** to clipboard, and **Scan
Again** which resets the scan gate and resumes analysis.

### Localization

UI ships in **three languages**: English (default), French (Canada), and Spanish. Strings live in
Compose Multiplatform resource bundles under `composeApp/src/commonMain/composeResources/`
(`values/`, `values-fr-rCA/`, `values-es/`) and are shared by Android and iOS from a single source.
ViewModels emit `UiText` (sealed type wrapping either a raw string or a `StringResource`) so locale
resolution stays in the composable layer and user-facing copy is never hardcoded in business logic.

### Shared Compose UI

Every screen — scan, generate, result sheet — is Compose Multiplatform and lives in `commonMain`.
The iOS app hosts this Compose UI inside a single `UIViewControllerRepresentable` wrapper. No
duplicate SwiftUI screens; the only platform-specific code is the hardware bridges (camera,
clipboard, haptics, share sheet, URL open).

---

## Architecture

```mermaid
graph TB
    subgraph "commonMain — shared Kotlin (Android + iOS)"
        UI["Compose Multiplatform UI<br/>(screens · components · theme)"]
        MVI["MVI ViewModels<br/>State · Intent · ViewModel"]
        BRIDGE_EXP["expect bridges<br/>(bridge/ package)"]
    end

    subgraph "androidMain — Android actuals"
        BRIDGE_AND["QrBridge → UniFFI Kotlin (JNA)"]
        CAM_AND["CameraPreview → CameraX ImageAnalysis"]
        MISC_AND["Permissions · Haptics · OpenUrl ·<br/>Share · Save · ImageDecoder"]
    end

    subgraph "iosMain — iOS actuals (KMM side)"
        BRIDGE_IOS["QrBridge → RustyQR.xcframework"]
        CAM_IOS["CameraPreview → AVFoundation UIKitView"]
        MISC_IOS["Haptics · OpenUrl · Share · Save"]
    end

    subgraph "rustySDK — Rust core + FFI"
        FFI["rusty-qr-ffi<br/>UniFFI proc-macro exports"]
        CORE["rusty-qr-core<br/>encoder · decoder · scanner"]
    end

    MVI --> BRIDGE_EXP
    BRIDGE_EXP --> BRIDGE_AND
    BRIDGE_EXP --> BRIDGE_IOS
    BRIDGE_AND --> FFI
    BRIDGE_IOS --> FFI
    FFI --> CORE
```

All business logic and UI live in `commonMain`. Platform code is minimal — only what the OS
requires (camera hardware, file I/O, haptics, system share sheet, clipboard, URL open).

| Layer     | Technology                          | Responsibility                                              |
|-----------|-------------------------------------|-------------------------------------------------------------|
| UI        | Compose Multiplatform (both)        | Renders state, dispatches intents                           |
| ViewModel | Kotlin (commonMain)                 | Processes intents, owns state, manages the scan gate        |
| Bridge    | expect/actual (`bridge/` package)   | Abstracts platform-specific FFI / hardware calls            |
| FFI       | UniFFI (auto-generated)             | Marshals types between Kotlin/Swift and Rust                |
| Core      | Rust                                | All QR encoding, decoding, validation, and error handling   |

---

## MVI Pattern

Every screen follows **Model–View–Intent** with strict unidirectional data flow:

```mermaid
graph LR
    USER(("User action"))
    INTENT["Intent<br/>(sealed interface)"]
    VM["ViewModel<br/>onIntent()"]
    STATE["State<br/>(immutable data class)"]
    VIEW["Composable<br/>(pure render)"]

    USER -->|"tap / frame / permission"| INTENT
    INTENT -->|"dispatched to"| VM
    VM -->|"state.update { }"| STATE
    STATE -->|"collectAsState()"| VIEW
    VIEW -->|"emits"| INTENT
```

Rules enforced across every screen:

- **State** is an immutable `data class` — no `var` exposed to composables.
- **Intent** is a `sealed interface` — every user action is an explicit type, not a lambda callback.
- **ViewModel** owns all business logic — composables are pure render functions.
- **Side effects** (haptics, URL open, share, save) are triggered inside `onIntent()`, never inside
  a composable body.
- **Error contract**: ArrowKT `Either<QrError, T>` — never throw for domain errors, never
  `kotlin.Result<T>`.
- **Explicit backing fields** (KEEP-278): `val state: StateFlow<X> field = MutableStateFlow(...)`
  rather than `_state` / `asStateFlow()`.

---

## expect/actual Convention

All platform-specific contracts live in a single `bridge/` package — never scattered across feature
packages:

```
commonMain/kotlin/com/p2/apps/rustyqr/bridge/
├── QrBridge.kt             // Rust FFI — generate/decode/version
├── CameraPreview.kt        // camera composable
├── CameraPermission.kt     // runtime permission check + request
├── HapticFeedback.kt       // tap/success haptic
├── OpenUrl.kt              // open http(s) URL in system browser
├── ShareQrImage.kt         // system share sheet for PNG bytes
├── SaveQrImage.kt          // save PNG to gallery / Photos
└── ImageDecoder.kt         // PNG bytes → ImageBitmap
```

Each has a matching `*.android.kt` and `*.ios.kt` actual. The `MatchingDeclarationName` detekt rule
is disabled because actual files follow the `*.android.kt` / `*.ios.kt` naming convention rather
than matching the top-level class name.

---

## Navigation

No navigation library. Two top-level tabs with `Crossfade`. The scan result is a `ModalBottomSheet`
driven by `ScanViewModel` state, not a navigation destination.

```mermaid
graph LR
    APP["App.kt<br/>Tab state hoisted here"]
    SCAN["ScanScreen<br/>+ ScanResultSheet"]
    GEN["GenerateScreen<br/>+ QrResultCard"]

    APP -->|"Tab.Scan (default)"| SCAN
    APP -->|"Tab.Generate"| GEN
```

Camera lifecycle is tied to tab visibility — `CameraPreview` is only composed when `Tab.Scan` is
active. Switching to Generate unbinds the camera; switching back rebinds without a black flash. The
rationale is documented in `docs/adr/002-scan-to-result-navigation.md`.

---

## Theme, Typography, Motion

Dark-only Material 3 colour scheme. Feature code accesses colours only through
`MaterialTheme.colorScheme.*` — never direct imports from `Color.kt`.

| Token                 | Value                                    |
|-----------------------|------------------------------------------|
| Primary               | `#F5A623` (amber)                        |
| Background            | `#1A1A1A`                                |
| Surface / Container   | `#1E1E1E` / `#242424`                    |
| Outline               | `#3A3A3A`                                |
| Body font             | Inter                                    |
| Display / mono font   | JetBrains Mono (titles, badges, buttons) |
| Shape scale           | 4 / 8 / 12 / 16 / 28dp (MD3)             |

Motion uses MD3 easing curves from `ui/theme/Motion.kt`:

- `StandardEasing` — tab crossfade, colour animations
- `EmphasizedDecelerate` — elements entering (QR card appear)
- `EmphasizedAccelerate` — elements leaving (input text animates down)

---

## Project Structure

```
Rusty-QR/
├── composeApp/                 # KMP module — shared UI + Android actuals + iOS actuals
│   ├── src/commonMain/         # Shared Kotlin: UI, ViewModels, bridges (expect)
│   ├── src/androidMain/        # Android actuals + CameraX + JNI libs + generated UniFFI
│   └── src/iosMain/            # iOS actuals (Kotlin/Native)
│   └── README.md               # Android build pipeline + androidMain details
│
├── iosApp/                     # iOS Xcode project — hosts Compose UI via UIKit
│   ├── project.yml             # XcodeGen source of truth
│   ├── iosApp/                 # Swift shell (ContentView wraps MainViewController)
│   ├── Configuration/          # xcconfig files
│   ├── generated/              # UniFFI Swift bindings (gitignored)
│   ├── Frameworks/             # RustyQR.xcframework (gitignored)
│   └── README.md               # iOS build pipeline + Xcode/XcodeGen details
│
├── rustySDK/                   # Rust workspace — all QR logic
│   ├── crates/core/            # encoder · decoder · types · errors (no FFI)
│   ├── crates/ffi/             # UniFFI thin wrappers
│   ├── crates/uniffi-bindgen/  # CLI for generating Kotlin/Swift bindings
│   └── README.md               # Rust SDK deep dive (ownership, cargo, FFI types)
│
├── config/detekt/              # Detekt static analysis rules
├── docs/                       # PRD, implementation plan, ADRs
└── .husky/                     # Git hooks (pre-commit lint, commit-msg format)
```

For per-module details, see the nested READMEs linked above.

---

## Build and Run

### Prerequisites

- **Android**: Android Studio, JDK 11+, Android SDK (compileSdk 36, minSdk 29)
- **iOS**: Xcode, macOS, XcodeGen (`brew install xcodegen`)
- **Rust**: Install via [rustup.rs](https://rustup.rs/) — targets for Android + iOS cross-compile
  (see platform READMEs for first-time setup)

### Quick commands

```bash
# Android — build Rust .so + Kotlin bindings + APK
./gradlew :composeApp:buildRustAndroid :composeApp:assembleDebug

# iOS — build Rust .a + XCFramework + Swift bindings + Xcode project
./gradlew :composeApp:buildRustIos
open iosApp/iosApp.xcodeproj

# All three Kotlin/Swift linters
./gradlew :composeApp:lintAll

# Auto-fix Kotlin formatting
./gradlew :composeApp:ktlintFormat
```

Deeper details (what each task does, how the cross-compiler is invoked, how UniFFI generates
bindings) live in the platform READMEs — see [`composeApp/README.md`](composeApp/README.md) and
[`iosApp/README.md`](iosApp/README.md).

### Rust SDK (standalone)

```bash
cd rustySDK
cargo test --workspace                           # all tests
cargo clippy --workspace -- -D warnings          # lint
cargo bench -p rusty-qr-core                     # benchmarks
cargo deny check                                 # supply chain audit
```

See [`rustySDK/README.md`](rustySDK/README.md) for the full Rust deep dive.

---

## Tech Stack

| Component        | Technology                         | Version       |
|------------------|------------------------------------|---------------|
| Shared UI        | Compose Multiplatform              | 1.11.0-beta01 |
| Shared logic     | Kotlin Multiplatform               | 2.3.20        |
| Error handling   | ArrowKT `Either`                   | —             |
| Android camera   | CameraX                            | —             |
| iOS camera       | AVFoundation                       | —             |
| FFI loader       | JNA (Android)                      | 5.14.0        |
| QR engine        | Rust (edition 2021)                | —             |
| QR encoding      | `qrcode` crate                     | 0.14          |
| QR decoding      | `rqrr` crate                       | 0.10          |
| Image processing | `image` crate                      | 0.25          |
| FFI bindings     | UniFFI                             | 0.28          |
| Rust errors      | `thiserror`                        | 2.x           |
| Kotlin lint      | ktlint + detekt                    | —             |
| Swift lint       | SwiftLint                          | —             |
| Git hooks        | Husky-style (Gradle task)          | —             |
| Xcode project    | XcodeGen                           | —             |
| Dependency audit | `cargo-deny`                       | —             |

---

## Code Quality

| Check                  | Tool            | Command                                                                        |
|------------------------|-----------------|--------------------------------------------------------------------------------|
| Rust formatting        | `rustfmt`       | `cargo fmt --check`                                                            |
| Rust linting           | Clippy          | `cargo clippy --workspace -- -D warnings`                                      |
| Rust tests             | Cargo test      | `cargo test --workspace`                                                       |
| Rust benchmarks        | Criterion       | `cargo bench -p rusty-qr-core`                                                 |
| Rust supply chain      | cargo-deny      | `cargo deny check`                                                             |
| Kotlin formatting      | ktlint          | `./gradlew :composeApp:ktlintCheck`                                            |
| Kotlin static analysis | detekt          | `./gradlew :composeApp:detekt`                                                 |
| Swift linting          | SwiftLint       | `./gradlew :composeApp:swiftlint`                                              |
| Pre-commit hook        | Husky           | `lintAll` on staged `.kt`/`.kts`/`.swift`; `cargo fmt --check` on staged `.rs` |
| Commit messages        | commit-msg hook | Enforces conventional commits (`type(scope): message`)                         |
| iOS CI                 | GitHub Actions  | Rust checks + XCFramework build + xcodebuild + SwiftLint                       |

---

## Status

| Phase                                         | Status            |
|-----------------------------------------------|-------------------|
| 0 — Toolchain setup                           | Done              |
| 1 — Rust core encoder                         | Done              |
| 2 — Rust core decoder + raw scanner           | Done              |
| 3 — FFI crate + UniFFI bindings               | Done              |
| 4 — Android build pipeline (.so + Kotlin)     | Done              |
| 5 — iOS build pipeline (.a + XCFramework)     | Done              |
| 6 — Android UI + CameraX scanner              | Done              |
| 7 — iOS runtime wiring                        | In progress       |
| 8 — Polish, perf assertions, docs             | In progress       |

iOS actuals for `QrBridge.ios.kt` are currently stubs (`TODO("Wired in Phase 7")`). The iOS build
pipeline produces the XCFramework and Swift bindings, but the Kotlin-side iOS bridge delegation to
those bindings is not yet in place. Android is end-to-end functional.

---

## License

Released under the [MIT License](LICENSE). Portfolio project — not published to crates.io, Maven
Central, or CocoaPods.
