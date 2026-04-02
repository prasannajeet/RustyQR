---
name: check-ffi-compat
description: Validate that Rust types are UniFFI-compatible before they cross the FFI boundary. Catches Phase 3 blockers early.
user-invocable: false
---

# Check FFI Compatibility

Validate that types in `rustSDK/crates/core/` are compatible with UniFFI proc-macro bindings before
the FFI crate is built.

## When to Invoke

- After creating or modifying types in `crates/core/src/types.rs`
- After creating or modifying error enums in `crates/core/src/error.rs`
- After changing public function signatures in any core module
- Before starting Phase 3 (FFI crate) work

## Checks

### 1. Error Enum Validation

Read `rustSDK/crates/core/src/error.rs` and verify:

| Rule                     | Valid                                | Invalid                            |
|--------------------------|--------------------------------------|------------------------------------|
| Named fields only        | `Foo { reason: String }`             | `Foo(String)`                      |
| No tuple variants        | `Bar { code: u32, msg: String }`     | `Bar(u32, String)`                 |
| Fieldless variants OK    | `NotFound`                           | —                                  |
| Field types are FFI-safe | `String`, `u32`, `i64`, `bool`       | `&str`, `Box<dyn Error>`, generics |
| Derives present          | `#[derive(Debug, thiserror::Error)]` | Missing derives                    |

**Note**: `uniffi::Error` derive will be added in the FFI crate, not core. But the enum shape must
be compatible NOW.

### 2. Record (Struct) Validation

Read `rustSDK/crates/core/src/types.rs` and verify:

| Rule                 | Valid                                        | Invalid                              |
|----------------------|----------------------------------------------|--------------------------------------|
| All fields owned     | `pub name: String`                           | `pub name: &'a str`                  |
| No generics          | `pub struct QrConfig`                        | `pub struct Config<T>`               |
| No lifetime params   | `pub struct Result`                          | `pub struct Result<'a>`              |
| Fields are FFI types | `String`, `u32`, `Vec<u8>`, `Option<String>` | `Box<dyn Trait>`, `Rc<T>`, `PathBuf` |
| All fields pub       | `pub field: Type`                            | `field: Type` (private)              |
| No skip/ignore       | All fields cross FFI                         | `#[serde(skip)]` won't help          |

**Supported field types (exhaustive for this project):**

- Primitives: `bool`, `i8`, `i16`, `i32`, `i64`, `u8`, `u16`, `u32`, `u64`, `f32`, `f64`
- String: `String` (not `&str`, not `Cow<str>`)
- Bytes: `Vec<u8>` (maps to Kotlin `List<UByte>`, Swift `Data`)
- Collections: `Vec<T>`, `HashMap<String, T>` where T is also FFI-safe
- Optional: `Option<T>` where T is FFI-safe
- Other Records: types that also derive `uniffi::Record`
- **NOT**: `PathBuf`, `OsString`, `Duration`, `SystemTime`, `Uuid`, `Url` (unless custom type
  converters added)

### 3. Function Signature Validation

Read all `pub fn` in `crates/core/src/*.rs` that will be exposed via FFI:

| Rule                | Valid                         | Invalid                            |
|---------------------|-------------------------------|------------------------------------|
| Owned params        | `fn foo(s: String)`           | `fn foo(s: &str)` for exported fns |
| `&self` for methods | `fn bar(&self)`               | `fn bar(self)` consumes            |
| Return owned types  | `-> Result<Vec<u8>, QrError>` | `-> Result<&[u8], QrError>`        |
| No generics in sig  | `fn baz(x: u32)`              | `fn baz<T>(x: T)`                  |
| No impl Trait       | `fn qux() -> String`          | `fn qux() -> impl Display`         |

**Important**: `&str` IS valid for function parameters in proc-macro exports (UniFFI converts
automatically). But for records/structs, use `String`.

### 4. Cross-Reference Check

For each public function in core that will be wrapped by FFI:

- Verify return type is FFI-safe
- Verify all parameter types are FFI-safe
- Verify error type (if Result) has named fields only
- Verify any Records returned are fully FFI-safe (recursive check)

## Output Format

```
## FFI Compatibility Check

### error.rs
- [PASS] All error variants use named fields
- [FAIL] Variant `DecodeFailed(String)` uses tuple field — change to `DecodeFailed { reason: String }`

### types.rs
- [PASS] QrConfig: all fields are FFI-safe
- [FAIL] ScanResult: field `metadata: HashMap<String, Box<dyn Any>>` is not FFI-safe

### Public API
- [PASS] generate_png(content: &str, size: u32) -> Result<Vec<u8>, QrError>
- [FAIL] decode_with_options<T>(data: &[u8], opts: T) — generic parameter not supported

**Result: <N> issues found — <fix instructions for each>**
```
