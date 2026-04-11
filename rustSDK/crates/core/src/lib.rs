//! `rusty-qr-core` — pure-Rust QR code generation and decoding engine.
//!
//! This crate contains all business logic for encoding QR codes to PNG bytes
//! and decoding QR codes from image data or raw pixel buffers.
//! It has **no UniFFI dependency** — UniFFI derives are added by the sibling
//! `rusty-qr-ffi` crate so that this core remains a plain Rust library usable
//! in any context (CLI tools, WASM, server-side, etc.).
//!
//! # Crate layout
//!
//! | Module      | Purpose |
//! |-------------|---------|
//! | [`encoder`] | QR code generation — text → PNG bytes |
//! | [`decoder`] | QR code scanning — image bytes or raw pixels → decoded text |
//! | [`command`] | Command/response dispatcher (mirrors mobile MVI pattern) |
//! | [`types`]   | Shared value types (`QrConfig`, `QrErrorCorrection`, `ScanResult`) |
//! | [`error`]   | `QrError` enum used by all fallible operations |
//!
//! # Quick start
//!
//! ```rust
//! use rusty_qr_core::{encoder, decoder};
//!
//! // Encode text into a QR code PNG
//! let png_bytes = encoder::generate_png("https://example.com", 256).unwrap();
//!
//! // Decode the PNG back to text
//! let scan = decoder::decode(&png_bytes).unwrap();
//! assert_eq!(scan.content, "https://example.com");
//! ```

pub mod command;
pub mod decoder;
pub mod encoder;
pub mod error;
pub mod types;

pub use command::{process, QrCommand, QrResponse};
pub use error::QrError;
pub use types::{QrConfig, QrErrorCorrection, ScanResult};
