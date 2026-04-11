//! Shared types for QR code generation and scanning.
//!
//! This module defines the configuration and result types used across
//! the encoder and decoder modules. All types are plain Rust structs/enums
//! with no UniFFI annotations — the FFI crate mirrors them with its own
//! `Ffi`-prefixed wrappers and `From` conversions.

/// Error correction level for QR code generation.
///
/// Higher error correction levels produce larger QR codes but allow more
/// of the code to be damaged or obscured while still being decodable.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum QrErrorCorrection {
    /// ~7% recovery. Smallest QR code.
    Low,
    /// ~15% recovery. Default.
    Medium,
    /// ~25% recovery.
    Quartile,
    /// ~30% recovery. Largest QR code.
    High,
}

impl QrErrorCorrection {
    /// Converts this error correction level to the `qrcode` crate's `EcLevel`.
    pub(crate) fn to_ec_level(self) -> qrcode::EcLevel {
        match self {
            Self::Low => qrcode::EcLevel::L,
            Self::Medium => qrcode::EcLevel::M,
            Self::Quartile => qrcode::EcLevel::Q,
            Self::High => qrcode::EcLevel::H,
        }
    }
}

/// Configuration for QR code generation with custom options.
///
/// # Example
///
/// ```rust
/// use rusty_qr_core::types::{QrConfig, QrErrorCorrection};
/// use rusty_qr_core::encoder;
///
/// let config = QrConfig {
///     size: 512,
///     error_correction: QrErrorCorrection::High,
/// };
/// let png = encoder::generate_with_config("hello", config).unwrap();
/// ```
// AUDIT: both fields are Copy — could derive Copy here
#[derive(Debug, Clone, PartialEq, Eq)]
#[must_use]
pub struct QrConfig {
    /// Output image size in pixels (width = height). Must be 1..=4096.
    pub size: u32,
    /// Error correction level.
    pub error_correction: QrErrorCorrection,
}

/// Result of scanning/decoding a QR code from an image.
///
/// Currently contains only the decoded text. Future versions may add
/// metadata such as the QR version, error-correction level detected,
/// or bounding-box coordinates.
#[derive(Debug, Clone, PartialEq, Eq)]
#[must_use]
pub struct ScanResult {
    /// The decoded text content extracted from the QR code.
    pub content: String,
}
