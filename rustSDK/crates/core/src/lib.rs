//! `rusty-qr-core` — pure-Rust QR code generation engine.
//!
//! This crate contains all business logic for encoding QR codes to PNG bytes.
//! It has no UniFFI dependency; UniFFI derives are added by the `rusty-qr-ffi`
//! crate in Phase 3.

pub mod encoder;
pub mod error;

pub use error::QrError;
