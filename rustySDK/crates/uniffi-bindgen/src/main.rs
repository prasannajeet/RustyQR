//! UniFFI binding generator binary.
//!
//! This is a tiny CLI entry point that invokes UniFFI's built-in `uniffi_bindgen_main`.
//! Running this binary generates the Kotlin and Swift binding source files from
//! the UDL / proc-macro metadata defined in the `rusty-qr-ffi` crate.
//!
//! Typically invoked via a Gradle or Xcode build script:
//!
//! ```sh
//! cargo run -p uniffi-bindgen generate \
//!     --library target/release/librusty_qr_ffi.dylib \
//!     --language kotlin --language swift \
//!     --out-dir generated/
//! ```
fn main() {
    uniffi::uniffi_bindgen_main()
}
