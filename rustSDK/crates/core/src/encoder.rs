//! QR code encoding functions.
//!
//! This module provides [`generate_png`] for turning text content into a PNG
//! image containing a QR code, [`generate_with_config`] for configurable
//! generation, and [`get_library_version`] for querying the crate version at
//! runtime.

use image::codecs::png::PngEncoder;
use image::{imageops::FilterType, ExtendedColorType, ImageEncoder};

use crate::types::QrConfig;
use crate::QrError;

/// Maximum output image size in pixels (width = height).
const MAX_SIZE: u32 = 4096;

/// Validates the common inputs shared by all generation functions.
///
/// # Errors
///
/// * [`QrError::InvalidInput`] – `content` is empty, or `size` is 0 or greater
///   than [`MAX_SIZE`].
fn validate_generate_inputs(content: &str, size: u32) -> Result<(), QrError> {
    if content.is_empty() {
        return Err(QrError::InvalidInput {
            reason: "content must not be empty".into(),
        });
    }
    if size == 0 || size > MAX_SIZE {
        return Err(QrError::InvalidInput {
            reason: format!("size must be 1..={MAX_SIZE}, got {size}"),
        });
    }
    Ok(())
}

/// Generates a QR code PNG from the given text content.
///
/// The output image is square; both width and height equal `size`. The QR
/// code is rendered at the library's natural pixel density and then scaled to
/// the requested size with a Nearest-neighbour filter so that cells remain
/// sharp at any resolution.
///
/// # Arguments
///
/// * `content` – The text to encode. Must not be empty.
/// * `size`    – Output image side length in pixels. Must be in `1..=4096`.
///
/// # Errors
///
/// * [`QrError::InvalidInput`] – `content` is empty, or `size` is 0 or greater
///   than 4096.
/// * [`QrError::EncodingFailed`] – The `qrcode` library could not encode the
///   content (e.g. the data exceeds QR capacity).
/// * [`QrError::ImageError`] – The PNG serialisation step failed.
pub fn generate_png(content: &str, size: u32) -> Result<Vec<u8>, QrError> {
    validate_generate_inputs(content, size)?;

    let code = qrcode::QrCode::new(content.as_bytes()).map_err(|e| QrError::EncodingFailed {
        reason: e.to_string(),
    })?;

    render_qr_to_png(&code, size)
}

/// Generates a QR code PNG from the given text content with custom configuration.
///
/// This function allows specifying the output size and error correction level.
/// The output image is square; both width and height equal `config.size`.
///
/// # Arguments
///
/// * `content` – The text to encode. Must not be empty.
/// * `config`  – Configuration specifying the output size and error correction
///   level. `config.size` must be in `1..=4096`.
///
/// # Errors
///
/// * [`QrError::InvalidInput`] – `content` is empty, or `config.size` is 0 or
///   greater than 4096.
/// * [`QrError::EncodingFailed`] – The `qrcode` library could not encode the
///   content (e.g. the data exceeds QR capacity).
/// * [`QrError::ImageError`] – The PNG serialisation step failed.
pub fn generate_with_config(content: &str, config: QrConfig) -> Result<Vec<u8>, QrError> {
    validate_generate_inputs(content, config.size)?;

    // --- QR encoding with specified error correction level ---
    let code = qrcode::QrCode::with_error_correction_level(
        content.as_bytes(),
        config.error_correction.to_ec_level(),
    )
    .map_err(|e| QrError::EncodingFailed {
        reason: e.to_string(),
    })?;

    render_qr_to_png(&code, config.size)
}

/// Returns the version of this library as declared in `Cargo.toml`.
///
/// This function is infallible and always returns a non-empty string.
pub fn get_library_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

/// Renders a `QrCode` to a PNG byte buffer at the given size.
///
/// Shared by both [`generate_png`] and [`generate_with_config`] to keep
/// rendering logic in one place (DRY).
fn render_qr_to_png(code: &qrcode::QrCode, size: u32) -> Result<Vec<u8>, QrError> {
    // Render to a grayscale image at the library's natural cell size.
    let raw_image = code.render::<image::Luma<u8>>().build();

    // Resize to the caller-requested dimensions.
    let resized = image::imageops::resize(&raw_image, size, size, FilterType::Nearest);

    // --- PNG serialisation ---
    let mut buf: Vec<u8> = Vec::new();
    PngEncoder::new(&mut buf)
        .write_image(
            resized.as_raw(),
            resized.width(),
            resized.height(),
            ExtendedColorType::L8,
        )
        .map_err(|e| QrError::ImageError {
            reason: e.to_string(),
        })?;

    Ok(buf)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::{QrConfig, QrErrorCorrection};
    use crate::QrError;

    /// PNG file signature — the first 8 bytes of every valid PNG file.
    const PNG_MAGIC: &[u8] = b"\x89PNG\r\n\x1a\n";

    #[test]
    fn encode_simple_text_produces_valid_png() {
        let result = generate_png("hello", 256).unwrap();
        assert_eq!(
            &result[..8],
            PNG_MAGIC,
            "output must start with PNG magic bytes"
        );
        assert!(result.len() > 100, "PNG should have meaningful content");
    }

    #[test]
    fn encode_url_produces_valid_png() {
        let result = generate_png("https://example.com?foo=bar", 256).unwrap();
        assert_eq!(
            &result[..8],
            PNG_MAGIC,
            "output must start with PNG magic bytes"
        );
    }

    #[test]
    fn encode_unicode_content_succeeds() {
        let result = generate_png("Hello 世界 🌍", 256).unwrap();
        assert_eq!(
            &result[..8],
            PNG_MAGIC,
            "output must start with PNG magic bytes"
        );
    }

    #[test]
    fn encode_output_dimensions_match_requested_size() {
        let result = generate_png("hello", 256).unwrap();
        // The IHDR chunk starts at byte 8; width is at bytes 16-19 and height at bytes 20-23
        // (both big-endian u32).
        let width = u32::from_be_bytes(result[16..20].try_into().unwrap());
        let height = u32::from_be_bytes(result[20..24].try_into().unwrap());
        assert_eq!(width, 256, "PNG width must match requested size");
        assert_eq!(height, 256, "PNG height must match requested size");
    }

    #[test]
    fn encode_empty_content_fails() {
        let err = generate_png("", 256).unwrap_err();
        assert!(matches!(err, QrError::InvalidInput { .. }));
    }

    #[test]
    fn encode_size_zero_fails() {
        let err = generate_png("hello", 0).unwrap_err();
        assert!(matches!(err, QrError::InvalidInput { .. }));
    }

    #[test]
    fn encode_size_exceeds_max_fails() {
        let err = generate_png("hello", 5000).unwrap_err();
        assert!(matches!(err, QrError::InvalidInput { .. }));
    }

    #[test]
    fn encode_size_one_succeeds() {
        let result = generate_png("hello", 1).unwrap();
        assert_eq!(
            &result[..8],
            PNG_MAGIC,
            "output must start with PNG magic bytes"
        );
    }

    #[test]
    fn encode_size_max_succeeds() {
        let result = generate_png("hello", 4096).unwrap();
        assert_eq!(
            &result[..8],
            PNG_MAGIC,
            "output must start with PNG magic bytes"
        );
    }

    #[test]
    fn get_library_version_matches_cargo() {
        assert_eq!(get_library_version(), "0.1.0");
    }

    #[test]
    fn encode_with_config_default() {
        let config = QrConfig {
            size: 256,
            error_correction: QrErrorCorrection::Medium,
        };
        let result = generate_with_config("hello", config).unwrap();
        assert_eq!(
            &result[..8],
            PNG_MAGIC,
            "output must start with PNG magic bytes"
        );
        assert!(result.len() > 100, "PNG should have meaningful content");
    }

    #[test]
    fn encode_with_config_high_ec() {
        let config_high = QrConfig {
            size: 256,
            error_correction: QrErrorCorrection::High,
        };
        let result_high = generate_with_config("hello", config_high).unwrap();
        assert_eq!(
            &result_high[..8],
            PNG_MAGIC,
            "output must start with PNG magic bytes"
        );
        assert!(
            result_high.len() > 100,
            "High EC PNG should have meaningful content"
        );
    }

    #[test]
    fn encode_with_config_empty_content_fails() {
        let config = QrConfig {
            size: 256,
            error_correction: QrErrorCorrection::Medium,
        };
        let err = generate_with_config("", config).unwrap_err();
        assert!(matches!(err, QrError::InvalidInput { .. }));
    }

    #[test]
    fn encode_with_config_invalid_size_fails() {
        let config = QrConfig {
            size: 0,
            error_correction: QrErrorCorrection::Medium,
        };
        let err = generate_with_config("hello", config).unwrap_err();
        assert!(matches!(err, QrError::InvalidInput { .. }));
    }

    #[test]
    fn encode_all_ecl_levels() {
        let levels = [
            QrErrorCorrection::Low,
            QrErrorCorrection::Medium,
            QrErrorCorrection::Quartile,
            QrErrorCorrection::High,
        ];
        for level in levels {
            let config = QrConfig {
                size: 256,
                error_correction: level,
            };
            let result = generate_with_config("hello", config).unwrap();
            assert_eq!(
                &result[..8],
                PNG_MAGIC,
                "EC level {level:?} must produce valid PNG"
            );
        }
    }

    #[test]
    fn encode_content_exceeds_qr_capacity() {
        // QR codes have a maximum data capacity; 3000+ bytes of data should exceed it.
        let huge_content: String = "A".repeat(3500);
        let err = generate_png(&huge_content, 256).unwrap_err();
        assert!(
            matches!(err, QrError::EncodingFailed { .. }),
            "oversized content should return EncodingFailed, got {err:?}"
        );
    }
}
