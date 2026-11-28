//! FFI bindings for `rusty-qr-core` via [UniFFI](https://mozilla.github.io/uniffi-rs/).
//!
//! This crate is a **thin wrapper** — every exported function is a one-liner
//! delegating to `rusty_qr_core`. No business logic lives here.
//!
//! # Why mirror types?
//!
//! The core crate is intentionally free of `UniFFI` annotations so it can be
//! used as a plain Rust library. This FFI crate re-declares each public type
//! with `uniffi::` derives (e.g. `FfiQrConfig`, `FfiQrError`) and provides
//! `From` conversions in both directions. The `UniFFI` code generator reads
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
//! | `content_fits`          | `rusty_qr_core::encoder::content_fits` |
//! | `FfiUrlRiskChecker`     | `rusty_qr_risk_candle::UrlRiskChecker` (the URL risk check) |
//!
//! The risk check has its own error type, [`FfiRiskError`], mirroring
//! [`rusty_qr_core::risk::RiskError`]. It is a separate bounded context, so QR callers that handle
//! every [`FfiQrError`] variant are not affected by it.

use std::sync::Arc;

uniffi::setup_scaffolding!();

// ---------------------------------------------------------------------------
// Wrapper types
// ---------------------------------------------------------------------------

/// QR error-correction level exposed to foreign callers.
///
/// Mirrors [`rusty_qr_core::QrErrorCorrection`] with `UniFFI` derives so the
/// core crate stays free of `UniFFI` dependencies.
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
    /// Output image size in pixels (width = height). Must be 1..=4096, and at least the code's width in modules
    /// plus 8 for the quiet zone (29 for the smallest code, 185 for the largest).
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
    /// The content exceeds the capacity of the largest QR code.
    ///
    /// Declared last: flat errors cross FFI as their declaration index.
    #[error("Content too long: {reason}")]
    ContentTooLong {
        /// Human-readable description of the failure.
        reason: String,
    },
}

/// The outcome of a URL risk check, exposed to foreign callers.
///
/// Mirrors [`rusty_qr_core::risk::RiskVerdict`]. There is deliberately no `Safe` variant: below the
/// threshold the check has nothing to say.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Enum)]
pub enum FfiRiskVerdict {
    /// The score reached the model's threshold. The app shows a warning.
    Suspicious {
        /// The model's phishing score for the URL, in `0.0..=1.0`.
        score: f32,
    },
    /// The score stayed below the threshold. The app shows nothing about risk.
    NoWarning,
}

/// Errors from the URL risk check, exposed to foreign callers.
///
/// Mirrors [`rusty_qr_core::risk::RiskError`].
#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum FfiRiskError {
    /// The input could not be used: an empty URL, or model files that are not valid.
    #[error("Invalid input: {reason}")]
    InvalidInput {
        /// What was wrong with the input.
        reason: String,
    },
    /// A score or threshold was outside `0.0..=1.0`, or not a number.
    #[error("Invalid score: {reason}")]
    InvalidScore {
        /// The value and why it was rejected.
        reason: String,
    },
    /// The model failed to load or to score a URL.
    ///
    /// Declared last: flat errors cross FFI as their declaration index.
    #[error("Model failed: {reason}")]
    ModelFailed {
        /// The model runtime's error message.
        reason: String,
    },
}

/// The URL risk check, with its model loaded, exposed to foreign callers.
///
/// Load it once, off the main thread (loading parses the whole model), and reuse it for every scan.
/// It holds no mutable state, so one instance can serve concurrent calls.
#[derive(uniffi::Object)]
pub struct FfiUrlRiskChecker {
    inner: rusty_qr_risk_candle::UrlRiskChecker,
}

#[uniffi::export]
impl FfiUrlRiskChecker {
    /// Loads the model from the bytes of its `model.safetensors`, `tokenizer.json` and `config.json`,
    /// and flags scores at or above `threshold`, which comes from the model's evaluation.
    ///
    /// # Errors
    ///
    /// Returns [`FfiRiskError::InvalidScore`] if `threshold` is not in `0.0..=1.0`,
    /// [`FfiRiskError::InvalidInput`] if the config or tokenizer cannot be parsed or the config has no
    /// `phishing` label, and [`FfiRiskError::ModelFailed`] if the weights do not match the config.
    #[uniffi::constructor]
    pub fn new(
        weights: Vec<u8>,
        tokenizer_json: &[u8],
        config_json: &[u8],
        threshold: f32,
    ) -> Result<Arc<Self>, FfiRiskError> {
        rusty_qr_risk_candle::UrlRiskChecker::new(weights, tokenizer_json, config_json, threshold)
            .map(|inner| Arc::new(Self { inner }))
            .map_err(FfiRiskError::from)
    }

    /// Assesses one scanned URL. Surrounding whitespace is ignored, and URLs longer than 2,048 bytes
    /// are cut to that length before scoring. Blocking: call it off the main thread.
    ///
    /// # Errors
    ///
    /// Returns [`FfiRiskError::InvalidInput`] if `url` is empty or only whitespace, and
    /// [`FfiRiskError::ModelFailed`] or [`FfiRiskError::InvalidScore`] if the model cannot score it.
    pub fn assess(&self, url: &str) -> Result<FfiRiskVerdict, FfiRiskError> {
        self.inner
            .assess(url)
            .map(FfiRiskVerdict::from)
            .map_err(FfiRiskError::from)
    }
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
            rusty_qr_core::QrError::ContentTooLong { reason } => Self::ContentTooLong { reason },
            rusty_qr_core::QrError::DecodingFailed { reason } => Self::DecodingFailed { reason },
            rusty_qr_core::QrError::ImageError { reason } => Self::ImageError { reason },
        }
    }
}

impl From<rusty_qr_core::risk::RiskVerdict> for FfiRiskVerdict {
    fn from(value: rusty_qr_core::risk::RiskVerdict) -> Self {
        match value {
            rusty_qr_core::risk::RiskVerdict::Suspicious { score } => Self::Suspicious { score: score.value() },
            rusty_qr_core::risk::RiskVerdict::NoWarning => Self::NoWarning,
        }
    }
}

impl From<rusty_qr_core::risk::RiskError> for FfiRiskError {
    fn from(value: rusty_qr_core::risk::RiskError) -> Self {
        match value {
            rusty_qr_core::risk::RiskError::InvalidInput { reason } => Self::InvalidInput { reason },
            rusty_qr_core::risk::RiskError::InvalidScore { reason } => Self::InvalidScore { reason },
            rusty_qr_core::risk::RiskError::ModelFailed { reason } => Self::ModelFailed { reason },
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
///
/// `size` must be in `1..=4096` and at least the code's width in modules plus 8 for the quiet zone (29 for
/// the smallest code, 185 for the largest); a smaller size fails with `InvalidInput`.
#[uniffi::export]
fn generate_png(content: &str, size: u32) -> Result<Vec<u8>, FfiQrError> {
    rusty_qr_core::encoder::generate_png(content, size).map_err(FfiQrError::from)
}

/// Generates a QR code PNG with custom configuration (size + error-correction).
#[uniffi::export]
fn generate_with_config(content: &str, config: FfiQrConfig) -> Result<Vec<u8>, FfiQrError> {
    rusty_qr_core::encoder::generate_with_config(content, config.into()).map_err(FfiQrError::from)
}

/// Decodes a QR code from PNG or JPEG image bytes.
#[uniffi::export]
fn decode_qr(image_data: &[u8]) -> Result<FfiScanResult, FfiQrError> {
    rusty_qr_core::decoder::decode(image_data)
        .map(FfiScanResult::from)
        .map_err(FfiQrError::from)
}

/// Decodes a QR code from a raw grayscale pixel buffer.
///
/// This is the camera-frame path — the caller provides the Y (luma) plane
/// directly so no PNG round-trip is needed.
#[uniffi::export]
fn decode_qr_from_raw(pixels: &[u8], width: u32, height: u32) -> Result<FfiScanResult, FfiQrError> {
    rusty_qr_core::decoder::decode_from_raw(pixels, width, height)
        .map(FfiScanResult::from)
        .map_err(FfiQrError::from)
}

/// Returns the library version string (from the core crate's `Cargo.toml`).
#[uniffi::export]
fn get_library_version() -> String {
    rusty_qr_core::encoder::get_library_version()
}

/// Whether `generate_png` can encode `content`; `false` means it would fail with `ContentTooLong`.
///
/// `true` guarantees generation at any size in `185..=4096` (a version 40 code at one pixel per module).
#[uniffi::export]
fn content_fits(content: &str) -> Result<bool, FfiQrError> {
    rusty_qr_core::encoder::content_fits(content).map_err(FfiQrError::from)
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
    fn core_error_converts_to_the_same_ffi_variant_and_reason() {
        use rusty_qr_core::QrError;
        // Both enums share identical `#[error]` formats, so equal text means same variant and same reason.
        let reason = || String::from("the reason");
        let cases = [
            QrError::InvalidInput { reason: reason() },
            QrError::EncodingFailed { reason: reason() },
            QrError::ContentTooLong { reason: reason() },
            QrError::DecodingFailed { reason: reason() },
            QrError::ImageError { reason: reason() },
        ];
        for core_err in cases {
            let expected = core_err.to_string();
            assert!(expected.ends_with("the reason"), "{expected}");
            assert_eq!(FfiQrError::from(core_err).to_string(), expected);
        }
    }

    // -----------------------------------------------------------------------
    // 2. Function delegation tests
    // -----------------------------------------------------------------------

    #[test]
    fn generate_png_delegates_to_core() -> Result<(), FfiQrError> {
        let ffi_result = generate_png("hello", 256)?;
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

        let ffi_result = generate_with_config("hello", ffi_config)?;
        let core_result =
            rusty_qr_core::encoder::generate_with_config("hello", core_config).map_err(FfiQrError::from)?;
        assert_eq!(ffi_result, core_result, "FFI and core must produce identical bytes");
        Ok(())
    }

    #[test]
    fn decode_qr_delegates_to_core() -> Result<(), FfiQrError> {
        let png = rusty_qr_core::encoder::generate_png("delegation test", 256).map_err(FfiQrError::from)?;
        let ffi_result = decode_qr(&png)?;
        assert_eq!(ffi_result.content, "delegation test");
        Ok(())
    }

    #[test]
    fn decode_qr_from_raw_delegates_to_core() -> Result<(), FfiQrError> {
        let png = rusty_qr_core::encoder::generate_png("raw delegation", 256).map_err(FfiQrError::from)?;

        // Convert PNG to raw grayscale pixels via the image crate (dev-dependency).
        let img = image::load_from_memory(&png).map_err(|e| FfiQrError::ImageError { reason: e.to_string() })?;
        let luma = img.to_luma8();
        let (w, h) = (luma.width(), luma.height());

        let ffi_result = decode_qr_from_raw(&luma.into_raw(), w, h)?;
        assert_eq!(ffi_result.content, "raw delegation");
        Ok(())
    }

    #[test]
    fn get_library_version_delegates_to_core() {
        let ffi_version = get_library_version();
        let core_version = rusty_qr_core::encoder::get_library_version();
        assert_eq!(ffi_version, core_version);
    }

    #[test]
    fn content_fits_delegates_to_core() -> Result<(), FfiQrError> {
        for content in ["hello", &"a".repeat(2332), &"7".repeat(5597)] {
            let ffi_result = content_fits(content)?;
            let core_result = rusty_qr_core::encoder::content_fits(content).map_err(FfiQrError::from)?;
            assert_eq!(
                ffi_result,
                core_result,
                "FFI and core must agree for {} bytes",
                content.len()
            );
        }
        Ok(())
    }

    #[test]
    fn content_fits_empty_returns_invalid_input() {
        let result = content_fits("");
        assert!(
            matches!(result, Err(FfiQrError::InvalidInput { .. })),
            "expected InvalidInput, got {result:?}"
        );
    }

    // -----------------------------------------------------------------------
    // 3. Error propagation tests
    // -----------------------------------------------------------------------

    #[test]
    fn generate_png_empty_content_returns_invalid_input() {
        let result = generate_png("", 256);
        assert!(
            matches!(result, Err(FfiQrError::InvalidInput { .. })),
            "expected InvalidInput, got {result:?}"
        );
    }

    #[test]
    fn generate_png_zero_size_returns_invalid_input() {
        let result = generate_png("test", 0);
        assert!(
            matches!(result, Err(FfiQrError::InvalidInput { .. })),
            "expected InvalidInput, got {result:?}"
        );
    }

    #[test]
    fn decode_qr_empty_data_returns_invalid_input() {
        let result = decode_qr(&[]);
        assert!(
            matches!(result, Err(FfiQrError::InvalidInput { .. })),
            "expected InvalidInput, got {result:?}"
        );
    }

    #[test]
    fn decode_qr_invalid_image_returns_error() {
        let result = decode_qr(&[0xFF, 0x00]);
        assert!(
            matches!(result, Err(FfiQrError::ImageError { .. })),
            "expected ImageError, got {result:?}"
        );
    }

    #[test]
    fn decode_qr_from_raw_empty_returns_invalid_input() {
        let result = decode_qr_from_raw(&[], 0, 0);
        assert!(
            matches!(result, Err(FfiQrError::InvalidInput { .. })),
            "expected InvalidInput, got {result:?}"
        );
    }

    // -----------------------------------------------------------------------
    // 3. URL risk check
    // -----------------------------------------------------------------------

    const RISK_WEIGHTS: &[u8] = include_bytes!("../../risk-candle/tests/fixtures/tiny-bert/model.safetensors");
    const RISK_TOKENIZER: &[u8] = include_bytes!("../../risk-candle/tests/fixtures/tiny-bert/tokenizer.json");
    const RISK_CONFIG: &[u8] = include_bytes!("../../risk-candle/tests/fixtures/tiny-bert/config.json");
    /// Between the fixture's scores for `https://example.com` (about 0.121) and 126 "a"s (about 0.066).
    const RISK_THRESHOLD: f32 = 0.1;

    #[test]
    fn risk_checker_delegates_to_the_adapter() -> Result<(), Box<dyn std::error::Error>> {
        let ffi = FfiUrlRiskChecker::new(RISK_WEIGHTS.to_vec(), RISK_TOKENIZER, RISK_CONFIG, RISK_THRESHOLD)?;
        let core = rusty_qr_risk_candle::UrlRiskChecker::new(
            RISK_WEIGHTS.to_vec(),
            RISK_TOKENIZER,
            RISK_CONFIG,
            RISK_THRESHOLD,
        )?;
        for url in ["https://example.com", &"a".repeat(126)] {
            assert_eq!(ffi.assess(url)?, FfiRiskVerdict::from(core.assess(url)?), "{url}");
        }
        assert!(matches!(
            ffi.assess("https://example.com")?,
            FfiRiskVerdict::Suspicious { .. }
        ));
        assert_eq!(ffi.assess(&"a".repeat(126))?, FfiRiskVerdict::NoWarning);
        Ok(())
    }

    #[test]
    fn risk_checker_errors_cross_as_risk_errors() {
        let bad_threshold = FfiUrlRiskChecker::new(RISK_WEIGHTS.to_vec(), RISK_TOKENIZER, RISK_CONFIG, 1.5);
        assert!(
            matches!(bad_threshold, Err(FfiRiskError::InvalidScore { .. })),
            "{:?}",
            bad_threshold.err()
        );
        let bad_weights = FfiUrlRiskChecker::new(b"not weights".to_vec(), RISK_TOKENIZER, RISK_CONFIG, 0.5);
        assert!(
            matches!(bad_weights, Err(FfiRiskError::ModelFailed { .. })),
            "{:?}",
            bad_weights.err()
        );
    }

    #[test]
    fn risk_checker_rejects_an_empty_url() -> Result<(), Box<dyn std::error::Error>> {
        let checker = FfiUrlRiskChecker::new(RISK_WEIGHTS.to_vec(), RISK_TOKENIZER, RISK_CONFIG, RISK_THRESHOLD)?;
        let result = checker.assess("  ");
        assert!(
            matches!(result, Err(FfiRiskError::InvalidInput { .. })),
            "got {result:?}"
        );
        Ok(())
    }

    #[test]
    fn core_risk_error_converts_to_the_same_ffi_variant_and_reason() {
        use rusty_qr_core::risk::RiskError;
        // Both enums share identical `#[error]` formats, so equal text means same variant and same reason.
        let reason = || String::from("the reason");
        let cases = [
            RiskError::InvalidInput { reason: reason() },
            RiskError::InvalidScore { reason: reason() },
            RiskError::ModelFailed { reason: reason() },
        ];
        for core_err in cases {
            let expected = core_err.to_string();
            assert!(expected.ends_with("the reason"), "{expected}");
            assert_eq!(FfiRiskError::from(core_err).to_string(), expected);
        }
    }

    #[test]
    fn core_risk_verdict_converts_with_its_score() -> Result<(), Box<dyn std::error::Error>> {
        use rusty_qr_core::risk::{RiskScore, RiskVerdict};
        let suspicious = RiskVerdict::Suspicious {
            score: RiskScore::new(0.75)?,
        };
        assert_eq!(
            FfiRiskVerdict::from(suspicious),
            FfiRiskVerdict::Suspicious { score: 0.75 }
        );
        assert_eq!(FfiRiskVerdict::from(RiskVerdict::NoWarning), FfiRiskVerdict::NoWarning);
        Ok(())
    }
}
