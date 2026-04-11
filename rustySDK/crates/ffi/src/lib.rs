//! FFI bindings for `rusty-qr-core` via [UniFFI](https://mozilla.github.io/uniffi-rs/).
//!
//! This crate is a **thin wrapper** — every exported function is a one-liner
//! delegating to `rusty_qr_core`. No business logic lives here.
//!
//! # Why mirror types?
//!
//! The core crate is intentionally free of UniFFI annotations so it can be
//! used as a plain Rust library. This FFI crate re-declares each public type
//! with `uniffi::` derives (e.g. `FfiQrConfig`, `FfiQrError`) and provides
//! `From` conversions in both directions. The UniFFI code generator reads
//! these derives to produce Kotlin and Swift bindings.
//!
//! # Exported functions
//!
//! | Function                | Maps to |
//! |-------------------------|---------|
//! | `generate_png`          | `rusty_qr_core::encoder::generate_png` |
//! | `generate_with_config`  | `rusty_qr_core::encoder::generate_with_config` |
//! | `decode_qr`             | `rusty_qr_core::decoder::decode` |
//! | `decode_qr_from_raw`    | `rusty_qr_core::decoder::decode_from_raw` |
//! | `get_library_version`   | `rusty_qr_core::encoder::get_library_version` |

uniffi::setup_scaffolding!();

// ---------------------------------------------------------------------------
// Wrapper types
// ---------------------------------------------------------------------------

/// QR error-correction level exposed to foreign callers.
///
/// Mirrors [`rusty_qr_core::QrErrorCorrection`] with UniFFI derives so the
/// core crate stays free of UniFFI dependencies.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FfiQrErrorCorrection {
    /// ~7 % recovery capacity. Produces the smallest QR code.
    Low,
    /// ~15 % recovery capacity. Default level.
    Medium,
    /// ~25 % recovery capacity.
    Quartile,
    /// ~30 % recovery capacity. Produces the largest QR code.
    High,
}

/// Configuration for QR code generation exposed to foreign callers.
///
/// Mirrors [`rusty_qr_core::QrConfig`].
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiQrConfig {
    /// Output image size in pixels (width = height). Must be 1..=4096.
    pub size: u32,
    /// Error-correction level.
    pub error_correction: FfiQrErrorCorrection,
}

/// Result of scanning / decoding a QR code, exposed to foreign callers.
///
/// Mirrors [`rusty_qr_core::ScanResult`].
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FfiScanResult {
    /// The decoded text content.
    pub content: String,
}

/// Errors that can occur during QR code operations, exposed to foreign callers.
///
/// Mirrors [`rusty_qr_core::QrError`]. Each variant carries a human-readable
/// `reason` string.
#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum FfiQrError {
    /// The caller supplied invalid input.
    #[error("Invalid input: {reason}")]
    InvalidInput {
        /// Human-readable description of what was wrong.
        reason: String,
    },
    /// QR encoding failed.
    #[error("Encoding failed: {reason}")]
    EncodingFailed {
        /// Human-readable description of the failure.
        reason: String,
    },
    /// QR decoding failed.
    #[error("Decoding failed: {reason}")]
    DecodingFailed {
        /// Human-readable description of the failure.
        reason: String,
    },
    /// An image-processing operation failed.
    #[error("Image processing error: {reason}")]
    ImageError {
        /// Human-readable description of the failure.
        reason: String,
    },
}

// ---------------------------------------------------------------------------
// From / Into conversions between FFI ↔ core types
//
// These conversions allow the exported functions to accept FFI wrapper types
// from foreign callers, convert them to core types for processing, and
// convert the results back to FFI types for the return trip across the
// language boundary.
// ---------------------------------------------------------------------------

impl From<FfiQrErrorCorrection> for rusty_qr_core::QrErrorCorrection {
    fn from(value: FfiQrErrorCorrection) -> Self {
        match value {
            FfiQrErrorCorrection::Low => Self::Low,
            FfiQrErrorCorrection::Medium => Self::Medium,
            FfiQrErrorCorrection::Quartile => Self::Quartile,
            FfiQrErrorCorrection::High => Self::High,
        }
    }
}

impl From<FfiQrConfig> for rusty_qr_core::QrConfig {
    fn from(value: FfiQrConfig) -> Self {
        Self {
            size: value.size,
            error_correction: value.error_correction.into(),
        }
    }
}

impl From<rusty_qr_core::ScanResult> for FfiScanResult {
    fn from(value: rusty_qr_core::ScanResult) -> Self {
        Self { content: value.content }
    }
}

impl From<rusty_qr_core::QrError> for FfiQrError {
    fn from(value: rusty_qr_core::QrError) -> Self {
        match value {
            rusty_qr_core::QrError::InvalidInput { reason } => Self::InvalidInput { reason },
            rusty_qr_core::QrError::EncodingFailed { reason } => Self::EncodingFailed { reason },
            rusty_qr_core::QrError::DecodingFailed { reason } => Self::DecodingFailed { reason },
            rusty_qr_core::QrError::ImageError { reason } => Self::ImageError { reason },
        }
    }
}

// ---------------------------------------------------------------------------
// Exported functions — thin one-liner delegations to core
// ---------------------------------------------------------------------------

/// Generates a QR code PNG from the given text content at the specified size.
///
/// This is the simplest entry point — uses the default (Medium) error-correction
/// level. The output is a `Vec<u8>` containing valid PNG bytes.
#[uniffi::export]
fn generate_png(content: String, size: u32) -> Result<Vec<u8>, FfiQrError> {
    rusty_qr_core::encoder::generate_png(&content, size).map_err(FfiQrError::from)
}

/// Generates a QR code PNG with custom configuration (size + error-correction).
#[uniffi::export]
fn generate_with_config(content: String, config: FfiQrConfig) -> Result<Vec<u8>, FfiQrError> {
    rusty_qr_core::encoder::generate_with_config(&content, config.into()).map_err(FfiQrError::from)
}

/// Decodes a QR code from PNG or JPEG image bytes.
#[uniffi::export]
fn decode_qr(image_data: Vec<u8>) -> Result<FfiScanResult, FfiQrError> {
    rusty_qr_core::decoder::decode(&image_data)
        .map(FfiScanResult::from)
        .map_err(FfiQrError::from)
}

/// Decodes a QR code from a raw grayscale pixel buffer.
///
/// This is the camera-frame path — the caller provides the Y (luma) plane
/// directly so no PNG round-trip is needed.
#[uniffi::export]
fn decode_qr_from_raw(pixels: Vec<u8>, width: u32, height: u32) -> Result<FfiScanResult, FfiQrError> {
    rusty_qr_core::decoder::decode_from_raw(&pixels, width, height)
        .map(FfiScanResult::from)
        .map_err(FfiQrError::from)
}

/// Returns the library version string (from the core crate's `Cargo.toml`).
#[uniffi::export]
fn get_library_version() -> String {
    rusty_qr_core::encoder::get_library_version()
}

#[cfg(test)]
mod tests {
    use super::*;

    // -----------------------------------------------------------------------
    // 1. Type conversion tests
    // -----------------------------------------------------------------------

    #[test]
    fn ffi_error_correction_converts_all_variants() {
        let pairs = [
            (FfiQrErrorCorrection::Low, rusty_qr_core::QrErrorCorrection::Low),
            (FfiQrErrorCorrection::Medium, rusty_qr_core::QrErrorCorrection::Medium),
            (
                FfiQrErrorCorrection::Quartile,
                rusty_qr_core::QrErrorCorrection::Quartile,
            ),
            (FfiQrErrorCorrection::High, rusty_qr_core::QrErrorCorrection::High),
        ];
        for (ffi, expected) in pairs {
            let converted: rusty_qr_core::QrErrorCorrection = ffi.into();
            assert_eq!(converted, expected, "FfiQrErrorCorrection::{ffi:?} must map correctly");
        }
    }

    #[test]
    fn ffi_config_converts_correctly() {
        let ffi_config = FfiQrConfig {
            size: 256,
            error_correction: FfiQrErrorCorrection::High,
        };
        let core_config: rusty_qr_core::QrConfig = ffi_config.into();
        assert_eq!(core_config.size, 256);
        assert_eq!(core_config.error_correction, rusty_qr_core::QrErrorCorrection::High);
    }

    #[test]
    fn core_scan_result_converts_to_ffi() {
        let core_result = rusty_qr_core::ScanResult { content: "test".into() };
        let ffi_result: FfiScanResult = core_result.into();
        assert_eq!(ffi_result.content, "test");
    }

    #[test]
    fn core_error_converts_to_ffi_all_variants() {
        let cases: Vec<(rusty_qr_core::QrError, &str)> = vec![
            (
                rusty_qr_core::QrError::InvalidInput {
                    reason: "bad input".into(),
                },
                "bad input",
            ),
            (
                rusty_qr_core::QrError::EncodingFailed {
                    reason: "encode fail".into(),
                },
                "encode fail",
            ),
            (
                rusty_qr_core::QrError::DecodingFailed {
                    reason: "decode fail".into(),
                },
                "decode fail",
            ),
            (
                rusty_qr_core::QrError::ImageError {
                    reason: "img error".into(),
                },
                "img error",
            ),
        ];

        for (core_err, expected_reason) in cases {
            let ffi_err: FfiQrError = core_err.into();
            match ffi_err {
                FfiQrError::InvalidInput { ref reason } => {
                    assert_eq!(reason, expected_reason);
                }
                FfiQrError::EncodingFailed { ref reason } => {
                    assert_eq!(reason, expected_reason);
                }
                FfiQrError::DecodingFailed { ref reason } => {
                    assert_eq!(reason, expected_reason);
                }
                FfiQrError::ImageError { ref reason } => {
                    assert_eq!(reason, expected_reason);
                }
            }
        }

        // Also verify variant matching specifically
        let inv: FfiQrError = rusty_qr_core::QrError::InvalidInput { reason: "x".into() }.into();
        assert!(matches!(inv, FfiQrError::InvalidInput { .. }));

        let enc: FfiQrError = rusty_qr_core::QrError::EncodingFailed { reason: "x".into() }.into();
        assert!(matches!(enc, FfiQrError::EncodingFailed { .. }));

        let dec: FfiQrError = rusty_qr_core::QrError::DecodingFailed { reason: "x".into() }.into();
        assert!(matches!(dec, FfiQrError::DecodingFailed { .. }));

        let img: FfiQrError = rusty_qr_core::QrError::ImageError { reason: "x".into() }.into();
        assert!(matches!(img, FfiQrError::ImageError { .. }));
    }

    // -----------------------------------------------------------------------
    // 2. Function delegation tests
    // -----------------------------------------------------------------------

    #[test]
    fn generate_png_delegates_to_core() -> Result<(), FfiQrError> {
        let ffi_result = generate_png("hello".into(), 256)?;
        let core_result = rusty_qr_core::encoder::generate_png("hello", 256).map_err(FfiQrError::from)?;
        assert_eq!(ffi_result, core_result, "FFI and core must produce identical bytes");
        Ok(())
    }

    #[test]
    fn generate_with_config_delegates_to_core() -> Result<(), FfiQrError> {
        let ffi_config = FfiQrConfig {
            size: 256,
            error_correction: FfiQrErrorCorrection::High,
        };
        let core_config = rusty_qr_core::QrConfig {
            size: 256,
            error_correction: rusty_qr_core::QrErrorCorrection::High,
        };

        let ffi_result = generate_with_config("hello".into(), ffi_config)?;
        let core_result =
            rusty_qr_core::encoder::generate_with_config("hello", core_config).map_err(FfiQrError::from)?;
        assert_eq!(ffi_result, core_result, "FFI and core must produce identical bytes");
        Ok(())
    }

    #[test]
    fn decode_qr_delegates_to_core() -> Result<(), FfiQrError> {
        let png = rusty_qr_core::encoder::generate_png("delegation test", 256).map_err(FfiQrError::from)?;
        let ffi_result = decode_qr(png)?;
        assert_eq!(ffi_result.content, "delegation test");
        Ok(())
    }

    #[test]
    fn decode_qr_from_raw_delegates_to_core() -> Result<(), FfiQrError> {
        let png = rusty_qr_core::encoder::generate_png("raw delegation", 256).map_err(FfiQrError::from)?;

        // Convert PNG to raw grayscale pixels via the image crate (dev-dependency).
        let img = image::load_from_memory(&png).expect("PNG must be loadable");
        let luma = img.to_luma8();
        let (w, h) = (luma.width(), luma.height());

        let ffi_result = decode_qr_from_raw(luma.into_raw(), w, h)?;
        assert_eq!(ffi_result.content, "raw delegation");
        Ok(())
    }

    #[test]
    fn get_library_version_delegates_to_core() {
        let ffi_version = get_library_version();
        let core_version = rusty_qr_core::encoder::get_library_version();
        assert_eq!(ffi_version, core_version);
    }

    // -----------------------------------------------------------------------
    // 3. Error propagation tests
    // -----------------------------------------------------------------------

    #[test]
    fn generate_png_empty_content_returns_invalid_input() {
        let err = generate_png(String::new(), 256).unwrap_err();
        assert!(
            matches!(err, FfiQrError::InvalidInput { .. }),
            "expected InvalidInput, got {err:?}"
        );
    }

    #[test]
    fn generate_png_zero_size_returns_invalid_input() {
        let err = generate_png("test".into(), 0).unwrap_err();
        assert!(
            matches!(err, FfiQrError::InvalidInput { .. }),
            "expected InvalidInput, got {err:?}"
        );
    }

    #[test]
    fn decode_qr_empty_data_returns_invalid_input() {
        let err = decode_qr(vec![]).unwrap_err();
        assert!(
            matches!(err, FfiQrError::InvalidInput { .. }),
            "expected InvalidInput, got {err:?}"
        );
    }

    #[test]
    fn decode_qr_invalid_image_returns_error() {
        let err = decode_qr(vec![0xFF, 0x00]).unwrap_err();
        assert!(
            matches!(err, FfiQrError::ImageError { .. }),
            "expected ImageError, got {err:?}"
        );
    }

    #[test]
    fn decode_qr_from_raw_empty_returns_invalid_input() {
        let err = decode_qr_from_raw(vec![], 0, 0).unwrap_err();
        assert!(
            matches!(err, FfiQrError::InvalidInput { .. }),
            "expected InvalidInput, got {err:?}"
        );
    }
}
