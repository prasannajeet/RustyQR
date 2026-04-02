---
name: new-bridge
description: Scaffold an expect/actual bridge pair for exposing a Rust function to both Android and iOS via KMM.
user-invocable: false
---

# New Bridge (expect/actual)

Scaffold an expect/actual bridge to expose Rust functionality from platform-specific bindings to
shared KMM code.

## Input

The invoking agent provides: function name, parameter types, return type, and which Rust FFI
function it wraps.

## When to Use

- Adding a new Rust function that mobile code needs to call
- The function exists in `crates/ffi/` as a `#[uniffi::export]` and bindings have been generated
- Both Android and iOS need access through shared `commonMain` code

## Scaffold

### 1. Expect declaration (commonMain)

`composeApp/src/commonMain/kotlin/com/p2/apps/rustyqr/QrBridge.kt`:

If `QrBridge.kt` already exists, ADD the new function to the existing expect object. If not, create:

```kotlin
package com.p2.apps.rustyqr

/**
 * Bridge to Rust QR code library.
 * Platform actuals call UniFFI-generated bindings.
 *
 * All functions are synchronous and should be called from
 * [kotlinx.coroutines.Dispatchers.IO], not the main thread.
 */
expect object QrBridge {
    /**
     * <Function description>.
     *
     * @param <param> <description>
     * @return <description>
     * @throws <ExceptionType> <when>
     */
    fun <functionName>(<params>): <ReturnType>
}
```

**Critical rules for expect declarations:**

- Return `ByteArray` for byte data (not `List<UByte>`) — conversion happens in actuals
- Use Kotlin stdlib types only (no UniFFI types in common code)
- Document threading requirements in KDoc

### 2. Android actual

`composeApp/src/androidMain/kotlin/com/p2/apps/rustyqr/QrBridge.android.kt`:

**File name**: `QrBridge.android.kt` — platform suffix, NOT class name. This is a project
convention (`MatchingDeclarationName` is disabled in detekt for this reason).

```kotlin
package com.p2.apps.rustyqr

import com.p2.apps.rustyqr.rust.RustyQrFfi

/**
 * Android actual — delegates to UniFFI-generated Kotlin bindings.
 */
actual object QrBridge {
    actual fun <functionName>(<params>): <ReturnType> {
        // Call UniFFI-generated function
        // Handle type conversions here
    }
}
```

**Type conversion patterns (UniFFI Kotlin mappings):**

| Rust type     | UniFFI Kotlin type | Bridge return type | Conversion                      |
|---------------|--------------------|--------------------|---------------------------------|
| `Vec<u8>`     | `List<UByte>`      | `ByteArray`        | `.toUByteArray().asByteArray()` |
| `String`      | `String`           | `String`           | none                            |
| `u32`         | `UInt`             | `Int`              | `.toInt()`                      |
| `i32`         | `Int`              | `Int`              | none                            |
| `bool`        | `Boolean`          | `Boolean`          | none                            |
| `Option<T>`   | `T?`               | `T?`               | convert inner if needed         |
| `Result<T,E>` | throws exception   | throws exception   | try/catch if wrapping           |

**`Vec<u8>` → `ByteArray` conversion (most common case):**

```kotlin
actual fun generateQrPng(content: String, size: Int): ByteArray {
    // UniFFI returns List<UByte>, bridge converts to ByteArray
    val ubyteList: List<UByte> = RustyQrFfi.generatePng(content, size.toUInt())
    return ubyteList.toUByteArray().asByteArray()
}
```

**`UInt` parameter conversion:**

```kotlin
actual fun someFunction(size: Int): ByteArray {
    // Bridge takes Int (Kotlin-idiomatic), converts to UInt for UniFFI
    return RustyQrFfi.someFunction(size.toUInt()).toUByteArray().asByteArray()
}
```

### 3. iOS actual

`composeApp/src/iosMain/kotlin/com/p2/apps/rustyqr/QrBridge.ios.kt`:

**File name**: `QrBridge.ios.kt` — platform suffix.

For **Approach B (Swift-side, v1)**: iOS actual is a placeholder that throws. Real Rust calls happen
in Swift.

```kotlin
package com.p2.apps.rustyqr

/**
 * iOS actual — placeholder for v1 (Approach B).
 * Rust is called directly from Swift via UniFFI-generated bindings.
 * This will be implemented when migrating to Approach A (cinterop).
 */
actual object QrBridge {
    actual fun <functionName>(<params>): <ReturnType> {
        // Approach B: Rust is called from Swift side
        // This placeholder exists to satisfy the expect/actual contract
        throw UnsupportedOperationException(
            "QrBridge.<functionName> is called from Swift in Approach B"
        )
    }
}
```

For **Approach A (cinterop, future)**: iOS actual calls through Kotlin/Native cinterop.

```kotlin
actual object QrBridge {
    actual fun <functionName>(<params>): <ReturnType> {
        // Call through cinterop bindings
        // Type conversions here (similar to Android but different FFI types)
    }
}
```

### 4. Wire into ViewModel

Update the relevant ViewModel to call the bridge:

```kotlin
// In the ViewModel's handleSubmit() or similar:
private fun handleSubmit() {
    viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        try {
            val result = withContext(Dispatchers.IO) {
                QrBridge.<functionName>(state.value.inputText, 256)
            }
            _state.update { it.copy(/* update with result */, isLoading = false) }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message, isLoading = false) }
        }
    }
}
```

**Critical**: Always `withContext(Dispatchers.IO)` — Rust calls are synchronous, 10-50ms.

## Checklist Before Creating

- [ ] The Rust function exists in `crates/ffi/src/lib.rs` with `#[uniffi::export]`
- [ ] UniFFI bindings have been generated (Kotlin file exists in
  `androidMain/kotlin/com/p2/apps/rustyqr/rust/`)
- [ ] The function's Rust types are FFI-compatible (run `check-ffi-compat` if unsure)
- [ ] You know the exact UniFFI Kotlin/Swift type mappings for all params and return

## Verify

```bash
./gradlew :composeApp:lintAll && ./gradlew :composeApp:assembleDebug
```
