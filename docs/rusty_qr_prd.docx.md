  
**Product Requirements Document**  
Rusty-QR — Cross-Platform QR Code Generation & Scanning Library

| Document Owner | Prasannajeet (Prasan) Pani |
| :---- | :---- |
| **Role** | Mobile Technical Lead / Staff Engineer |
| **Version** | 1.0 |
| **Date** | April 2026 |
| **Status** | Draft |
| **Classification** | Internal / Portfolio |

*This PRD defines the requirements, architecture, and delivery plan for Rusty-QR — a cross-platform QR code generation and scanning library written in Rust with auto-generated Kotlin (Android) and Swift (iOS) bindings via Mozilla’s UniFFI.*

# **Table of Contents**

**1\. Executive Summary**3

1.1 Problem Statement3

1.2 Proposed Solution3

1.3 Value Proposition3

**2\. Goals & Non-Goals**4

2.1 Goals4

2.2 Non-Goals4

**3\. User Personas & Use Cases**5

3.1 Primary Persona5

3.2 Secondary Persona5

3.3 Use Cases5

**4\. Functional Requirements**6

4.1 Generation API6

4.2 Scanning API6

4.3 Input Validation7

4.4 Output Specification7

4.5 Platform Bindings7

**5\. Non-Functional Requirements**8

5.1 Performance8

5.2 Reliability8

5.3 Compatibility8

5.4 Security8

**6\. Technical Architecture**9

6.1 Cargo Workspace Structure9

6.2 Module Responsibilities (SOLID Mapping)10

6.3 Data Flow11

6.4 Dependency Graph12

6.5 Error Hierarchy12

6.6 Testing Strategy13

6.7 Build Pipeline14

6.8 Decision Log15

**7\. API Reference**11

7.1 Functions11

7.2 Data Types12

**8\. Test Plan**13

8.1 Unit Tests (Rust)13

8.2 Integration Tests (Android)13

8.3 Integration Tests (iOS)14

8.4 Acceptance Criteria14

**9\. Delivery Plan**15

9.1 Milestones15

9.2 Dependencies & Risks15

**10\. Future Roadmap (v2+)**16

# **1\. Executive Summary**

## **1.1 Problem Statement**

Mobile teams building cross-platform features face a recurring challenge: writing and maintaining the same business logic in two languages (Kotlin and Swift). Performance-critical modules like image processing, encoding, and cryptography often require platform-specific native code, leading to duplicated effort, divergent behavior, and increased bug surface area.

## **1.2 Proposed Solution**

Rusty-QR is a QR code generation and scanning library written in Rust that compiles to native binaries for both Android (.so) and iOS (.a), with auto-generated idiomatic language bindings via Mozilla’s UniFFI. The library demonstrates a “write once, bind everywhere” pattern that can be extended to any shared native module.

## **1.3 Value Proposition**

•  **Single source of truth:** One Rust codebase serves both platforms, eliminating logic drift between Android and iOS implementations.

•  **Zero JNI/C boilerplate:** UniFFI auto-generates idiomatic Kotlin and Swift bindings, removing the need for manual JNI, C bridging headers, or FFI glue code.

•  **Native performance:** Rust compiles to machine code with no garbage collector overhead — predictable latency, no GC pauses.

•  **Memory safety:** Rust’s ownership model guarantees memory safety at compile time — no use-after-free, double-free, or data race bugs in the shared library.

•  **Portfolio demonstration:** Showcases the author’s ability to work across the mobile stack, from systems-level Rust to platform-specific UI integration.

# **2\. Goals & Non-Goals**

## **2.1 Goals**

| \# | Goal | Success Metric |
| :---- | :---- | :---- |
| G1 | Generate QR codes as PNG byte arrays from a simple API | cargo test passes with valid PNG output |
| G2 | Decode/scan QR codes from PNG byte arrays | cargo test decodes a known QR image correctly |
| G3 | Compile to .so (Android) and .a (iOS) native libraries | Successful builds for ARM64, ARMv7, x86\_64 |
| G4 | Auto-generate Kotlin bindings via UniFFI | Generated .kt files compile and run in Android project |
| G5 | Auto-generate Swift bindings via UniFFI | Generated .swift files compile and run in iOS project |
| G6 | Expose typed error handling to both platforms | Kotlin sealed exceptions, Swift enum errors |
| G7 | Provide a working KMM demo app for both platforms | Compose (Android) \+ SwiftUI (iOS) via shared KMM module generate and scan QR codes |
| G8 | Document the build-to-integration pipeline end-to-end | README covers install → build → integrate |

## **2.2 Non-Goals**

| \# | Non-Goal | Rationale |
| :---- | :---- | :---- |
| NG1 | Live camera-based scanning | Requires platform-specific camera APIs; library accepts image bytes only |
| NG2 | Custom QR styling (colors, logos, rounded modules) | Adds complexity; standard B\&W is v1 scope |
| NG3 | React Native or Flutter bindings | Focus on native platforms first |
| NG4 | Publishing to crates.io, Maven, or CocoaPods | Portfolio project, not a public distribution |
| NG5 | CI/CD pipeline for automated cross-compilation | Manual build scripts are sufficient for v1 |
| NG6 | Server-side QR generation API | This is a client-side mobile library |
| NG7 | Barcode scanning (non-QR formats) | Scope limited to QR codes; barcode support is v3+ |

# **3\. User Personas & Use Cases**

## **3.1 Primary Persona: Mobile Developer (Self)**

| Name | Prasan — Staff-level Android/Mobile engineer |
| :---- | :---- |
| **Context** | Building a portfolio piece to demonstrate cross-platform systems expertise |
| **Goal** | Write a Rust library, compile to native, and integrate with a KMM app (Android \+ iOS via shared module) |
| **Pain Point** | Manual JNI/C bridging is tedious, error-prone, and hard to maintain |

## **3.2 Secondary Persona: Mobile Team Lead**

| Context | Evaluating whether Rust \+ UniFFI is viable for shared cross-platform modules |
| :---- | :---- |
| **Goal** | Understand the developer experience, build complexity, and maintenance burden |
| **Needs** | Clear documentation, reproducible builds, and working example code |

## **3.3 Use Cases**

| UC\# | As a… | I want to… | So that… |
| :---- | :---- | :---- | :---- |
| UC1 | Mobile developer | Generate a QR code from a string | I can display it in my app’s UI |
| UC2 | Mobile developer | Configure QR size and quiet zone | I can customize the output for my layout |
| UC3 | Mobile developer | Decode a QR code from image bytes | I can extract data from a QR image the user provides |
| UC4 | Mobile developer | Decode multiple QR codes from one image | I can handle images with multiple QR codes |
| UC5 | Mobile developer | Receive typed errors from the library | I can handle failures gracefully in Kotlin/Swift |
| UC6 | Mobile developer | Build the library with one script | I don’t need to learn Rust build internals |
| UC7 | Interviewer/reviewer | See working Android \+ iOS examples | I can evaluate the candidate’s cross-platform skills |

# **4\. Functional Requirements**

## **4.1 Generation API**

| Priority | Requirement | Description | Status |
| :---- | :---- | :---- | :---- |
| **P0** | generate\_qr\_png(content, size) | Accepts a string and pixel size, returns PNG bytes as Vec\<u8\> | Implemented |
| **P0** | generate\_qr\_with\_config(config) | Accepts QrConfig struct with content, size, quiet\_zone options | Implemented |
| **P0** | get\_library\_version() | Returns the crate version string for runtime debugging | Implemented |
| **P1** | QrError enum | Typed errors: InvalidInput, EncodingFailed, RenderFailed, DecodeFailed | Implemented |
| **P1** | QrConfig record | Configuration struct: content (String), size (u32), quiet\_zone (bool) | Implemented |
| **P1** | QrResult record | Result struct: png\_data (Vec\<u8\>), width (u32), height (u32) | Implemented |

## **4.2 Scanning / Decoding API**

| Priority | Requirement | Description | Status |
| :---- | :---- | :---- | :---- |
| **P0** | decode\_qr(image\_data) | Accepts PNG/JPEG bytes, returns decoded text content | Planned |
| **P0** | decode\_qr\_multi(image\_data) | Accepts PNG/JPEG bytes, returns all QR codes found in image | Planned |
| **P1** | ScanResult record | Result struct: content (String), bounds (optional position data) | Planned |
| **P1** | DecodeFailed error variant | Added to QrError enum when no QR code is found or image is unreadable | Planned |
| **P2** | decode\_qr\_from\_raw(pixels, w, h) | Accepts raw grayscale pixel buffer for direct camera frame decoding | Planned |

## **4.3 Input Validation**

| Priority | Requirement | Description | Status |
| :---- | :---- | :---- | :---- |
| **P0** | Empty content rejection (gen) | Return QrError::InvalidInput if content is empty string | Implemented |
| **P0** | Size bounds checking (gen) | Reject size \= 0 or size \> 4096 with InvalidInput error | Implemented |
| **P0** | Empty image rejection (scan) | Return QrError::InvalidInput if image\_data is empty | Planned |
| **P0** | Invalid image format (scan) | Return QrError::DecodeFailed if bytes are not valid PNG/JPEG | Planned |
| **P1** | Content length validation | Return EncodingFailed if content exceeds QR capacity | Delegated to qrcode crate |
| **P2** | Image size limits (scan) | Reject images larger than 10MP to prevent OOM | Planned |

## **4.4 Output Specification**

### **Generation Output**

| Property | Specification |
| :---- | :---- |
| Format | PNG (Portable Network Graphics) |
| Color space | Grayscale (Luma8) — black modules on white background |
| Minimum dimensions | As specified by size parameter (actual may be larger due to QR grid) |
| Quiet zone | Configurable via QrConfig.quiet\_zone (default: true) |
| Error correction | QR default (Level M — \~15% recovery) |
| PNG header | Valid PNG magic bytes: 0x89 0x50 0x4E 0x47 |

### **Scanning Output**

| Property | Specification |
| :---- | :---- |
| Decoded content | UTF-8 string extracted from QR code |
| Multi-code support | Returns Vec\<ScanResult\> for images with multiple QR codes |
| Supported input formats | PNG, JPEG (decoded via image crate) |
| No-QR-found behavior | Returns QrError::DecodeFailed with descriptive reason |

## **4.5 Platform Bindings (Auto-Generated)**

| Platform | Binding Type | Error Mapping | Data Mapping |
| :---- | :---- | :---- | :---- |
| Android/Kotlin | Kotlin functions \+ data classes | QrError → sealed exception subclasses | Vec\<u8\> → List\<UByte\> |
| iOS/Swift | Swift functions \+ structs | QrError → Swift Error enum with associated values | Vec\<u8\> → \[UInt8\] |

# **5\. Non-Functional Requirements**

## **5.1 Performance**

| Metric | Target | Measurement Method |
| :---- | :---- | :---- |
| QR generation (256px) | \< 10ms on modern device | Benchmark with cargo bench |
| QR generation (1024px) | \< 50ms on modern device | Benchmark with cargo bench |
| QR scanning (256px image) | \< 20ms on modern device | Benchmark with cargo bench |
| QR scanning (1080p image) | \< 100ms on modern device | Benchmark with cargo bench |
| Memory allocation | \< 5MB peak for 1024px QR | Measure with Instruments / Android Profiler |
| Binary size (.so ARM64) | \< 3MB stripped | ls \-la on release build |

## **5.2 Reliability**

| Requirement | Specification |
| :---- | :---- |
| Crash rate | Zero panics in library code — all errors returned via Result\<T, E\> |
| Thread safety | All exported functions are stateless and safe to call from any thread |
| Memory safety | Guaranteed by Rust’s ownership system at compile time |
| Determinism | Same input always produces same output (no randomness in QR encoding) |

## **5.3 Compatibility**

| Platform | Minimum Target | Architectures |
| :---- | :---- | :---- |
| Android | API 21 (Lollipop 5.0) | arm64-v8a, armeabi-v7a, x86\_64, x86 |
| iOS | iOS 13.0 | arm64 (device), arm64-sim, x86\_64-sim |
| Rust | Edition 2021, MSRV 1.70+ | N/A |

## **5.4 Security**

| Concern | Mitigation |
| :---- | :---- |
| Input injection | Content is treated as opaque bytes; no shell execution or code evaluation |
| Buffer overflow | Impossible in safe Rust — all array accesses are bounds-checked |
| Dependency supply chain | Only well-known crates (qrcode, rxing, image) from crates.io; audit with cargo-audit |
| Sensitive data | Library does not store, log, or transmit any input data |

# **6\. Technical Architecture**

Rusty-QR follows a Cargo workspace architecture with two crates, mapping cleanly to Clean Architecture’s layered dependency rule: the core crate owns all business logic with zero knowledge of FFI, and the FFI crate depends inward on the core to expose bindings.

## **6.1 Cargo Workspace Structure**

The project uses a Cargo workspace with two crates rather than a single crate. This enforces separation of concerns at the compiler level — the core crate physically cannot import UniFFI types, guaranteeing that business logic stays portable.

| Path | Crate | Clean Architecture Layer | Responsibility |
| :---- | :---- | :---- | :---- |
| rusty-qr/ | Workspace root | — | Cargo.toml workspace definition, CI config, README |
| rusty-qr/crates/core/ | rusty-qr-core | Domain \+ Data | QR encoding, decoding, image rendering, validation, error types. Zero FFI knowledge. |
| rusty-qr/crates/ffi/ | rusty-qr-ffi | Interface Adapters | UniFFI annotations, re-exports core types for binding generation. Thin wrapper only. |

### **Directory Layout**

| File / Directory | Purpose |
| :---- | :---- |
| Cargo.toml | Workspace definition: members \= \["crates/core", "crates/ffi"\] |
| README.md | Project overview, build instructions, usage examples |
| Makefile | Convenience targets: make test, make android, make ios, make clean |
| .github/workflows/ci.yml | GitHub Actions: cargo test \+ cargo clippy \+ cargo fmt \--check |
| crates/core/Cargo.toml | Core crate deps: qrcode, rxing, image, thiserror. No UniFFI. |
| crates/core/src/lib.rs | Public API re-exports: pub mod encoder; pub mod decoder; pub mod error; |
| crates/core/src/encoder.rs | QR generation: content → QR matrix → PNG bytes |
| crates/core/src/decoder.rs | QR scanning: image bytes → decoded content strings |
| crates/core/src/error.rs | QrError enum with thiserror derives |
| crates/core/src/types.rs | Shared types: QrConfig, ScanResult, ErrorCorrectionLevel |
| crates/ffi/Cargo.toml | FFI crate deps: rusty-qr-core (path), uniffi |
| crates/ffi/src/lib.rs | \#\[uniffi::export\] wrappers calling into core. UniFFI scaffolding macro. |
| crates/ffi/uniffi.toml | UniFFI config: Kotlin package name, Swift module name |
| crates/ffi/build.rs | UniFFI build script for binding generation |
| scripts/build\_android.sh | cargo ndk build for arm64-v8a, armeabi-v7a, x86\_64 targets |
| scripts/build\_ios.sh | cargo build for aarch64-apple-ios \+ lipo \+ xcframework generation |
| app/ | KMM demo app root (Kotlin Multiplatform project) |
| app/shared/ | KMP shared module: wraps Rust bindings into cross-platform Kotlin API |
| app/androidApp/ | Android target (Jetpack Compose). Loads .so via JNA. |
| app/iosApp/ | iOS target (SwiftUI). Links XCFramework. |

*Why two crates instead of one? The Single Responsibility Principle. The core crate’s job is QR logic. The FFI crate’s job is bridging to mobile platforms. If you later want to add a C API, a WASM target, or a Python binding, you add a new crate that depends on core — core never changes. This is the Open/Closed Principle applied at the crate level.*

## **6.2 Module Responsibilities (SOLID Mapping)**

| Module | Responsibility | SOLID Principle | Design Rationale |
| :---- | :---- | :---- | :---- |
| encoder.rs | Accepts content string \+ config, returns PNG bytes | Single Responsibility | Encoding is one concern. It doesn’t know about decoding, FFI, or error presentation. |
| decoder.rs | Accepts image bytes, returns decoded content strings | Single Responsibility | Decoding is one concern. Fully independent of encoding. |
| error.rs | Defines QrError enum with all error variants | Single Responsibility | Error types are centralized. Both encoder and decoder use the same hierarchy. |
| types.rs | Defines QrConfig, ScanResult, ErrorCorrectionLevel | Interface Segregation | Shared types are extracted so consumers only import what they need. |
| lib.rs (core) | Re-exports public API surface | Dependency Inversion | Consumers depend on the public API, not internal module structure. |
| lib.rs (ffi) | Thin UniFFI wrapper around core functions | Open/Closed | Core is closed for modification. FFI extends it for new platforms without changing core code. |

### **Dependency Rule**

Dependencies flow strictly inward, following Clean Architecture:

| Layer | Can Depend On | Cannot Depend On |
| :---- | :---- | :---- |
| KMM app (app/) | rusty-qr-ffi (via generated bindings), KMP shared module | — |
| rusty-qr-ffi (interface adapters) | rusty-qr-core | Platform SDKs, example apps |
| rusty-qr-core (domain) | qrcode, rxing, image, thiserror | rusty-qr-ffi, UniFFI, platform SDKs |

This means you can run cargo test in crates/core/ with zero mobile toolchain installed. The core crate compiles on any Rust target (Linux, macOS, Windows, WASM).

### **FFI Layer Design (rusty-qr-ffi)**

The FFI crate is deliberately thin. Each function is a one-liner that delegates to core:

| FFI Function | Delegates To | UniFFI Annotation |
| :---- | :---- | :---- |
| generate\_qr\_png(content, size) | core::encoder::generate\_png(content, config) | \#\[uniffi::export\] |
| generate\_qr\_svg(content, size) | core::encoder::generate\_svg(content, config) | \#\[uniffi::export\] |
| decode\_qr(image\_data) | core::decoder::decode(image\_data) | \#\[uniffi::export\] |
| QrConfig, ScanResult, QrError | Re-exported from core::types / core::error | \#\[derive(uniffi::Record/Enum/Error)\] |

*The FFI layer adds NO business logic. If you find yourself writing an if statement in the FFI crate, it belongs in core. This rule ensures all logic is tested in pure Rust without FFI overhead.*

## **6.3 Data Flow**

### **Generation Flow**

The request-response flow for QR generation follows a synchronous, stateless pattern:

| Step | Actor | Action | Data |
| :---- | :---- | :---- | :---- |
| 1 | Mobile App | Calls generateQrPng(content, size) | String \+ u32 |
| 2 | UniFFI Layer (ffi) | Marshals Kotlin/Swift types to Rust types | FFI conversion |
| 3 | Core: encoder.rs | Validates input (empty check, size bounds) | Returns QrError if invalid |
| 4 | Core: encoder.rs | Encodes content as QR matrix via qrcode crate | QR code data matrix |
| 5 | Core: encoder.rs | Renders matrix to grayscale image via image crate | Luma8 pixel buffer |
| 6 | Core: encoder.rs | Encodes pixel buffer as PNG bytes | Vec\<u8\> |
| 7 | UniFFI Layer (ffi) | Marshals Vec\<u8\> to platform type | List\<UByte\> / \[UInt8\] |
| 8 | Mobile App | Decodes PNG bytes to Bitmap/UIImage | Displayed in UI |

### **Scanning Flow**

The scanning flow reverses the generation pipeline:

| Step | Actor | Action | Data |
| :---- | :---- | :---- | :---- |
| 1 | Mobile App | Captures/selects image, gets bytes | PNG/JPEG bytes |
| 2 | Mobile App | Calls decodeQr(imageData) | Vec\<u8\> (image bytes) |
| 3 | UniFFI Layer (ffi) | Marshals platform bytes to Rust Vec\<u8\> | FFI conversion |
| 4 | Core: decoder.rs | Validates input (non-empty, valid image) | Returns QrError if invalid |
| 5 | Core: decoder.rs | Decodes image bytes to pixel buffer via image crate | DynamicImage |
| 6 | Core: decoder.rs | Converts to grayscale luminance buffer | Luma8 pixel buffer |
| 7 | Core: decoder.rs | Scans for QR codes via rxing crate | Decoded text content(s) |
| 8 | UniFFI Layer (ffi) | Marshals String/Vec\<ScanResult\> to platform type | FFI conversion |
| 9 | Mobile App | Receives decoded content string(s) | Displayed / processed |

### **Round-Trip Invariant**

The architecture guarantees a critical property: for any valid input content, decode(encode(content)) \== content. This is the primary integration test that validates the entire pipeline.

## **6.4 Dependency Graph**

| Crate | Version | Used In | Purpose | License |
| :---- | :---- | :---- | :---- | :---- |
| qrcode | 0.14 | core | QR code encoding (data matrix generation) | MIT / Apache-2.0 |
| rxing | 0.6 | core | QR code decoding / scanning (Rust port of ZXing) | Apache-2.0 |
| image | 0.25 | core | Image rendering, PNG/JPEG encoding and decoding | MIT / Apache-2.0 |
| thiserror | 2.x | core | Ergonomic error type derivation | MIT / Apache-2.0 |
| uniffi | 0.28 | ffi only | FFI binding generation (Kotlin \+ Swift) | MPL-2.0 |

*Note: uniffi appears only in the ffi crate. The core crate has zero FFI dependencies. If UniFFI releases a breaking change, only the ffi crate needs updating.*

## **6.5 Error Hierarchy**

Rusty-QR uses a single QrError enum derived via thiserror. All error variants carry human-readable messages and map to platform-native exception types through UniFFI.

| Variant | When Thrown | Contains | Severity |
| :---- | :---- | :---- | :---- |
| InvalidInput(String) | Empty content, size \< 1 or \> 4096, empty image bytes | Descriptive message explaining which input was invalid | Caller error — never retry |
| EncodingFailed(String) | Content exceeds QR capacity, unsupported characters for ECL | Internal error detail from qrcode crate | Possibly recoverable — try lower ECL |
| RenderFailed(String) | Image encoding fails (memory, invalid dimensions) | Internal error detail from image crate | Unlikely — indicates system issue |
| DecodeFailed(String) | No QR code found in image, corrupt image data | Detail of what went wrong | Expected — not all images contain QR codes |
| ImageFormatError(String) | Image bytes are not valid PNG/JPEG | Format detection error | Caller error — validate before calling |

### **Error Design Principles**

•  **No panics in library code.** Every function returns Result\<T, QrError\>. The only panic-capable code is in test assertions.

•  **Errors carry context.** Each variant includes a String message explaining what went wrong, not just a type tag.

•  **Errors are UniFFI-compatible.** QrError derives uniffi::Error in the ffi crate, mapping to Kotlin exceptions and Swift error enums automatically.

### **Platform Error Mapping**

| Rust | Kotlin (Generated) | Swift (Generated) |
| :---- | :---- | :---- |
| QrError::InvalidInput(msg) | throw QrException.InvalidInput(msg) | QrError.invalidInput(message: msg) |
| QrError::EncodingFailed(msg) | throw QrException.EncodingFailed(msg) | QrError.encodingFailed(message: msg) |
| QrError::DecodeFailed(msg) | throw QrException.DecodeFailed(msg) | QrError.decodeFailed(message: msg) |
| Ok(value) | return value | return value |

## **6.6 Testing Strategy**

Testing is organized into four tiers, each with a clear purpose and location:

| Tier | Location | What It Tests | Run With |
| :---- | :---- | :---- | :---- |
| Unit Tests | crates/core/src/\*.rs (\#\[cfg(test)\]) | Individual functions in isolation | cargo test \-p rusty-qr-core |
| Integration Tests | crates/core/tests/ | Cross-module: encode → decode round-trip | cargo test \-p rusty-qr-core |
| FFI Tests | crates/ffi/tests/ | UniFFI type marshaling, error mapping | cargo test \-p rusty-qr-ffi |
| Platform Tests | app/shared/src/commonTest/, app/androidApp/ | End-to-end from KMM shared module \+ platform UIs | Gradle test runner |

### **Required Test Cases (Core)**

| Test | Category | Validates |
| :---- | :---- | :---- |
| encode\_simple\_text | Unit | Basic QR generation produces non-empty PNG bytes |
| encode\_empty\_content\_fails | Unit | Empty string returns QrError::InvalidInput |
| encode\_size\_zero\_fails | Unit | Size 0 returns QrError::InvalidInput |
| encode\_size\_exceeds\_max\_fails | Unit | Size \> 4096 returns QrError::InvalidInput |
| encode\_all\_ecl\_levels | Unit | L, M, Q, H error correction levels all produce valid output |
| decode\_valid\_qr | Unit | Known QR image bytes decode to expected content |
| decode\_no\_qr\_in\_image | Unit | Plain image returns QrError::DecodeFailed |
| decode\_empty\_bytes\_fails | Unit | Empty Vec\<u8\> returns QrError::InvalidInput |
| decode\_corrupt\_image\_fails | Unit | Random bytes return QrError::ImageFormatError |
| round\_trip\_ascii | Integration | encode then decode returns original ASCII content |
| round\_trip\_unicode | Integration | encode then decode returns original Unicode content |
| round\_trip\_url | Integration | encode then decode returns original URL with special chars |
| round\_trip\_long\_content | Integration | 2000+ char content encodes and decodes correctly |
| svg\_output\_is\_valid\_xml | Unit | SVG generation produces well-formed XML |

### **Coverage Target**

Target: 90%+ line coverage on crates/core. Measure with cargo-tarpaulin or cargo-llvm-cov. The FFI crate is intentionally thin and relies on core’s tests plus UniFFI’s own type-safety guarantees.

## **6.7 Build Pipeline**

### **Android Build Pipeline**

| Step | Command / Tool | Output |
| :---- | :---- | :---- |
| 1\. Install targets | rustup target add aarch64-linux-android armv7-linux-androideabi x86\_64-linux-android | Cross-compile targets installed |
| 2\. Install cargo-ndk | cargo install cargo-ndk | Android NDK build helper |
| 3\. Build .so files | cargo ndk \-t arm64-v8a \-t armeabi-v7a \-t x86\_64 build \--release \-p rusty-qr-ffi | jniLibs/\<arch\>/librusty\_qr\_ffi.so |
| 4\. Generate bindings | cargo run \-p rusty-qr-ffi \--bin uniffi-bindgen generate \--language kotlin | rusty\_qr.kt generated |
| 5\. Copy to project | cp .so → app/androidApp/src/main/jniLibs/\<arch\>/ | KMM Android target ready |
| 6\. Build app | cd app && ./gradlew androidApp:assembleDebug | .apk with native lib via KMM |

### **iOS Build Pipeline**

| Step | Command / Tool | Output |
| :---- | :---- | :---- |
| 1\. Install targets | rustup target add aarch64-apple-ios aarch64-apple-ios-sim | Cross-compile targets installed |
| 2\. Build device .a | cargo build \--release \--target aarch64-apple-ios \-p rusty-qr-ffi | librusty\_qr\_ffi.a (device) |
| 3\. Build sim .a | cargo build \--release \--target aarch64-apple-ios-sim \-p rusty-qr-ffi | librusty\_qr\_ffi.a (simulator) |
| 4\. Create XCFramework | xcodebuild \-create-xcframework ... | Rusty-QR.xcframework |
| 5\. Generate bindings | uniffi-bindgen generate \--language swift | Rusty-QR.swift generated |

### **CI Pipeline (GitHub Actions)**

| Job | Triggers | Steps | Fail Condition |
| :---- | :---- | :---- | :---- |
| test | Every push \+ PR | cargo fmt \--check, cargo clippy \-- \-D warnings, cargo test \--workspace | Any warning, test failure, or format diff |
| android-build | PR to main only | Install NDK, cargo ndk build, generate Kotlin bindings | Build failure or binding generation error |
| ios-build | PR to main (macOS runner) | cargo build iOS targets, create XCFramework | Build failure |
| coverage | Weekly schedule | cargo-tarpaulin \--workspace, upload to Codecov | Coverage drops below 85% |

## **6.8 Decision Log**

Key architectural decisions and their rationale, documented for interview discussions:

| Decision | Alternatives Considered | Rationale |
| :---- | :---- | :---- |
| Rust over C/C++ | C, C++, Kotlin Native | Memory safety without GC. No use-after-free, no buffer overflows. UniFFI only supports Rust. Aligns with 1Password’s stack. |
| UniFFI over manual JNI | Hand-written JNI, cbindgen, Diplomat | Auto-generates idiomatic Kotlin \+ Swift. Manual JNI is error-prone. UniFFI is Mozilla-backed and production-proven. |
| Cargo workspace over single crate | Single crate with feature flags | Workspace enforces dependency direction at compile time. Feature flags still allow accidental imports across boundaries. |
| rxing over bardecoder | bardecoder, quircs, ZXing via JNI | rxing is the most mature Rust QR decoder (ZXing port). ZXing via JNI defeats the purpose of a Rust library. |
| thiserror over anyhow | anyhow, manual error impl | thiserror is for libraries (typed errors). anyhow is for applications (opaque errors). Libraries must give typed variants. |
| PNG bytes over Bitmap/UIImage | Return platform image types | Raw bytes keep core platform-agnostic. Each platform converts trivially (BitmapFactory / UIImage(data:)). |
| Grayscale QR over colored | Colored, branded QR codes | v1 scope constraint. Color/branding is a v2 feature. |

*These decisions are interview gold. When asked “Why a workspace instead of one crate?” you have a documented answer connecting to SOLID and Clean Architecture.*

# **7\. API Reference**

## **7.1 Functions**

### **generate\_qr\_png**

| Property | Value |
| :---- | :---- |
| Signature | fn generate\_qr\_png(content: String, size: u32) \-\> Result\<Vec\<u8\>, QrError\> |
| Description | Generate a QR code as PNG bytes from a content string |
| Parameters | content: The text/URL to encode. size: Image width/height in pixels (1–4096) |
| Returns | PNG image data as byte array |
| Errors | InvalidInput, EncodingFailed, RenderFailed |
| Thread Safety | Safe to call from any thread (stateless) |

### **generate\_qr\_with\_config**

| Property | Value |
| :---- | :---- |
| Signature | fn generate\_qr\_with\_config(config: QrConfig) \-\> Result\<QrResult, QrError\> |
| Description | Generate a QR code with full configuration options |
| Parameters | config: QrConfig struct with content, size, quiet\_zone fields |
| Returns | QrResult struct with png\_data, width, height fields |
| Errors | InvalidInput, EncodingFailed, RenderFailed |

### **get\_library\_version**

| Property | Value |
| :---- | :---- |
| Signature | fn get\_library\_version() \-\> String |
| Description | Returns the semantic version of the compiled library |
| Returns | Version string (e.g. "0.1.0") |
| Errors | None (infallible) |

### **decode\_qr**

| Property | Value |
| :---- | :---- |
| Signature | fn decode\_qr(image\_data: Vec\<u8\>) \-\> Result\<String, QrError\> |
| Description | Decode a QR code from PNG or JPEG image bytes and return its text content |
| Parameters | image\_data: Raw PNG or JPEG bytes (e.g. from file read or camera capture) |
| Returns | Decoded text content as String |
| Errors | InvalidInput (empty bytes), DecodeFailed (no QR found or invalid image) |
| Thread Safety | Safe to call from any thread (stateless) |

### **decode\_qr\_multi**

| Property | Value |
| :---- | :---- |
| Signature | fn decode\_qr\_multi(image\_data: Vec\<u8\>) \-\> Result\<Vec\<ScanResult\>, QrError\> |
| Description | Decode all QR codes found in a PNG or JPEG image |
| Parameters | image\_data: Raw PNG or JPEG bytes |
| Returns | Vec\<ScanResult\> containing content of each QR code found |
| Errors | InvalidInput (empty bytes), DecodeFailed (invalid image format) |

## **7.2 Data Types**

### **QrConfig (Record)**

| Field | Type | Required | Default | Description |
| :---- | :---- | :---- | :---- | :---- |
| content | String | Yes | — | Text or URL to encode |
| size | u32 | Yes | — | Image dimension in pixels (1–4096) |
| quiet\_zone | bool | Yes | — | Whether to include white border around QR |

### **QrResult (Record)**

| Field | Type | Description |
| :---- | :---- | :---- |
| png\_data | Vec\<u8\> | Raw PNG image bytes |
| width | u32 | Actual image width in pixels |
| height | u32 | Actual image height in pixels |

### **ScanResult (Record)**

| Field | Type | Description |
| :---- | :---- | :---- |
| content | String | Decoded text content from the QR code |
| format | String | Code format identifier (e.g. "QR\_CODE") |

### **QrError (Error Enum)**

| Variant | Fields | Description |
| :---- | :---- | :---- |
| InvalidInput | reason: String | Input failed validation (empty content, bad size, empty image) |
| EncodingFailed | reason: String | QR encoding rejected the content |
| RenderFailed | reason: String | PNG image rendering failed |
| DecodeFailed | reason: String | No QR code found in image or image format unreadable |

# **8\. Test Plan**

## **8.1 Unit Tests (Rust)**

All unit tests live in src/lib.rs under \#\[cfg(test)\] and run via cargo test.

### **Generation Tests**

| Test | Category | Validates |
| :---- | :---- | :---- |
| generates\_valid\_png | Happy path | Output is valid PNG (magic bytes check) |
| generates\_with\_config | Happy path | Config-based generation returns correct dimensions |
| rejects\_empty\_content | Validation | Empty string returns InvalidInput error |
| rejects\_invalid\_size | Validation | Size 0 and 5000 both return InvalidInput error |
| returns\_library\_version | Utility | Version matches Cargo.toml version |

### **Scanning Tests**

| Test | Category | Validates |
| :---- | :---- | :---- |
| decodes\_generated\_qr | Round-trip | Generate a QR, then decode it — content matches original |
| decodes\_known\_qr\_image | Happy path | Decodes a pre-built QR PNG with known content |
| decodes\_multi\_qr | Happy path | Finds multiple QR codes in a single image |
| rejects\_empty\_image\_data | Validation | Empty byte array returns InvalidInput error |
| rejects\_invalid\_image | Validation | Random non-image bytes return DecodeFailed error |
| rejects\_image\_without\_qr | Edge case | Valid PNG with no QR code returns DecodeFailed |

## **8.2 Integration Tests (Android)**

| Test | Validates |
| :---- | :---- |
| Load native library | JNA loads librusty\_qr\_ffi.so via KMM shared module without crash |
| Generate and display QR | Output bytes decode to valid Bitmap via BitmapFactory |
| Scan QR from image | decodeQr() returns correct content from a QR Bitmap |
| Round-trip generate+scan | Generate QR, then scan it — content matches |
| Error handling (generate) | QrError exceptions are catchable with typed sealed classes |
| Error handling (scan) | DecodeFailed exception thrown for non-QR images |
| Thread safety | Concurrent calls from Dispatchers.IO don’t crash |

## **8.3 Integration Tests (iOS)**

| Test | Validates |
| :---- | :---- |
| Load native library | XCFramework links and Rusty-QR module imports |
| Generate and display QR | Output bytes convert to valid UIImage |
| Scan QR from image | decodeQr() returns correct content from a QR UIImage |
| Round-trip generate+scan | Generate QR, then scan it — content matches |
| Error handling (generate) | QrError cases are catchable with do/catch |
| Error handling (scan) | DecodeFailed case thrown for non-QR images |
| Thread safety | Concurrent calls from DispatchQueue.global don’t crash |

## **8.4 Acceptance Criteria**

*The project is considered complete when all of the following are true:*

•  cargo test passes with all generation and scanning tests green

•  Round-trip test: generate\_qr\_png → decode\_qr returns original content

•  Android .so builds succeed for arm64-v8a, armeabi-v7a, x86\_64

•  iOS .a builds succeed for aarch64-apple-ios and aarch64-apple-ios-sim

•  UniFFI generates valid Kotlin bindings that compile in an Android project

•  UniFFI generates valid Swift bindings that compile in an Xcode project

•  Example Compose screen generates AND scans a QR code

•  Example SwiftUI view generates AND scans a QR code

# **9\. Delivery Plan**

## **9.1 Milestones**

| Phase | Deliverable | Duration | Gate |
| :---- | :---- | :---- | :---- |
| Phase 1 | Install Rust, build & test generation locally | 1 session (1–2 hrs) | cargo test — generation tests pass |
| Phase 2 | Implement scanning API \+ round-trip tests | 1 session (2–3 hrs) | cargo test — all tests pass incl. scanning |
| Phase 3 | Build .so for Android \+ generate Kotlin bindings | 1 session (2–3 hrs) | .so files exist \+ .kt files compile |
| Phase 4 | Integrate into KMM app (Android target \+ Compose UI) | 1 session (2–3 hrs) | QR generates \+ scans on device/emulator |
| Phase 5 | Build .a for iOS \+ generate Swift bindings | 1 session (2–3 hrs) | .a files exist \+ .swift files compile |
| Phase 6 | Integrate into KMM app (iOS target \+ SwiftUI UI) | 1 session (2–3 hrs) | QR generates \+ scans on iOS simulator |
| Phase 7 | Documentation, cleanup, portfolio packaging | 1 session (1–2 hrs) | README complete, GitHub-ready |

## **9.2 Dependencies & Risks**

| Risk | Impact | Likelihood | Mitigation |
| :---- | :---- | :---- | :---- |
| Android NDK path configuration | Blocks Android build | Medium | build\_android.sh auto-detects common paths |
| UniFFI version compatibility | Binding generation fails | Low | Pin to uniffi 0.28; tested version |
| iOS signing / XCFramework issues | Blocks iOS integration | Medium | Separate build script handles XCFramework creation |
| Large .so binary size | App size concern | Low | Use \--release \+ strip; expect \<3MB per arch |
| qrcode crate content limits | Some inputs fail | Low | Return typed error; document QR size limits |
| rxing crate decode accuracy | Some QR images fail to scan | Medium | Test with varied image quality; return clear error messages |
| rxing binary size impact | Adds to .so/.a size | Low | Use feature flags to include only QR decoder, not all barcode formats |

# **10\. Future Roadmap (v2+)**

| Version | Feature | Description |
| :---- | :---- | :---- |
| v1.1 | Custom colors | Foreground/background color parameters for QR modules |
| v1.1 | Error correction level | Expose Low/Medium/Quartile/High EC selection |
| v1.2 | SVG output | Generate QR as SVG string (smaller, scalable) |
| v1.2 | decode\_qr\_from\_raw() | Accept raw grayscale pixel buffer for direct camera frame decoding |
| v2.0 | Live camera pipeline | Platform-specific camera frame → Rust decoder streaming pipeline |
| v2.0 | Batch generation | Generate multiple QR codes in a single call |
| v2.1 | Barcode support | Extend scanning to support Code128, EAN-13, UPC-A via rxing |
| v3.0 | React Native bindings | Extend UniFFI output or use manual JSI bridge |

*End of document. This PRD is a living document and should be updated as requirements evolve.*