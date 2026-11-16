//! QR code encoding functions.
//!
//! This module provides [`generate_png`] for turning text content into a PNG
//! image containing a QR code, [`generate_with_config`] for configurable
//! generation, and [`get_library_version`] for querying the crate version at
//! runtime.
//!
//! # Pipeline
//!
//! 1. **Validate** inputs (content non-empty, size in range).
//! 2. **Encode** content into a QR matrix via the `qrcode` crate.
//! 3. **Render** the matrix to a grayscale `image::GrayImage`.
//! 4. **Resize** to the caller-requested dimensions (nearest-neighbour to keep
//!    cells sharp).
//! 5. **Serialise** to PNG bytes via `image::codecs::png::PngEncoder`.

use image::codecs::png::PngEncoder;
use image::{imageops::FilterType, ExtendedColorType, ImageEncoder};

use crate::types::QrConfig;
use crate::QrError;

/// Maximum output image size in pixels (width = height).
const MAX_SIZE: u32 = 4096;

/// Maximum content length, in UTF-8 bytes, that always encodes with [`generate_png`].
///
/// Content of at most this many UTF-8 bytes always encodes with [`generate_png`]:
/// it is the byte-mode capacity of a version 40 QR code at error-correction
/// level M, and encoding falls back to a single byte-mode segment whenever the
/// mixed-mode segmentation does not fit. Longer content encodes only if it
/// packs into numeric or alphanumeric mode. [`generate_with_config`] with another
/// level has a different capacity.
pub const MAX_BYTE_CONTENT_LEN_LEVEL_M: usize = 2331;

/// [`MAX_BYTE_CONTENT_LEN_LEVEL_M`] as a `u32`, the type exported across FFI.
pub const MAX_BYTE_CONTENT_LEN_LEVEL_M_U32: u32 = 2331;

/// Converts a `qrcode` encoding error, reporting oversized data as its own variant.
fn map_encode_error(error: qrcode::types::QrError) -> QrError {
    match error {
        qrcode::types::QrError::DataTooLong => QrError::ContentTooLong {
            reason: error.to_string(),
        },
        qrcode::types::QrError::InvalidVersion
        | qrcode::types::QrError::UnsupportedCharacterSet
        | qrcode::types::QrError::InvalidEciDesignator
        | qrcode::types::QrError::InvalidCharacter => QrError::EncodingFailed {
            reason: error.to_string(),
        },
    }
}

/// Encodes `data` at `level`, falling back to a single byte-mode segment when
/// the `qrcode` optimizer's mixed-mode segmentation does not fit.
///
/// The optimizer splits text with short digit or uppercase runs into several
/// segments, and each extra segment header can cost more bits than it saves.
fn encode(data: &[u8], level: qrcode::EcLevel) -> Result<qrcode::QrCode, QrError> {
    match qrcode::QrCode::with_error_correction_level(data, level) {
        Err(qrcode::types::QrError::DataTooLong) => encode_single_byte_segment(data, level),
        other => other.map_err(map_encode_error),
    }
}

/// Encodes `data` as one byte-mode segment in the smallest version that holds it.
fn encode_single_byte_segment(data: &[u8], level: qrcode::EcLevel) -> Result<qrcode::QrCode, QrError> {
    for version in 1..=40 {
        let mut bits = qrcode::bits::Bits::new(qrcode::Version::Normal(version));
        if bits.push_byte_data(data).is_ok() && bits.push_terminator(level).is_ok() {
            return qrcode::QrCode::with_bits(bits, level).map_err(map_encode_error);
        }
    }
    Err(QrError::ContentTooLong {
        reason: format!(
            "{} UTF-8 bytes do not fit a version 40 QR code at level {level:?}",
            data.len()
        ),
    })
}

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
/// * [`QrError::ContentTooLong`] – The content exceeds the capacity of the
///   largest QR code at the chosen error-correction level.
/// * [`QrError::EncodingFailed`] – The `qrcode` library could not encode the
///   content for any other reason.
/// * [`QrError::ImageError`] – The PNG serialisation step failed.
pub fn generate_png(content: &str, size: u32) -> Result<Vec<u8>, QrError> {
    validate_generate_inputs(content, size)?;

    let code = encode(content.as_bytes(), qrcode::EcLevel::M)?;

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
/// * [`QrError::ContentTooLong`] – The content exceeds the capacity of the
///   largest QR code at the chosen error-correction level.
/// * [`QrError::EncodingFailed`] – The `qrcode` library could not encode the
///   content for any other reason.
/// * [`QrError::ImageError`] – The PNG serialisation step failed.
pub fn generate_with_config(content: &str, config: QrConfig) -> Result<Vec<u8>, QrError> {
    validate_generate_inputs(content, config.size)?;

    // --- QR encoding with specified error correction level ---
    let code = encode(content.as_bytes(), config.error_correction.to_ec_level())?;

    render_qr_to_png(&code, config.size)
}

/// Returns the version of this library as declared in `Cargo.toml`.
///
/// This function is infallible and always returns a non-empty string.
#[must_use]
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
        .map_err(|e| QrError::ImageError { reason: e.to_string() })?;

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
    fn encode_simple_text_produces_valid_png() -> Result<(), Box<dyn std::error::Error>> {
        let result = generate_png("hello", 256)?;
        assert_eq!(
            result.get(..8),
            Some(PNG_MAGIC),
            "output must start with PNG magic bytes"
        );
        assert!(result.len() > 100, "PNG should have meaningful content");
        Ok(())
    }

    #[test]
    fn encode_url_produces_valid_png() -> Result<(), Box<dyn std::error::Error>> {
        let result = generate_png("https://example.com?foo=bar", 256)?;
        assert_eq!(
            result.get(..8),
            Some(PNG_MAGIC),
            "output must start with PNG magic bytes"
        );
        Ok(())
    }

    #[test]
    fn encode_unicode_content_succeeds() -> Result<(), Box<dyn std::error::Error>> {
        let result = generate_png("Hello 世界 🌍", 256)?;
        assert_eq!(
            result.get(..8),
            Some(PNG_MAGIC),
            "output must start with PNG magic bytes"
        );
        Ok(())
    }

    #[test]
    fn encode_output_dimensions_match_requested_size() -> Result<(), Box<dyn std::error::Error>> {
        let result = generate_png("hello", 256)?;
        // The IHDR chunk starts at byte 8; width is at bytes 16-19 and height at bytes 20-23
        // (both big-endian u32).
        let width = u32::from_be_bytes(result.get(16..20).ok_or("PNG too short")?.try_into()?);
        let height = u32::from_be_bytes(result.get(20..24).ok_or("PNG too short")?.try_into()?);
        assert_eq!(width, 256, "PNG width must match requested size");
        assert_eq!(height, 256, "PNG height must match requested size");
        Ok(())
    }

    #[test]
    fn encode_empty_content_fails() {
        let result = generate_png("", 256);
        assert!(matches!(result, Err(QrError::InvalidInput { .. })));
    }

    #[test]
    fn encode_size_zero_fails() {
        let result = generate_png("hello", 0);
        assert!(matches!(result, Err(QrError::InvalidInput { .. })));
    }

    #[test]
    fn encode_size_exceeds_max_fails() {
        let result = generate_png("hello", 5000);
        assert!(matches!(result, Err(QrError::InvalidInput { .. })));
    }

    #[test]
    fn encode_size_one_succeeds() -> Result<(), Box<dyn std::error::Error>> {
        let result = generate_png("hello", 1)?;
        assert_eq!(
            result.get(..8),
            Some(PNG_MAGIC),
            "output must start with PNG magic bytes"
        );
        Ok(())
    }

    #[test]
    fn encode_size_max_succeeds() -> Result<(), Box<dyn std::error::Error>> {
        let result = generate_png("hello", 4096)?;
        assert_eq!(
            result.get(..8),
            Some(PNG_MAGIC),
            "output must start with PNG magic bytes"
        );
        Ok(())
    }

    #[test]
    fn get_library_version_matches_cargo() {
        assert_eq!(get_library_version(), "0.1.0");
    }

    #[test]
    fn encode_with_config_default() -> Result<(), Box<dyn std::error::Error>> {
        let config = QrConfig {
            size: 256,
            error_correction: QrErrorCorrection::Medium,
        };
        let result = generate_with_config("hello", config)?;
        assert_eq!(
            result.get(..8),
            Some(PNG_MAGIC),
            "output must start with PNG magic bytes"
        );
        assert!(result.len() > 100, "PNG should have meaningful content");
        Ok(())
    }

    #[test]
    fn encode_with_config_high_ec() -> Result<(), Box<dyn std::error::Error>> {
        let config_high = QrConfig {
            size: 256,
            error_correction: QrErrorCorrection::High,
        };
        let result_high = generate_with_config("hello", config_high)?;
        assert_eq!(
            result_high.get(..8),
            Some(PNG_MAGIC),
            "output must start with PNG magic bytes"
        );
        assert!(result_high.len() > 100, "High EC PNG should have meaningful content");
        Ok(())
    }

    #[test]
    fn encode_with_config_empty_content_fails() {
        let config = QrConfig {
            size: 256,
            error_correction: QrErrorCorrection::Medium,
        };
        let result = generate_with_config("", config);
        assert!(matches!(result, Err(QrError::InvalidInput { .. })));
    }

    #[test]
    fn encode_with_config_invalid_size_fails() {
        let config = QrConfig {
            size: 0,
            error_correction: QrErrorCorrection::Medium,
        };
        let result = generate_with_config("hello", config);
        assert!(matches!(result, Err(QrError::InvalidInput { .. })));
    }

    #[test]
    fn encode_all_ecl_levels() -> Result<(), Box<dyn std::error::Error>> {
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
            let result = generate_with_config("hello", config)?;
            assert_eq!(
                result.get(..8),
                Some(PNG_MAGIC),
                "EC level {level:?} must produce valid PNG"
            );
        }
        Ok(())
    }

    #[test]
    fn encode_content_exceeds_qr_capacity() {
        let huge_content: String = "A".repeat(3500);
        let result = generate_png(&huge_content, 256);
        assert!(
            matches!(result, Err(QrError::ContentTooLong { .. })),
            "oversized content should return ContentTooLong, got {result:?}"
        );
    }

    #[test]
    fn byte_mode_boundary_is_max_byte_content_len() -> Result<(), Box<dyn std::error::Error>> {
        let at_limit = generate_png(&"a".repeat(MAX_BYTE_CONTENT_LEN_LEVEL_M), 256)?;
        assert_eq!(at_limit.get(..8), Some(PNG_MAGIC), "content at the limit must encode");

        let over = generate_png(&"a".repeat(MAX_BYTE_CONTENT_LEN_LEVEL_M + 1), 256);
        assert!(
            matches!(over, Err(QrError::ContentTooLong { .. })),
            "one byte over the limit must return ContentTooLong, got {over:?}"
        );
        Ok(())
    }

    #[test]
    fn multibyte_boundary_is_measured_in_utf8_bytes() -> Result<(), Box<dyn std::error::Error>> {
        // "é" is 2 bytes in UTF-8, so an odd limit needs one extra ASCII byte.
        let at_limit = format!("a{}", "é".repeat(MAX_BYTE_CONTENT_LEN_LEVEL_M / 2));
        assert_eq!(at_limit.len(), MAX_BYTE_CONTENT_LEN_LEVEL_M);
        let encoded = generate_png(&at_limit, 256)?;
        assert_eq!(encoded.get(..8), Some(PNG_MAGIC), "content at the limit must encode");

        let over = format!("{at_limit}a");
        assert_eq!(over.len(), MAX_BYTE_CONTENT_LEN_LEVEL_M + 1);
        let result = generate_png(&over, 256);
        assert!(
            matches!(result, Err(QrError::ContentTooLong { .. })),
            "one UTF-8 byte over the limit must return ContentTooLong, got {result:?}"
        );

        // Fewer characters than the limit, but more bytes: still too long.
        let few_chars = "é".repeat(MAX_BYTE_CONTENT_LEN_LEVEL_M / 2 + 1);
        assert!(few_chars.chars().count() <= MAX_BYTE_CONTENT_LEN_LEVEL_M);
        assert!(
            few_chars.len() > MAX_BYTE_CONTENT_LEN_LEVEL_M,
            "must be over the limit in bytes"
        );
        let result = generate_png(&few_chars, 256);
        assert!(
            matches!(result, Err(QrError::ContentTooLong { .. })),
            "limit is in bytes, not characters, got {result:?}"
        );
        Ok(())
    }

    #[test]
    fn digits_only_content_can_exceed_byte_limit() -> Result<(), Box<dyn std::error::Error>> {
        let digits = "7".repeat(MAX_BYTE_CONTENT_LEN_LEVEL_M + 1);
        let result = generate_png(&digits, 256)?;
        assert_eq!(result.get(..8), Some(PNG_MAGIC), "numeric mode packs more characters");
        Ok(())
    }

    #[test]
    fn with_config_oversized_content_returns_content_too_long() {
        let config = QrConfig {
            size: 256,
            error_correction: QrErrorCorrection::High,
        };
        let result = generate_with_config(&"a".repeat(MAX_BYTE_CONTENT_LEN_LEVEL_M), config);
        assert!(
            matches!(result, Err(QrError::ContentTooLong { .. })),
            "level H holds less than level M, got {result:?}"
        );
    }

    #[test]
    fn non_capacity_encoding_errors_stay_encoding_failed() {
        let errors = [
            qrcode::types::QrError::InvalidVersion,
            qrcode::types::QrError::UnsupportedCharacterSet,
            qrcode::types::QrError::InvalidEciDesignator,
            qrcode::types::QrError::InvalidCharacter,
        ];
        for error in errors {
            let mapped = map_encode_error(error);
            assert!(matches!(mapped, QrError::EncodingFailed { .. }), "got {mapped:?}");
        }
    }

    #[test]
    fn byte_limit_holds_for_text_with_short_digit_runs() -> Result<(), Box<dyn std::error::Error>> {
        // Short digit runs make the qrcode optimizer split segments, which can cost
        // more bits than a single byte-mode segment.
        let patterns = [
            "x12345",
            "Call me at 5551234 or 5559876 tomorrow. ",
            "order 48213 shipped, tracking 9400111 ok; ",
        ];
        for pattern in patterns {
            let text = pattern.repeat(MAX_BYTE_CONTENT_LEN_LEVEL_M);
            let at_limit = text.get(..MAX_BYTE_CONTENT_LEN_LEVEL_M).ok_or("pattern too short")?;
            let png = generate_png(at_limit, 256)?;
            assert_eq!(png.get(..8), Some(PNG_MAGIC), "{pattern:?} at the limit must encode");

            let over = text.get(..=MAX_BYTE_CONTENT_LEN_LEVEL_M).ok_or("pattern too short")?;
            let result = generate_png(over, 256);
            assert!(
                matches!(result, Err(QrError::ContentTooLong { .. })),
                "{pattern:?} one byte over must return ContentTooLong, got {result:?}"
            );
        }
        Ok(())
    }

    #[test]
    fn digit_run_content_at_the_limit_round_trips() -> Result<(), Box<dyn std::error::Error>> {
        let text = "x12345".repeat(MAX_BYTE_CONTENT_LEN_LEVEL_M);
        let at_limit = text.get(..MAX_BYTE_CONTENT_LEN_LEVEL_M).ok_or("pattern too short")?;
        let png = generate_png(at_limit, 1024)?;
        let scan = crate::decoder::decode(&png)?;
        assert_eq!(scan.content, at_limit);
        Ok(())
    }

    #[test]
    fn byte_limit_constants_agree() -> Result<(), Box<dyn std::error::Error>> {
        assert_eq!(
            usize::try_from(MAX_BYTE_CONTENT_LEN_LEVEL_M_U32)?,
            MAX_BYTE_CONTENT_LEN_LEVEL_M
        );
        Ok(())
    }

    #[test]
    fn with_config_holds_the_byte_capacity_of_each_level() -> Result<(), Box<dyn std::error::Error>> {
        let capacities = [
            (QrErrorCorrection::Low, 2953),
            (QrErrorCorrection::Medium, 2331),
            (QrErrorCorrection::Quartile, 1663),
            (QrErrorCorrection::High, 1273),
        ];
        for (level, capacity) in capacities {
            let text = "x12345".repeat(capacity);
            let config = QrConfig {
                size: 256,
                error_correction: level,
            };
            let at_limit = text.get(..capacity).ok_or("pattern too short")?;
            let png = generate_with_config(at_limit, config)?;
            assert_eq!(
                png.get(..8),
                Some(PNG_MAGIC),
                "level {level:?} at {capacity} bytes must encode"
            );

            let over = text.get(..=capacity).ok_or("pattern too short")?;
            let result = generate_with_config(over, config);
            assert!(
                matches!(result, Err(QrError::ContentTooLong { .. })),
                "level {level:?} one byte over must return ContentTooLong, got {result:?}"
            );
        }
        Ok(())
    }
}
