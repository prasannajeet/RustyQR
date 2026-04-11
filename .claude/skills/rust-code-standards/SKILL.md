---
name: rust-code-standards
description: Enforces Rust code standards — Result-based error handling, thiserror + UniFFI-safe errors, crate architecture, dependency management, testing, and documentation.
user-invocable: false
---

# Rust Code Standards

These standards MUST be followed in all Rust code across `rustySDK/crates/core/` and
`rustySDK/crates/ffi/`.

---

## 1. Event-Based Architecture — Command In, Response Out

**The Rust core library is a pure function machine. Every public operation
follows: `Command → Result<Response, QrError>`. No hidden state, no side effects, no global
mutability.**

This mirrors the MVI pattern on the mobile side (Intent → State), making the entire pipeline
predictable:

```
Mobile:   Intent → ViewModel → QrBridge → Rust Command → Result<Response> → State → View
```

### Command and Response Types

```rust
/// Commands that can be sent to the QR engine.
/// Each variant maps 1:1 to a mobile Intent that crosses the FFI boundary.
#[derive(Debug, Clone)]
pub enum QrCommand {
    /// Generate a QR code PNG from text content.
    GeneratePng { content: String, size: u32 },
    /// Generate a QR code with custom configuration.
    GenerateWithConfig { content: String, config: QrConfig },
    /// Decode a QR code from PNG image data.
    Decode { image_data: Vec<u8> },
    /// Get the library version string.
    GetVersion,
}

/// Responses from the QR engine.
/// Each variant maps 1:1 to a state update on the mobile side.
#[derive(Debug, Clone)]
pub enum QrResponse {
    /// Generated PNG bytes.
    PngGenerated { data: Vec<u8> },
    /// Decoded QR content.
    Decoded { content: String },
    /// Library version.
    Version { version: String },
}
```

### The Process Function

```rust
// BAD: Scattered public functions with inconsistent signatures
pub fn generate_png(content: &str, size: u32) -> Result<Vec<u8>, QrError> { ... }
pub fn decode(data: &[u8]) -> Result<String, QrError> { ... }
pub fn get_version() -> String { ... }  // not even a Result!

// GOOD: Central dispatch + individual functions for FFI convenience
/// Process a command and return a response.
/// This is the core dispatch — all operations route through here.
pub fn process(command: QrCommand) -> Result<QrResponse, QrError> {
    match command {
        QrCommand::GeneratePng { content, size } => {
            let data = encoder::generate_png(&content, size)?;
            Ok(QrResponse::PngGenerated { data })
        }
        QrCommand::GenerateWithConfig { content, config } => {
            let data = encoder::generate_with_config(&content, config)?;
            Ok(QrResponse::PngGenerated { data })
        }
        QrCommand::Decode { image_data } => {
            let content = decoder::decode(&image_data)?;
            Ok(QrResponse::Decoded { content })
        }
        QrCommand::GetVersion => {
            Ok(QrResponse::Version { version: get_library_version() })
        }
    }
}
```

### Individual Functions Still Exist (for FFI)

The `process()` dispatcher is the canonical entry point, but individual functions are still exposed
for FFI convenience (UniFFI exports work better with simple functions than enum dispatch):

```rust
// These are the FFI-facing functions — thin wrappers around process()
// They exist because UniFFI exports simple fn signatures, not enum dispatch

/// Generate a QR code PNG. Called from mobile via FFI.
pub fn generate_png(content: &str, size: u32) -> Result<Vec<u8>, QrError> {
    // Validate, encode, return — pure function, no side effects
    // ...
}

/// Decode a QR code from PNG data. Called from mobile via FFI.
pub fn decode(image_data: &[u8]) -> Result<String, QrError> {
    // Validate, decode, return — pure function, no side effects
    // ...
}
```

### Good Code vs Bad Code

```rust
// BAD: Hidden state, side effects, unpredictable behavior
static mut LAST_GENERATED: Option<Vec<u8>> = None;

pub fn generate_png(content: &str, size: u32) -> Result<Vec<u8>, QrError> {
    let data = do_generate(content, size)?;
    unsafe { LAST_GENERATED = Some(data.clone()); } // hidden side effect!
    Ok(data)
}

pub fn get_last_generated() -> Option<Vec<u8>> {
    unsafe { LAST_GENERATED.clone() } // hidden state!
}

// GOOD: Pure functions, no state, predictable
pub fn generate_png(content: &str, size: u32) -> Result<Vec<u8>, QrError> {
    // Input → validate → process → output. Nothing else.
    if content.is_empty() {
        return Err(QrError::InvalidInput { reason: "content must not be empty".into() });
    }
    let code = qrcode::QrCode::new(content)
        .map_err(|e| QrError::EncodingFailed { reason: e.to_string() })?;
    // ... encode to PNG ...
    Ok(buf)
}
```

### Rules

- **Every public function is a pure function**: same input → same output, always
- **No global mutable state**: no `static mut`, no `lazy_static` with mutation, no `Mutex<T>` for
  shared state
- **No side effects**: no file I/O, no network calls, no logging in the core crate (logging can go
  in FFI if needed)
- **Command types define the full input**: no implicit parameters, no environment reads
- **Response types define the full output**: no out-parameters, no global result caches
- **`process()` is the canonical dispatcher**: all operations route through it for testability
- **Individual functions exist for FFI**: UniFFI exports need simple `fn` signatures, not enum
  dispatch

### End-to-End Flow

```
┌─────────────────────────────────────────────────────┐
│ Mobile (Android/iOS)                                 │
│                                                     │
│  Intent ──→ ViewModel ──→ QrBridge (expect/actual)  │
│                                                     │
│      ↕ FFI boundary (UniFFI)                        │
│                                                     │
│  State  ←── ViewModel ←── QrBridge                  │
│    ↓                                                │
│  View (renders state)                               │
└─────────────────────────────────────────────────────┘
        │                         ↑
        ↓                         │
┌─────────────────────────────────────────────────────┐
│ Rust Core                                           │
│                                                     │
│  fn(input) ──→ validate ──→ process ──→ Result<T>   │
│                                                     │
│  Pure functions. No state. No side effects.         │
└─────────────────────────────────────────────────────┘
```

---

## 2. Error Handling — `Result<T, QrError>`, Never Panic

**All public functions that can fail MUST return `Result<T, QrError>`. No `unwrap()`, `expect()`,
or `panic!()` in library code.**

### The Error Type

```rust
// MUST: thiserror for Display/Error impls, named fields for UniFFI
#[derive(Debug, thiserror::Error)]
pub enum QrError {
    #[error("Invalid input: {reason}")]
    InvalidInput { reason: String },

    #[error("Encoding failed: {reason}")]
    EncodingFailed { reason: String },

    #[error("Decoding failed: {reason}")]
    DecodingFailed { reason: String },

    #[error("Image processing error: {reason}")]
    ImageError { reason: String },
}
```

### Good Code vs Bad Code

```rust
// BAD: Panics in library code — crashes the mobile app
pub fn generate_png(content: &str, size: u32) -> Vec<u8> {
    let code = qrcode::QrCode::new(content).unwrap();
    let image = code.render::<Luma<u8>>().build();
    let mut buf = Vec::new();
    image.write_to(&mut buf, ImageFormat::Png).expect("PNG encoding failed");
    buf
}

// GOOD: All errors propagated via Result with typed variants
pub fn generate_png(content: &str, size: u32) -> Result<Vec<u8>, QrError> {
    if content.is_empty() {
        return Err(QrError::InvalidInput {
            reason: "content must not be empty".into(),
        });
    }
    let code = qrcode::QrCode::new(content)
        .map_err(|e| QrError::EncodingFailed { reason: e.to_string() })?;
    let image = code.render::<Luma<u8>>().build();
    let mut buf = Vec::new();
    PngEncoder::new(&mut buf)
        .write_image(image.as_raw(), image.width(), image.height(), ExtendedColorType::L8)
        .map_err(|e| QrError::EncodingFailed { reason: e.to_string() })?;
    Ok(buf)
}
```

### Converting External Errors

```rust
// BAD: Losing error context
let code = qrcode::QrCode::new(content)
    .map_err(|_| QrError::EncodingFailed { reason: "failed".into() })?;

// GOOD: Preserving the original error message
let code = qrcode::QrCode::new(content)
    .map_err(|e| QrError::EncodingFailed { reason: e.to_string() })?;
```

### Validation Pattern

```rust
// BAD: Validation scattered through the function
pub fn generate_png(content: &str, size: u32) -> Result<Vec<u8>, QrError> {
    let code = qrcode::QrCode::new(content).map_err(/* ... */)?; // fails deep inside
    // size check happens after encoding started
    if size == 0 { return Err(/* ... */); }
}

// GOOD: Validate all inputs upfront, fail fast
pub fn generate_png(content: &str, size: u32) -> Result<Vec<u8>, QrError> {
    // Validate first — before any expensive work
    if content.is_empty() {
        return Err(QrError::InvalidInput {
            reason: "content must not be empty".into(),
        });
    }
    if size == 0 || size > 4096 {
        return Err(QrError::InvalidInput {
            reason: format!("size must be 1..=4096, got {size}"),
        });
    }
    // Now do the work
    let code = qrcode::QrCode::new(content)
        .map_err(|e| QrError::EncodingFailed { reason: e.to_string() })?;
    // ...
}
```

### Rules

- **NEVER** `unwrap()`, `expect()`, or `panic!()` in library code
- **ALWAYS** return `Result<T, QrError>` from public functions
- **ALWAYS** use `map_err` with `?` to convert external crate errors — preserve the message via
  `.to_string()`
- **ALWAYS** validate inputs at the top of the function, before expensive work
- **OK** to use `unwrap()` in tests — but prefer `?` with `Result`-returning test functions
- **OK** to use `unreachable!()` for genuinely impossible code paths (e.g., exhaustive match arms
  the compiler can't prove)

---

## 2. Error Enums — UniFFI-Safe From Day One

**Error types in `crates/core` must be compatible with UniFFI even though `uniffi::Error` derive is
only added in `crates/ffi`.**

```rust
// BAD: Tuple variants — will break UniFFI in Phase 3
#[derive(Debug, thiserror::Error)]
pub enum QrError {
    #[error("Invalid input: {0}")]
    InvalidInput(String),
    #[error("Encoding failed: {0}")]
    EncodingFailed(String),
}

// GOOD: Named fields — UniFFI-compatible from day one
#[derive(Debug, thiserror::Error)]
pub enum QrError {
    #[error("Invalid input: {reason}")]
    InvalidInput { reason: String },
    #[error("Encoding failed: {reason}")]
    EncodingFailed { reason: String },
}
```

### Rules

- **ALL** enum variants with data MUST use named fields (`{ reason: String }` not `(String)`)
- Fieldless variants are fine: `NotFound` (no data = no problem)
- Field types must be FFI-safe: `String`, `u32`, `i64`, `bool` — NOT `&str`, `Box<dyn Error>`,
  generics
- Use `thiserror::Error` derive in core; `uniffi::Error` derive is added in the FFI crate wrapper

---

## 3. Crate Architecture — Core vs FFI

### Core crate (`crates/core`) — ALL business logic

```
crates/core/
  Cargo.toml
  src/
    lib.rs          # pub mod encoder; pub mod decoder; pub mod error; pub mod types;
    error.rs        # QrError enum
    types.rs        # QrConfig, QrResult, ScanResult records
    encoder.rs      # generate_png(), generate_with_config()
    decoder.rs      # decode(), decode_multi()
  tests/
    round_trip.rs   # Integration tests
```

### FFI crate (`crates/ffi`) — THIN wrappers only

```rust
// BAD: Business logic in the FFI crate
#[uniffi::export]
fn generate_png(content: String, size: u32) -> Result<Vec<u8>, QrError> {
    if content.is_empty() {
        return Err(QrError::InvalidInput { reason: "empty content".into() });
    }
    let code = qrcode::QrCode::new(content.as_bytes())?;
    // ... 20 more lines of encoding logic
}

// GOOD: One-liner delegating to core
#[uniffi::export]
fn generate_png(content: String, size: u32) -> Result<Vec<u8>, QrError> {
    rusty_qr_core::encoder::generate_png(&content, size)
}
```

### Rules

- **ZERO** business logic in `crates/ffi/`
- Every FFI function is a **one-liner** delegating to a `crates/core` function
- FFI crate owns: `uniffi::setup_scaffolding!()`, `#[uniffi::export]`, `#[derive(uniffi::Record)]`
  re-derives
- Core crate owns: all logic, all validation, all error construction, all tests
- Core crate has **no UniFFI dependency** — it uses `thiserror` only. UniFFI derives are added in
  FFI.

---

## 4. Types — UniFFI-Safe Records

```rust
// BAD: Types that won't survive FFI
pub struct QrConfig {
    pub content: &str,               // reference — not FFI-safe
    pub options: HashMap<String, Box<dyn Any>>, // trait object — not FFI-safe
}

// GOOD: All owned types, all fields pub, no generics
/// Configuration for QR code generation.
#[derive(Debug, Clone)]
pub struct QrConfig {
    /// Output image size in pixels (width = height). Must be 1..=4096.
    pub size: u32,
    /// Error correction level: "L", "M", "Q", or "H".
    pub error_correction: String,
}
```

### Supported field types (for UniFFI compatibility)

- `String`, `bool`, `i8`–`i64`, `u8`–`u64`, `f32`, `f64`
- `Vec<T>`, `HashMap<String, T>`, `Option<T>` where T is also supported
- `Vec<u8>` for byte data (maps to Kotlin `List<UByte>`, Swift `Data`)
- Other structs that also follow these rules

### NOT supported

- `&str`, `&[u8]`, any references or lifetimes
- `Box<dyn Trait>`, `Rc<T>`, `Arc<T>` (except `Arc<Self>` for Object types)
- Generic types (`MyType<T>`)
- `PathBuf`, `OsString`, `Duration`, `SystemTime`, `Uuid`, `Url`

---

## 5. Dependencies — Feature-Gate for Binary Size

**Target: total Rust library < 3MB per architecture.**

```toml
# BAD: Pulls in everything, bloats mobile binary
[dependencies]
image = "0.25"
rxing = "0.6"

# GOOD: Only what you need
[dependencies]
image = { version = "0.25", default-features = false, features = ["png"] }
rxing = { version = "0.6", default-features = false, features = ["qrcode"] }
```

### Rules

- **ALWAYS** use `default-features = false` and enable only needed features
- Check binary size impact before adding a new dependency: `cargo bloat --release -p rusty-qr-ffi`
- Prefer crates with feature flags over monolithic crates
- If a dependency adds > 500KB, document the justification

---

## 6. Documentation — Doc Comments on All Public Items

```rust
// BAD: No docs on public items
pub struct QrConfig {
    pub size: u32,
    pub error_correction: String,
}

pub fn generate_with_config(content: &str, config: QrConfig) -> Result<Vec<u8>, QrError> {

// GOOD: Module, struct, field, and function docs
/// Configuration for QR code generation.
#[derive(Debug, Clone)]
pub struct QrConfig {
    /// Output image size in pixels (width = height). Must be 1..=4096.
    pub size: u32,
    /// Error correction level: "L", "M", "Q", or "H".
    pub error_correction: String,
}

/// Generates a QR code PNG with custom configuration.
///
/// Returns the PNG as raw bytes suitable for writing to a file or
/// passing across FFI to mobile platforms.
///
/// # Errors
///
/// Returns [`QrError::InvalidInput`] if content is empty or size is out of range.
/// Returns [`QrError::EncodingFailed`] if the QR encoding or PNG writing fails.
pub fn generate_with_config(content: &str, config: QrConfig) -> Result<Vec<u8>, QrError> {
```

### Rules

- Doc comments (`///`) on all `pub` items: modules, structs, enums, fields, functions
- `# Errors` section on all `Result`-returning functions listing each variant
- Module-level docs (`//!`) at the top of each `.rs` file

---

## 7. Testing — Behavior, Not Implementation

```rust
// BAD: Testing internal implementation details
#[test]
fn test_encoder_uses_qrcode_crate() {
    // Checks that a specific internal crate is called — brittle
}

// GOOD: Testing behavior and contracts
#[test]
fn encode_simple_text_produces_valid_png() {
    let result = generate_png("hello", 256).unwrap();
    assert_eq!(&result[..8], b"\x89PNG\r\n\x1a\n", "must be valid PNG");
    assert!(result.len() > 100, "PNG should have meaningful content");
}

#[test]
fn encode_empty_content_fails() {
    let err = generate_png("", 256).unwrap_err();
    assert!(matches!(err, QrError::InvalidInput { .. }));
}

#[test]
fn round_trip_preserves_content() {
    let content = "https://example.com?foo=bar&baz=qux";
    let png = generate_png(content, 256).unwrap();
    let decoded = decode(&png).unwrap();
    assert_eq!(decoded, content);
}
```

### Rules

- **Unit tests** inline in `#[cfg(test)] mod tests` — test individual functions
- **Integration tests** in `crates/core/tests/` — test public API as an external consumer
- Test **behavior and contracts**: valid input → expected output, invalid input → correct error
  variant, round-trip preservation
- Test error paths: empty input, zero size, oversized input, corrupt data
- Verify PNG magic bytes (`\x89PNG\r\n\x1a\n`) for encoding tests
- `assert!(matches!(err, QrError::Variant { .. }))` to check error variants without matching field
  values

---

## 8. Formatting & Linting

All three must pass before any commit:

```bash
cd rustySDK && cargo fmt --check
cd rustySDK && cargo clippy --workspace -- -D warnings
cd rustySDK && cargo test --workspace
```

- `cargo fmt` — standard Rust formatting, no custom rustfmt.toml overrides
- `cargo clippy` with `-D warnings` — zero warnings, all lints treated as errors
- No `#[allow(clippy::...)]` without a comment explaining why
