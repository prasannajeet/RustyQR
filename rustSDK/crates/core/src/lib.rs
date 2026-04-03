//! `rusty-qr-core` — pure-Rust QR code generation and decoding engine.
//!
//! This crate contains all business logic for encoding QR codes to PNG bytes
//! and decoding QR codes from image data or raw pixel buffers.
//! It has no UniFFI dependency; UniFFI derives are added by the `rusty-qr-ffi`
//! crate in Phase 3.

pub mod command;
pub mod decoder;
pub mod encoder;
pub mod error;
pub mod types;

pub use command::{process, QrCommand, QrResponse};
pub use error::QrError;
pub use types::{QrConfig, QrErrorCorrection, ScanResult};
