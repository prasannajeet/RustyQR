//! Error types for the `rusty-qr-core` crate.
//!
//! All fallible operations in this crate return [`QrError`].
//! The variants use named fields (not tuple variants) to stay compatible
//! with UniFFI, which maps named fields to foreign-language record types
//! in the FFI crate.

/// Errors that can occur during QR code operations.
///
/// All variants use named fields to remain compatible with UniFFI, which will
/// be added in Phase 3 when the FFI crate is wired up.
#[derive(Debug, thiserror::Error)]
pub enum QrError {
    /// The caller supplied invalid input (e.g. empty content or out-of-range size).
    #[error("Invalid input: {reason}")]
    InvalidInput { reason: String },

    /// The QR encoding step failed (e.g. content exceeds QR data capacity).
    #[error("Encoding failed: {reason}")]
    EncodingFailed { reason: String },

    /// Decoding a QR image failed (e.g. no QR code found, corrupt image data).
    #[error("Decoding failed: {reason}")]
    DecodingFailed { reason: String },

    /// An image-processing operation failed (e.g. PNG serialisation error).
    #[error("Image processing error: {reason}")]
    ImageError { reason: String },
}
