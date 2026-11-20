//! QR code encoding functions.
//!
//! This module provides [`generate_png`] for turning text content into a PNG
//! image containing a QR code, [`generate_with_config`] for configurable
//! generation, [`content_fits`] for checking capacity without rendering, and
//! [`get_library_version`] for querying the crate version at runtime.
//!
//! # Pipeline
//!
//! 1. **Validate** inputs (content non-empty, size in range).
//! 2. **Encode** content into a QR matrix via the `qrcode` crate: the smallest version that fits the shortest of
//!    the three per-class optimisations and one byte segment (see the private `segment` module).
//! 3. **Render** the matrix to a grayscale `image::GrayImage` at the largest whole number of pixels per module
//!    that fits the requested size, quiet zone included, so every module has the same size.
//! 4. **Centre** the rendering on a white canvas of the requested dimensions.
//! 5. **Serialise** to PNG bytes via `image::codecs::png::PngEncoder`.

mod segment;

use image::codecs::png::PngEncoder;
use image::{imageops, ExtendedColorType, GrayImage, ImageEncoder, Luma};

use crate::types::{QrConfig, QrErrorCorrection};
use crate::QrError;

/// Maximum output image size in pixels (width = height).
const MAX_SIZE: u32 = 4096;

/// Width of the quiet zone on each side, in modules (the `qrcode` renderer's default, ISO/IEC 18004).
const QUIET_ZONE_MODULES: u32 = 4;

/// Error-correction level used by [`generate_png`].
const GENERATE_PNG_LEVEL: QrErrorCorrection = QrErrorCorrection::Medium;

/// Encodes `data` at `level` into a QR code, using [`segment::encode_bits`] for the capacity decision.
///
/// Both generation and [`content_fits_at_level`] call `encode_bits`, so "the bits were built" is exactly
/// "generation will not return [`QrError::ContentTooLong`]". `QrCode::with_bits` cannot fail on capacity,
/// because the bits already fit their version.
fn encode(data: &[u8], level: qrcode::EcLevel) -> Result<qrcode::QrCode, QrError> {
    let bits = segment::encode_bits(data, level).ok_or_else(|| QrError::ContentTooLong {
        reason: format!(
            "{} UTF-8 bytes do not fit a version 40 QR code at level {level:?}",
            data.len()
        ),
    })?;
    qrcode::QrCode::with_bits(bits, level).map_err(|e| QrError::EncodingFailed { reason: e.to_string() })
}

/// Validates that `content` is not empty.
///
/// # Errors
///
/// * [`QrError::InvalidInput`] – `content` is empty.
fn validate_content(content: &str) -> Result<(), QrError> {
    if content.is_empty() {
        return Err(QrError::InvalidInput {
            reason: "content must not be empty".into(),
        });
    }
    Ok(())
}

/// Validates the common inputs shared by all generation functions.
///
/// # Errors
///
/// * [`QrError::InvalidInput`] – `content` is empty, or `size` is 0 or greater
///   than [`MAX_SIZE`].
fn validate_generate_inputs(content: &str, size: u32) -> Result<(), QrError> {
    validate_content(content)?;
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
/// code is drawn at the largest whole number of pixels per module that fits
/// `size` together with its quiet zone, so every module is the same size and
/// the code stays scannable. It is centred on a white background, which makes
/// the margin up to one module wider than the quiet zone on each side.
///
/// # Arguments
///
/// * `content` – The text to encode. Must not be empty.
/// * `size`    – Output image side length in pixels. Must be in `1..=4096`,
///   and at least the code's width in modules plus 8 (the quiet zone), which
///   is 29 for the smallest code and 185 for the largest.
///
/// # Errors
///
/// * [`QrError::InvalidInput`] – `content` is empty, `size` is 0 or greater
///   than 4096, or `size` is too small for one pixel per module.
/// * [`QrError::ContentTooLong`] – The content exceeds the capacity of the
///   largest QR code at the chosen error-correction level.
/// * [`QrError::EncodingFailed`] – The `qrcode` library could not build the
///   code from the packed data.
/// * [`QrError::ImageError`] – The PNG serialisation step failed.
pub fn generate_png(content: &str, size: u32) -> Result<Vec<u8>, QrError> {
    validate_generate_inputs(content, size)?;

    let code = encode(content.as_bytes(), GENERATE_PNG_LEVEL.to_ec_level())?;

    render_qr_to_png(&code, size)
}

/// Generates a QR code PNG from the given text content with custom configuration.
///
/// This function allows specifying the output size and error correction level.
/// The output image is square; both width and height equal `config.size`.
/// See [`generate_png`] for how the code is drawn at that size.
///
/// # Arguments
///
/// * `content` – The text to encode. Must not be empty.
/// * `config`  – Configuration specifying the output size and error correction
///   level. `config.size` must be in `1..=4096` and large enough for one pixel
///   per module (see [`generate_png`]).
///
/// # Errors
///
/// * [`QrError::InvalidInput`] – `content` is empty, `config.size` is 0 or
///   greater than 4096, or `config.size` is too small for one pixel per module.
/// * [`QrError::ContentTooLong`] – The content exceeds the capacity of the
///   largest QR code at the chosen error-correction level.
/// * [`QrError::EncodingFailed`] – The `qrcode` library could not build the
///   code from the packed data.
/// * [`QrError::ImageError`] – The PNG serialisation step failed.
pub fn generate_with_config(content: &str, config: QrConfig) -> Result<Vec<u8>, QrError> {
    validate_generate_inputs(content, config.size)?;

    let code = encode(content.as_bytes(), config.error_correction.to_ec_level())?;

    render_qr_to_png(&code, config.size)
}

/// Reports whether [`generate_png`] can encode `content` (error-correction level M).
///
/// `Ok(false)` exactly when [`generate_png`], for any valid size, would return [`QrError::ContentTooLong`].
/// It runs the same bit-building path as generation but stops before
/// Reed-Solomon, matrix and PNG rendering, so it is cheap enough to call per keystroke.
///
/// # Errors
///
/// * [`QrError::InvalidInput`] – `content` is empty.
pub fn content_fits(content: &str) -> Result<bool, QrError> {
    content_fits_at_level(content, GENERATE_PNG_LEVEL)
}

/// Reports whether [`generate_with_config`] can encode `content` at `error_correction`.
///
/// `Ok(false)` exactly when generation, for any valid size, would return [`QrError::ContentTooLong`].
///
/// # Errors
///
/// * [`QrError::InvalidInput`] – `content` is empty.
fn content_fits_at_level(content: &str, error_correction: QrErrorCorrection) -> Result<bool, QrError> {
    validate_content(content)?;
    Ok(segment::encode_bits(content.as_bytes(), error_correction.to_ec_level()).is_some())
}

/// Returns the version of this library as declared in `Cargo.toml`.
///
/// This function is infallible and always returns a non-empty string.
#[must_use]
pub fn get_library_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

/// Renders a `QrCode` to a PNG byte buffer of `size` x `size` pixels.
///
/// Shared by both [`generate_png`] and [`generate_with_config`] to keep
/// rendering logic in one place (DRY). Modules are drawn at a whole number of pixels
/// each; scaling the image by a fractional factor would give modules uneven widths,
/// which some decoders cannot read.
fn render_qr_to_png(code: &qrcode::QrCode, size: u32) -> Result<Vec<u8>, QrError> {
    let modules_with_quiet_zone = u32::try_from(code.width())
        .unwrap_or(u32::MAX)
        .saturating_add(2 * QUIET_ZONE_MODULES);
    let pixels_per_module = size / modules_with_quiet_zone;
    if pixels_per_module == 0 {
        return Err(QrError::InvalidInput {
            reason: format!(
                "size {size} is too small for this content: its code is {modules_with_quiet_zone} modules wide \
                 with the quiet zone, so size must be at least {modules_with_quiet_zone}"
            ),
        });
    }

    let drawing = code
        .render::<Luma<u8>>()
        .module_dimensions(pixels_per_module, pixels_per_module)
        .build();

    // Centre the drawing on a white canvas of the requested size.
    let offset = i64::from((size - drawing.width()) / 2);
    let mut canvas = GrayImage::from_pixel(size, size, Luma([u8::MAX]));
    imageops::replace(&mut canvas, &drawing, offset, offset);

    // --- PNG serialisation ---
    let mut buf: Vec<u8> = Vec::new();
    PngEncoder::new(&mut buf)
        .write_image(canvas.as_raw(), canvas.width(), canvas.height(), ExtendedColorType::L8)
        .map_err(|e| QrError::ImageError { reason: e.to_string() })?;

    Ok(buf)
}

#[cfg(test)]
mod tests {
    use super::*;

    type TestResult<T = ()> = Result<T, Box<dyn std::error::Error>>;

    /// PNG file signature — the first 8 bytes of every valid PNG file.
    const PNG_MAGIC: &[u8] = b"\x89PNG\r\n\x1a\n";

    const ALL_LEVELS: [QrErrorCorrection; 4] = [
        QrErrorCorrection::Low,
        QrErrorCorrection::Medium,
        QrErrorCorrection::Quartile,
        QrErrorCorrection::High,
    ];

    /// The app's size: what the Kotlin and Swift callers pass.
    const APP_SIZE: u32 = 512;

    /// Asserts `png` starts with the PNG signature.
    fn assert_png(png: &[u8]) {
        assert_eq!(png.get(..8), Some(PNG_MAGIC), "output must start with PNG magic bytes");
    }

    fn config(size: u32, error_correction: QrErrorCorrection) -> QrConfig {
        QrConfig { size, error_correction }
    }

    /// Decodes `png` back to a grayscale image.
    fn gray_image(png: &[u8]) -> TestResult<GrayImage> {
        Ok(image::load_from_memory(png)?.to_luma8())
    }

    #[test]
    fn generate_png_produces_a_png_for_text_urls_and_unicode() -> TestResult {
        for content in ["hello", "https://example.com?foo=bar", "Hello 世界 🌍"] {
            let png = generate_png(content, 256)?;
            assert_png(&png);
            assert!(png.len() > 100, "{content:?}: PNG should have meaningful content");
        }
        Ok(())
    }

    #[test]
    fn output_dimensions_match_requested_size() -> TestResult {
        // 256 is not a multiple of the 29 modules of a version 1 code with its quiet zone.
        for size in [256, 300, 512] {
            let image = gray_image(&generate_png("hello", size)?)?;
            assert_eq!((image.width(), image.height()), (size, size), "requested {size}");
        }
        Ok(())
    }

    #[test]
    fn empty_content_is_invalid_input() {
        let result = generate_png("", 256);
        assert!(matches!(result, Err(QrError::InvalidInput { .. })), "got {result:?}");
        let result = generate_with_config("", config(256, QrErrorCorrection::Medium));
        assert!(matches!(result, Err(QrError::InvalidInput { .. })), "got {result:?}");
    }

    #[test]
    fn out_of_range_size_is_invalid_input() {
        for size in [0, MAX_SIZE + 1] {
            let result = generate_png("hello", size);
            assert!(matches!(result, Err(QrError::InvalidInput { .. })), "got {result:?}");
            let result = generate_with_config("hello", config(size, QrErrorCorrection::Medium));
            assert!(matches!(result, Err(QrError::InvalidInput { .. })), "got {result:?}");
        }
    }

    #[test]
    fn max_size_succeeds() -> TestResult {
        assert_png(&generate_png("hello", MAX_SIZE)?);
        Ok(())
    }

    #[test]
    fn size_without_one_pixel_per_module_is_invalid_input() -> TestResult {
        // "hello" is a 21-module version 1 code: 29 modules with the quiet zone.
        for size in [1, 28] {
            let result = generate_png("hello", size);
            assert!(
                matches!(result, Err(QrError::InvalidInput { .. })),
                "size {size} must be rejected, got {result:?}"
            );
        }
        assert_eq!(gray_image(&generate_png("hello", 29)?)?.width(), 29);

        // A version 40 code is 177 modules, 185 with the quiet zone.
        let big = "a".repeat(2331);
        assert!(matches!(generate_png(&big, 184), Err(QrError::InvalidInput { .. })));
        assert_eq!(gray_image(&generate_png(&big, 185)?)?.width(), 185);
        Ok(())
    }

    #[test]
    fn modules_are_whole_pixels_centred_on_white() -> TestResult {
        // Version 1, 29 modules with the quiet zone: 300 / 29 = 10 px per module, a 290 px drawing, 5 px margin.
        let image = gray_image(&generate_png("hello", 300)?)?;
        let (px, margin) = (10, 5);
        let at = |x: u32, y: u32| image.get_pixel_checked(x, y).map(|&Luma([value])| value);

        // The top row of the top-left finder pattern is 7 dark modules, after 4 light ones of quiet zone, then
        // the separator module.
        let row = margin + 4 * px + px / 2;
        for x in 0..margin + 12 * px {
            let in_finder = (margin + 4 * px..margin + 11 * px).contains(&x);
            let expected = if in_finder { 0 } else { u8::MAX };
            assert_eq!(at(x, row), Some(expected), "pixel ({x}, {row})");
        }
        // Everything outside the drawing is white.
        for edge in [0, margin - 1, margin + 29 * px, 299] {
            for other in [0, 150, 299] {
                assert_eq!(at(edge, other), Some(u8::MAX), "pixel ({edge}, {other})");
                assert_eq!(at(other, edge), Some(u8::MAX), "pixel ({other}, {edge})");
            }
        }
        Ok(())
    }

    #[test]
    fn library_version_matches_cargo() {
        assert_eq!(get_library_version(), "0.1.0");
    }

    #[test]
    fn generate_with_config_produces_a_png_at_every_level() -> TestResult {
        for level in ALL_LEVELS {
            let png = generate_with_config("hello", config(256, level))?;
            assert_png(&png);
            assert!(png.len() > 100, "level {level:?}: PNG should have meaningful content");
        }
        Ok(())
    }

    #[test]
    fn content_fits_empty_is_invalid_input() {
        assert!(matches!(content_fits(""), Err(QrError::InvalidInput { .. })));
        assert!(matches!(
            content_fits_at_level("", QrErrorCorrection::High),
            Err(QrError::InvalidInput { .. })
        ));
    }

    #[test]
    fn content_fits_at_level_agrees_with_generation_at_every_level() -> TestResult {
        // The boundary table in tests/capacity.rs covers the public API; this covers the private per-level entry point.
        for level in ALL_LEVELS {
            for content in ["a".repeat(1273), "a".repeat(1274), "é".repeat(636), "é".repeat(637)] {
                let generated = match generate_with_config(&content, config(256, level)) {
                    Ok(_) => true,
                    Err(QrError::ContentTooLong { .. }) => false,
                    Err(other) => return Err(other.into()),
                };
                assert_eq!(
                    content_fits_at_level(&content, level)?,
                    generated,
                    "{} bytes at level {level:?}",
                    content.len()
                );
            }
        }
        Ok(())
    }

    #[test]
    fn every_version_round_trips_at_the_app_size() -> TestResult {
        // Fullest text of each version at level M: 'a' runs are one byte segment, so length alone picks the version.
        let mut fullest = [0_usize; 40];
        for length in 1..=2331 {
            let bits = segment::encode_bits(&b"a".repeat(length), QrErrorCorrection::Medium.to_ec_level())
                .ok_or("must fit")?;
            let qrcode::Version::Normal(number) = bits.version() else {
                return Err("expected a normal version".into());
            };
            let slot = fullest
                .get_mut(usize::try_from(number - 1)?)
                .ok_or("version out of range")?;
            *slot = length;
        }
        for (index, &length) in fullest.iter().enumerate() {
            assert!(length > 0, "no sample for version {}", index + 1);
            let text = "a".repeat(length);
            let png = generate_png(&text, APP_SIZE)?;
            assert_eq!(
                crate::decoder::decode(&png)?.content,
                text,
                "version {} ({length} bytes) must scan at {APP_SIZE} px",
                index + 1
            );
        }
        Ok(())
    }
}
