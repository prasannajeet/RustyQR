---
name: new-rust-module
description: Scaffold a new module in the Rust core crate with proper structure, error handling, doc comments, and test file.
user-invocable: false
---

# New Rust Module

Scaffold a new module in `rustSDK/crates/core/` following project conventions.

## Input

The invoking agent provides: module name (e.g., `encoder`, `decoder`) and a brief description of
what it does.

## Scaffold

### 1. Create the module file

`rustSDK/crates/core/src/<module_name>.rs`:

```rust
//! <Module description — one line>.
//!
//! <Expanded description — what this module does, what crate dependencies it uses,
//! and how it fits into the QR pipeline.>

use crate::error::QrError;
use crate::types::{/* relevant types */};

/// <Function doc — what it does, constraints on inputs, what the return value contains.>
///
/// # Errors
///
/// Returns [`QrError::InvalidInput`] if <condition>.
/// Returns [`QrError::<Variant>`] if <condition>.
pub fn <primary_function>(/* params */) -> Result</* return type */, QrError> {
    // Validate inputs first
    // Core logic
    // Return Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn <function>_with_valid_input_succeeds() {
        // Arrange
        // Act
        // Assert
    }

    #[test]
    fn <function>_with_invalid_input_fails() {
        // Arrange
        // Act
        let err = <function>(/* bad input */).unwrap_err();
        // Assert
        assert!(matches!(err, QrError::InvalidInput { .. }));
    }
}
```

### 2. Register in lib.rs

Add to `rustSDK/crates/core/src/lib.rs`:

```rust
pub mod <module_name>;
```

### 3. Create integration test file (if applicable)

`rustSDK/crates/core/tests/<module_name>.rs`:

```rust
//! Integration tests for the <module_name> module.

use rusty_qr_core::<module_name>::*;

#[test]
fn <integration_test_name>() {
    // Test the public API as an external consumer would
}
```

### 4. Update error.rs if new variants needed

Add variants to `QrError` in `rustSDK/crates/core/src/error.rs`:

```rust
#[derive(Debug, thiserror::Error)]
pub enum QrError {
    // ... existing variants ...

    /// <When this error occurs>.
    #[error("<user-facing message>: {reason}")]
    <NewVariant> { reason: String },
}
```

**Critical**: All variants MUST use named fields (`{ reason: String }` not `(String)`).
**Critical**: Do NOT add `uniffi::Error` derive in the core crate — UniFFI derives are added only in
`crates/ffi/`. The core crate uses `thiserror` only.

### 5. Update types.rs if new types needed

Add types to `rustSDK/crates/core/src/types.rs`:

```rust
/// <Type description>.
#[derive(Debug, Clone)]
pub struct <TypeName> {
    /// <Field description>.
    pub <field>: <type>,
}
```

**Critical**: All fields must be owned types — no references, no lifetimes, no generics.
**Critical**: Do NOT add `uniffi::Record` derive in the core crate — UniFFI derives are added only
in `crates/ffi/`. But design the struct to be UniFFI-compatible (all owned types, all fields pub).

Supported field types (must be FFI-compatible even without the derive):

- `String`, `bool`, `i8`-`i64`, `u8`-`u64`, `f32`, `f64`
- `Vec<T>`, `HashMap<String, T>`, `Option<T>` (where T is also supported)
- Other structs that also follow these rules
- NOT: `&str`, `&[u8]`, `Box<dyn T>`, generics, type aliases wrapping references

## Conventions

- All public functions return `Result<T, QrError>` — no panics
- Validate inputs at the top of the function, return early on error
- Use `map_err` to convert external crate errors into `QrError` variants
- Doc comments on all public items (module, functions, types, fields)
- Inline unit tests in `#[cfg(test)] mod tests` — test behavior, not implementation
- Integration tests in `crates/core/tests/` — test public API as external consumer
- Feature-gate heavy dependencies:
  `image = { version = "0.25", default-features = false, features = ["png"] }`

## Verify

```bash
cd rustSDK && cargo fmt --check && cargo clippy --workspace -- -D warnings && cargo test --workspace
```
