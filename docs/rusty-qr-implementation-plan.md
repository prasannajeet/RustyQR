# Rusty-QR: Mobile Architect Implementation Plan

## Context

Rusty-QR is a cross-platform QR code generation and **live scanning** app written in Rust with auto-generated Kotlin and Swift bindings via UniFFI, integrated into an existing KMM Compose Multiplatform demo app. The PRD (`docs:/rusty_qr_prd.docx.md`) defines the core spec. The KMM skeleton is complete (Android + iOS targets, linting, git hooks). Phases 0-1 are complete (Rust toolchain + encoder).

The goal: build the Rust library, generate native bindings, and wire them into the existing KMM app so both Android and iOS can generate QR codes and **scan them live via the device camera** from shared Rust logic.

**Note:** The PRD lists live camera scanning as NG1 (non-goal). This plan overrides that — camera-based QR scanning is now in scope. The Rust core provides a `decode_from_raw()` function that accepts raw grayscale pixel buffers, enabling efficient camera frame processing without PNG encoding overhead.

---

## Phase 0: Toolchain Setup ✅
**Gate:** All tools installed and verified

- Install Rust targets: `aarch64-linux-android`, `armv7-linux-androideabi`, `x86_64-linux-android`, `aarch64-apple-ios`, `aarch64-apple-ios-sim`
- Install `cargo-ndk` for Android cross-compilation
- Verify `$ANDROID_NDK_HOME`, `xcrun --show-sdk-path`
- Create `rustySDK/.cargo/config.toml` with NDK linker paths

**Verify:** `rustc --version` >= 1.70, `cargo ndk --version`, `rustup target list --installed` shows all 5 targets

---

## Phase 1: Rust Core — Encoder ✅
**Gate:** `cargo test -p rusty-qr-core` passes all generation tests (10/10 passing)

**Create Rust workspace alongside the KMM project:**
```
rustySDK/
  Cargo.toml                    # workspace: members = ["crates/core", "crates/ffi"]
  crates/core/
    Cargo.toml                  # qrcode = "0.14", image = "0.25", thiserror = "2"
    src/
      lib.rs                    # pub mod encoder; pub mod error; pub mod types;
      error.rs                  # QrError enum (5 variants, named fields for UniFFI compat)
      types.rs                  # QrConfig, QrResult records
      encoder.rs                # generate_png(), generate_with_config(), get_library_version()
```

**Key design decision:** Use **named fields** in `QrError` variants (`InvalidInput { reason: String }` not `InvalidInput(String)`) from day one — UniFFI requires this. Avoids adapter layer in Phase 3.

**Encoder flow:** validate inputs → `qrcode::QrCode::new()` → render to `image::GrayImage` → resize to requested size with `Nearest` filter → encode as PNG via `PngEncoder` → return `Vec<u8>`

**Tests:** `encode_simple_text`, `encode_empty_content_fails`, `encode_size_zero_fails`, `encode_size_exceeds_max_fails`, PNG magic bytes validation, `get_library_version()` matches Cargo.toml

---

## Phase 2: Rust Core — Decoder, Raw Scanner, Round-Trip
**Gate:** `cargo test -p rusty-qr-core` passes ALL tests including round-trip

**Add to core crate:**
- `src/decoder.rs` — `decode()` (PNG/JPEG bytes) and `decode_from_raw()` (raw grayscale pixel buffer) using `rqrr` crate
- `src/types.rs` — `QrConfig`, `QrErrorCorrection` enum, `ScanResult { content: String }`
- `src/command.rs` — `QrCommand`/`QrResponse` event dispatch per code standards
- `crates/core/tests/round_trip.rs` — integration tests
- Add dep: `rqrr = "0.10"` to core Cargo.toml (QR-only decoder, ~10x smaller than `rxing`)

**Two decode paths:**
1. **`decode(image_data: &[u8]) -> Result<ScanResult, QrError>`** — accepts PNG/JPEG bytes, loads via `image::load_from_memory()`, converts to grayscale, scans with `rqrr`. For decoding saved images.
2. **`decode_from_raw(pixels: &[u8], width: u32, height: u32) -> Result<ScanResult, QrError>`** — accepts raw grayscale pixel buffer directly. **This is the camera path** — mobile camera APIs (CameraX/AVFoundation) provide luminance planes that can be passed straight to Rust without image encoding overhead. Expected latency: < 20ms per frame.

**Decoder flow (both paths converge):** validate inputs → get grayscale pixels → `rqrr::PreparedImage::prepare_from_greyscale()` → `detect_grids()` → `decode()` → extract text

**Critical round-trip test:**
```rust
let content = "https://example.com?foo=bar&baz=qux";
let png = generate_png(content, 256)?;
let decoded = decode(&png)?;
assert_eq!(decoded.content, content);
```

**Why `rqrr` over `rxing`:** `rxing` (0.8.5) pulls in 1D barcode support, `imageproc`, `serde`, `encoding_rs` by default — significant binary bloat. `rqrr` (0.10) is purpose-built for QR detection only and works directly with grayscale buffers, making it ideal for the camera frame path.

Also adds `generate_with_config()` to encoder and the `QrCommand`/`QrResponse` event dispatch from the code standards.

---

## Phase 3: FFI Crate + UniFFI Bindings
**Gate:** `cargo build -p rusty-qr-ffi` succeeds, Kotlin + Swift binding files generated

**Create:**
```
rustySDK/crates/ffi/
  Cargo.toml                    # rusty-qr-core (path), uniffi = "0.28"
  build.rs                      # UniFFI scaffolding
  uniffi.toml                   # kotlin package: com.p2.apps.rustyqr.rust
  src/lib.rs                    # #[uniffi::export] thin wrappers
```

**Approach:** Use **proc-macros** (`#[uniffi::export]`) not UDL files. Each FFI function is a one-liner delegating to core. **Zero business logic in FFI crate.**

**FFI surface (6 functions, 5 types):**
- `generate_png(content: String, size: u32) -> Result<Vec<u8>, QrError>`
- `generate_with_config(content: String, config: QrConfig) -> Result<Vec<u8>, QrError>`
- `decode_qr(image_data: Vec<u8>) -> Result<ScanResult, QrError>`
- `decode_qr_from_raw(pixels: Vec<u8>, width: u32, height: u32) -> Result<ScanResult, QrError>` — **camera frame path**
- `get_library_version() -> String`
- Types: `QrConfig`, `QrErrorCorrection`, `ScanResult` → `#[derive(uniffi::Record/Enum)]`, `QrError` → `#[derive(uniffi::Error)]`

**Note on `decode_qr_from_raw`:** The raw pixel buffer comes from the camera's luminance plane. On Android, CameraX `ImageProxy.planes[0]` provides this. On iOS, `CVPixelBuffer` in `kCVPixelFormatType_420YpCbCr8BiPlanarFullRange` provides the Y plane. UniFFI maps `Vec<u8>` to `List<UByte>` (Kotlin) / `Data` (Swift) — the mobile side converts the platform buffer to the appropriate type before calling.

**Verify:** Run `uniffi-bindgen generate --language kotlin` and `--language swift` to produce binding files.

---

## Phase 4: Android Build Pipeline ✅
**Gate:** `.so` files for 3 architectures + Kotlin bindings compile in Gradle

**Create:**
- `rustySDK/scripts/build_android.sh` — cargo-ndk build → generate Kotlin bindings → copy .so + .kt to KMM
- `Makefile` at `rustySDK/` with targets: `test`, `android`, `ios`, `clean`
- `buildRustAndroid` Gradle task — wraps `make android` so Gradle can invoke it directly

**Output placement:**
```
composeApp/src/androidMain/
  jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/librusty_qr_ffi.so
  kotlin/generated/com/p2/apps/rustyqr/rust/rusty_qr_ffi.kt
```

**Gradle change** in `composeApp/build.gradle.kts`:
- Add `implementation("net.java.dev.jna:jna:5.14.0@aar")` via version catalog + `"androidMainImplementation"` config (KMP sourceSets DSL workaround)
- Add `buildRustAndroid` Exec task

**16KB page alignment:** All `.so` files are 16KB ELF-aligned (`align 2**14`) automatically via NDK 30. Compliant with Android 15+ requirement (effective November 2025). AGP 9.1 handles zip alignment at packaging time.

**CI:** GitHub Actions workflow (`.github/workflows/android.yml`) runs Rust checks → Android build with caching for cargo-ndk, Rust artifacts, NDK, and Gradle.

**Verify:** `./gradlew :composeApp:buildRustAndroid :composeApp:compileDebugKotlinAndroid` succeeds

---

## Phase 5: iOS Build Pipeline
**Gate:** `.a` files for device + simulator, XCFramework created, Swift bindings compile

**Dependency management:** SPM (Swift Package Manager), not CocoaPods. CocoaPods is in maintenance mode; SPM is built into Xcode and requires no external tooling.

**Create:** `rustySDK/scripts/build_ios.sh`
1. `cargo build --release --target aarch64-apple-ios -p rusty-qr-ffi`
2. `cargo build --release --target aarch64-apple-ios-sim -p rusty-qr-ffi`
3. `uniffi-bindgen generate --language swift`
4. `xcodebuild -create-xcframework` from both `.a` files

**Add `make ios` target** to `rustySDK/Makefile` (stub exists, wire to `build_ios.sh`)

**Place:** `iosApp/Frameworks/RustyQR.xcframework/` + Swift binding file

**Xcode:** Add XCFramework to "Frameworks, Libraries, and Embedded Content" with Embed & Sign

**CI:** GitHub Actions workflow (`.github/workflows/ios.yml`) runs on macOS runner with Xcode, builds XCFramework + Swift bindings, runs `xcodebuild build` for iOS Simulator target.

**Verify:** `make ios` succeeds, XCFramework contains both device and simulator slices, Swift binding file compiles

---

## Phase 6: Android UI + CameraX Scanner + Navigation
**Gate:** QR generates on Android emulator AND camera scanner detects QR codes and navigates to result screen

**ADR:** See `docs/adr/002-scan-to-result-navigation.md` for full navigation architecture.

**Architecture -- expect/actual bridge:**
```kotlin
// commonMain: QrBridge.kt
expect object QrBridge {
    fun generateQrPng(content: String, size: Int): ByteArray
    fun decodeQr(imageData: ByteArray): ScanResult
    fun decodeQrFromRaw(pixels: ByteArray, width: Int, height: Int): ScanResult
    fun getLibraryVersion(): String
}

// androidMain: QrBridge.android.kt -- calls UniFFI-generated Kotlin
// iosMain: QrBridge.ios.kt -- placeholder for now (wired in Phase 7)
```

**`List<UByte>` to `ByteArray` conversion** in the Android actual implementation (UniFFI maps `Vec<u8>` to `List<UByte>`, but `BitmapFactory` needs `ByteArray`).

**Navigation (commonMain):**
- **Compose Navigation** (`org.jetbrains.androidx.navigation:navigation-compose:2.9.0-alpha01`) -- official multiplatform port
- `Route` sealed interface: `Route.Scan`, `Route.Result(content, format)`, `Route.Generate`
- `NavHost` in `App.kt` with `startDestination = Route.Scan`
- Navigation events are one-shot `SharedFlow<ScanNavigationEvent>` -- NOT part of screen state

**MVI screens (commonMain):**
- `scan/` -- ScanScreenState, ScanIntent, ScanNavigationEvent, ScanViewModel, ScanScreen
- `result/` -- ResultScreenState, ResultIntent, ResultViewModel, ResultScreen, ContentTypeDetector
- `generate/` -- GenerateScreenState, GenerateIntent, GenerateViewModel, GenerateScreen
- `navigation/AppNavGraph.kt` -- Route sealed interface

**Scan to Result flow:**
1. Camera frame decoded -> `ScanIntent.FrameDecoded(result)` dispatched to ViewModel
2. ViewModel: `AtomicBoolean` scan gate locks (first-write-wins, prevents double-navigation)
3. ViewModel: `isScanning = false` (pauses analysis), emits `ScanNavigationEvent.ShowResult`
4. Composable: `LaunchedEffect` collects nav event -> `navController.navigate(Route.Result(...))`
5. Haptic feedback on successful decode
6. Result screen shows decoded content + "Open in Browser" (URL) / "Copy" / "Scan Again"
7. "Scan Again" -> `navController.popBackStack()` -> scan gate unlocks -> analysis resumes

**Camera scanning (Android -- `androidMain`):**
- **CameraX** dependency: `camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`
- `CameraPreview` composable wrapping `PreviewView` via `AndroidView`
- `ImageAnalysis` use case with `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST`
- Frame processing: extract Y (luminance) plane from `ImageProxy.planes[0]` -> convert to `ByteArray` -> call `QrBridge.decodeQrFromRaw(bytes, width, height)`
- Analyzer skips frames when `isScanning == false` (camera session stays alive -- no black flash on return)
- Runtime permission: `Manifest.permission.CAMERA` with Accompanist permissions or manual request

**Threading:**
- `generateQrPng()` -> `withContext(Dispatchers.IO)` (10-50ms, infrequent)
- `decodeQrFromRaw()` -> called from `ImageAnalysis.Analyzer` on camera thread; result dispatched to ViewModel via `viewModelScope.launch { onIntent(...) }`
- Scan gate `AtomicBoolean` provides fast-path check on camera thread before posting

---

## Phase 7: iOS UI + AVFoundation Scanner + Navigation
**Gate:** QR generates on iOS simulator AND camera scanner detects QR codes and navigates to result screen (device only -- simulator has no camera)

**ADR:** See `docs/adr/002-scan-to-result-navigation.md` for full navigation architecture.

**Approach B (Swift-side, recommended for v1):** Call Rust directly from Swift in `iosApp/`, simpler to get working. Refactor to cinterop later.

**Navigation (SwiftUI):**
- `NavigationStack` with `.navigationDestination(item:)` (requires iOS 17+)
- `ScanNavigationTarget` enum: `.result(content: String)`
- ViewModel exposes `@Published private(set) var navigationTarget: ScanNavigationTarget?`
- Set to `.result(content:)` on decode, set to `nil` on "Scan Again"

**MVI screens (iosApp/):**
```
iosApp/iosApp/
    Scan/
        ScanState.swift             -- state struct + intent enum + nav target
        ScanViewModel.swift         -- @MainActor ViewModel with scan gate
        ScanView.swift              -- ScanView (wiring) + ScanContentView (render)
        CameraPreviewView.swift     -- UIViewRepresentable for AVCaptureVideoPreviewLayer
    Result/
        ResultState.swift           -- state struct + intent enum
        ResultViewModel.swift       -- @MainActor ViewModel
        ResultView.swift            -- ResultView (wiring) + ResultContentView (render)
    Generate/
        GenerateView.swift          -- MVI screen for QR generation
```

**Scan to Result flow (mirrors Android):**
1. Camera frame decoded -> `.frameDecoded(content:)` intent sent to ViewModel
2. ViewModel: `scanGateLocked` flag locks (first-write-wins)
3. ViewModel: `isScanning = false` (delegate skips frames), sets `navigationTarget = .result(content:)`
4. SwiftUI: `NavigationStack` observes `navigationTarget`, pushes `ResultView`
5. Haptic feedback via `UIImpactFeedbackGenerator(.medium)`
6. Result screen shows decoded content + "Open in Browser" (URL) / "Copy" / "Scan Again"
7. "Scan Again" -> `.resumeScanning` intent -> gate unlocks, `navigationTarget = nil`, analysis resumes

**Camera scanning (iOS -- Swift):**
- **AVFoundation**: `AVCaptureSession` with `AVCaptureVideoDataOutput`
- Set pixel format to `kCVPixelFormatType_420YpCbCr8BiPlanarFullRange` (provides Y luminance plane)
- `AVCaptureVideoDataOutputSampleBufferDelegate` callback:
  - Guard `viewModel.state.isScanning` -- skip if false (session stays alive, no black flash)
  - Lock `CVPixelBuffer` base address, extract Y plane -> copy to `Data`
  - Call `RustyQrFfi.decodeQrFromRaw(pixels: data, width: width, height: height)` on detached task
  - On success: `Task { @MainActor in viewModel.send(.frameDecoded(content: decoded)) }`
- `SwiftUI` camera preview via `UIViewRepresentable` wrapping `AVCaptureVideoPreviewLayer`
- Runtime permission: `NSCameraUsageDescription` in `Info.plist`

**Note:** Camera does not work in iOS Simulator. Generation UI can be tested on simulator; scanning requires a physical device or can be stubbed with a test image.

---

## Phase 8: Polish & Documentation
**Gate:** All tests pass, README complete, binary size <3MB

- Finalize `Makefile` targets: `make test`, `make android`, `make ios`, `make clean`, `make all`
- Update `.gitignore`: `rustySDK/target/`, `*.so`, `*.a`, `*.xcframework`, generated bindings
- Performance validation: timing assertions in Rust tests
- Binary size verification: `ls -la` on release `.so`/`.a` files
- Update `README.md` with build instructions and architecture overview

---

## Phase Dependency Graph

```
Phase 0 (Toolchain)                ✅ DONE
    |
Phase 1 (Core: Encoder)           ✅ DONE
    |
Phase 2 (Core: Decoder + Raw Scanner + Round-Trip)
    |
Phase 3 (FFI + UniFFI)
    |
    +--- Phase 4 (Android .so)    ✅ DONE
    |         |
    |    Phase 6 (Android UI + CameraX Scanner)
    |
    +--- Phase 5 (iOS .a + XCFramework)
    |         |
    |    Phase 7 (iOS UI + AVFoundation Scanner)
    |
Phase 8 (Polish)
```

Both platform pipelines (4-5) complete before UI work (6-7). Android pipeline done first -- faster iteration, simpler JNA loading vs XCFramework.

---

## Top Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| Camera frame to Rust latency > 20ms | Scanner feels laggy | `decode_from_raw()` avoids PNG encode/decode; throttle to 10fps; benchmark early |
| `Vec<u8>` copy overhead for camera frames | Allocation per frame at 30fps | Throttle to max 10fps; 640x480 Y plane = 307KB per copy -- acceptable |
| iOS XCFramework + KMM coexistence | Linking errors | Budget extra time for Phase 5+7; use Swift-side approach (Approach B) |
| CameraX/AVFoundation API differences | Inconsistent behavior | Camera code is platform-specific by design; no shared abstraction attempted |
| Camera permission denied | Scanner non-functional | Show clear permission rationale; graceful fallback to image-picker decode |
| Xcode `project.pbxproj` fragility | Build breaks | Document manual Xcode steps; avoid scripting pbxproj edits |
| `rqrr` decode accuracy vs `rxing` | Some QR codes fail to scan | `rqrr` is well-tested for QR; if issues arise, swap to `rxing` with feature flags |

---

## Key Files to Modify

| File | Change |
|------|--------|
| `gradle/libs.versions.toml` | Add navigation-compose, CameraX, Accompanist versions |
| `composeApp/build.gradle.kts` | Add JNA, CameraX, navigation-compose, Accompanist dependencies |
| `composeApp/src/commonMain/.../App.kt` | NavHost with Route.Scan -> Route.Result -> Route.Generate |
| `composeApp/src/commonMain/.../navigation/` | Route sealed interface |
| `composeApp/src/commonMain/.../scan/` | ScanScreen MVI (state, intent, nav event, viewmodel, screen) |
| `composeApp/src/commonMain/.../result/` | ResultScreen MVI + ContentTypeDetector |
| `composeApp/src/commonMain/.../generate/` | GenerateScreen MVI |
| `composeApp/src/androidMain/...` | CameraX preview + ImageAnalysis, QrBridge actual |
| `iosApp/iosApp/Scan/` | ScanView + CameraPreviewView + ViewModel (SwiftUI + AVFoundation) |
| `iosApp/iosApp/Result/` | ResultView + ViewModel (SwiftUI) |
| `iosApp/iosApp.xcodeproj/project.pbxproj` | Link RustyQR.xcframework |
| `iosApp/iosApp/Info.plist` | Add `NSCameraUsageDescription` |
| `.gitignore` | Add Rust build artifacts |

---

## Verification (End-to-End)

1. `cd rustySDK && cargo test --workspace` -- all Rust tests pass (encoder + decoder + round-trip)
2. `cd rustySDK && cargo clippy --workspace -- -D warnings` -- no warnings
3. `make android` -- .so files + Kotlin bindings generated
4. `make ios` -- .a files + XCFramework + Swift bindings generated
5. `./gradlew :composeApp:assembleDebug` -- Android APK builds
6. Build iOS target in Xcode / `xcodebuild` -- succeeds
7. Run on Android emulator: generate QR displays correctly
8. Run on Android device: point camera at QR code -> decoded text appears in real-time
9. Run on iOS simulator: generate QR displays correctly
10. Run on iOS device: point camera at QR code -> decoded text appears in real-time
