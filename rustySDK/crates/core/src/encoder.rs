//! QR code encoding functions.
//!
//! This module provides [`generate_png`] for turning text content into a PNG
//! image containing a QR code, [`generate_with_config`] for configurable
//! generation, [`content_fits`] for checking capacity without rendering, and [`get_library_version`] for querying the crate version at
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

use crate::types::{QrConfig, QrErrorCorrection};
use crate::QrError;

/// Maximum output image size in pixels (width = height).
const MAX_SIZE: u32 = 4096;

/// Error-correction level used by [`generate_png`].
const GENERATE_PNG_LEVEL: QrErrorCorrection = QrErrorCorrection::Medium;

/// Most bytes any QR code can hold: numeric mode at version 40, level L.
///
/// Longer input cannot fit at any level, so [`encode_bits`] rejects it before parsing.
const MAX_ENCODABLE_BYTES: usize = 7089;

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

/// ECI designator for UTF-8 (ISO/IEC 18004, Table 4 / AIM ECI registry).
const ECI_UTF8: u32 = 26;

/// Builds the QR data bits for `data` at `level`. This is THE capacity decision.
///
/// Both generation ([`encode`]) and [`content_fits_at_level`] call this function,
/// so "the bits were built" is exactly "generation will not return
/// [`QrError::ContentTooLong`]": `QrCode::with_bits` cannot fail on capacity.
///
/// * ASCII-only `data` uses the `qrcode` optimizer's mixed-mode segmentation
///   (numeric, alphanumeric, byte). ASCII bytes are below 0x80, so they can never
///   form Kanji pairs.
/// * Any non-ASCII byte switches to UTF-8 mode: an ECI 26 header, then segments
///   that are never Kanji (see [`utf8_segmented_bits`]). `qrcode`'s own parser would
///   pack UTF-8 byte pairs that fall in Shift-JIS ranges into Kanji mode, which other
///   scanners (iOS Camera, `ZXing`, Google Lens) decode as Shift-JIS and garble.
///
/// If the mixed-mode result does not fit, a single byte-mode segment is tried
/// (see [`single_byte_segment_bits`]), because each extra segment header can cost
/// more bits than it saves. Input over [`MAX_ENCODABLE_BYTES`] is rejected first,
/// so a huge paste costs no parsing.
fn encode_bits(data: &[u8], level: qrcode::EcLevel) -> Result<qrcode::bits::Bits, QrError> {
    if data.len() > MAX_ENCODABLE_BYTES {
        return Err(QrError::ContentTooLong {
            reason: format!(
                "{} UTF-8 bytes exceed the {MAX_ENCODABLE_BYTES} a QR code can hold",
                data.len()
            ),
        });
    }
    let utf8 = !data.is_ascii();
    let segmented = if utf8 {
        utf8_segmented_bits(data, level)
    } else {
        qrcode::bits::encode_auto(data, level).ok()
    };
    match segmented {
        Some(bits) => Ok(bits),
        None => single_byte_segment_bits(data, level, utf8),
    }
}

/// Segments non-ASCII `data` without Kanji and packs it behind an ECI 26 header.
///
/// ASCII runs are segmented by the `qrcode` parser (it cannot emit Kanji for bytes
/// below 0x80); each run containing bytes of 0x80 or more becomes one byte segment.
/// The optimizer then merges neighbours wherever that is cheaper, as in `encode_auto`.
/// Segments are optimised per character-count-width class (versions 1-9, 10-26, 27-40);
/// the first class that fits wins, with the smallest fitting version inside it.
fn utf8_segmented_bits(data: &[u8], level: qrcode::EcLevel) -> Option<qrcode::bits::Bits> {
    let mut segments = Vec::new();
    let mut offset = 0;
    for run in data.chunk_by(|a, b| a.is_ascii() == b.is_ascii()) {
        let end = offset + run.len();
        if run.first().is_some_and(u8::is_ascii) {
            segments.extend(
                qrcode::optimize::Parser::new(run).map(|segment| qrcode::optimize::Segment {
                    mode: segment.mode,
                    begin: segment.begin + offset,
                    end: segment.end + offset,
                }),
            );
        } else {
            segments.push(qrcode::optimize::Segment {
                mode: qrcode::types::Mode::Byte,
                begin: offset,
                end,
            });
        }
        offset = end;
    }
    [(1, 9), (10, 26), (27, 40)].into_iter().find_map(|(low, high)| {
        let optimized = qrcode::optimize::Optimizer::new(segments.iter().copied(), qrcode::Version::Normal(high))
            .collect::<Vec<_>>();
        smallest_fitting_bits(low, high, |version| {
            let mut bits = qrcode::bits::Bits::new(version);
            bits.push_eci_designator(ECI_UTF8).ok()?;
            bits.push_segments(data, optimized.iter().copied()).ok()?;
            bits.push_terminator(level).ok()?;
            Some(bits)
        })
    })
}

/// Finds the bits for the smallest normal version in `low..=high` for which `build` succeeds.
///
/// `build` must fail only for capacity, and fit monotonically: if it fits at a version
/// it fits at every larger one. `high` is probed first, so data that fits nowhere in the
/// range costs one pass; otherwise the smallest version is found by binary search.
fn smallest_fitting_bits(
    low: i16,
    high: i16,
    build: impl Fn(qrcode::Version) -> Option<qrcode::bits::Bits>,
) -> Option<qrcode::bits::Bits> {
    let mut best = build(qrcode::Version::Normal(high))?;
    // Invariant: `best` holds the bits for version `high`, which fits; versions below `low` do not.
    let (mut low, mut high) = (low, high);
    while low < high {
        let middle = low + (high - low) / 2;
        match build(qrcode::Version::Normal(middle)) {
            Some(bits) => {
                high = middle;
                best = bits;
            }
            None => low = middle + 1,
        }
    }
    Some(best)
}

/// Encodes `data` as one byte-mode segment (behind an ECI 26 header when `utf8`) in the smallest version.
///
/// Version 40 is probed first: if a single byte segment misses version 40 it
/// misses every version, so oversized input costs one pass instead of forty.
fn single_byte_segment_bits(data: &[u8], level: qrcode::EcLevel, utf8: bool) -> Result<qrcode::bits::Bits, QrError> {
    smallest_fitting_bits(1, 40, |version| byte_segment_bits(data, version, level, utf8)).ok_or_else(|| {
        QrError::ContentTooLong {
            reason: format!(
                "{} UTF-8 bytes do not fit a version 40 QR code at level {level:?}",
                data.len()
            ),
        }
    })
}

/// Packs `data` as a single byte-mode segment at `version`, or `None` if it does not fit.
///
/// `None` can only mean capacity: for `Version::Normal(1..=40)` the only error the
/// `qrcode` push functions return is `DataTooLong` (the ECI designator 26 is valid).
fn byte_segment_bits(
    data: &[u8],
    version: qrcode::Version,
    level: qrcode::EcLevel,
    utf8: bool,
) -> Option<qrcode::bits::Bits> {
    let mut bits = qrcode::bits::Bits::new(version);
    if utf8 {
        bits.push_eci_designator(ECI_UTF8).ok()?;
    }
    (bits.push_byte_data(data).is_ok() && bits.push_terminator(level).is_ok()).then_some(bits)
}

/// Encodes `data` at `level` into a QR code, using [`encode_bits`] for the capacity decision.
fn encode(data: &[u8], level: qrcode::EcLevel) -> Result<qrcode::QrCode, QrError> {
    qrcode::QrCode::with_bits(encode_bits(data, level)?, level).map_err(map_encode_error)
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

    let code = encode(content.as_bytes(), GENERATE_PNG_LEVEL.to_ec_level())?;

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

/// Reports whether [`generate_png`] can encode `content` (error-correction level M).
///
/// `Ok(false)` exactly when [`generate_png`], for any valid size, would return [`QrError::ContentTooLong`].
/// It runs the same bit-building path as generation but stops before
/// Reed-Solomon, matrix and PNG rendering, so it is cheap enough to call per keystroke.
///
/// # Errors
///
/// * [`QrError::InvalidInput`] – `content` is empty.
/// * [`QrError::EncodingFailed`] – the `qrcode` library failed for a reason other than capacity.
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
/// * [`QrError::EncodingFailed`] – the `qrcode` library failed for a reason other than capacity.
fn content_fits_at_level(content: &str, error_correction: QrErrorCorrection) -> Result<bool, QrError> {
    validate_content(content)?;
    match encode_bits(content.as_bytes(), error_correction.to_ec_level()) {
        Ok(_) => Ok(true),
        Err(QrError::ContentTooLong { .. }) => Ok(false),
        Err(other) => Err(other),
    }
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

    /// ISO 18004 version 40 capacities as `(level, numeric, alphanumeric, byte)`. Test-only oracle.
    const V40_CAPACITY: [(QrErrorCorrection, usize, usize, usize); 4] = [
        (QrErrorCorrection::Low, 7089, 4296, 2953),
        (QrErrorCorrection::Medium, 5596, 3391, 2331),
        (QrErrorCorrection::Quartile, 3993, 2420, 1663),
        (QrErrorCorrection::High, 3057, 1852, 1273),
    ];

    /// Byte-mode capacity at level M, from [`V40_CAPACITY`].
    const BYTE_CAPACITY_LEVEL_M: usize = {
        let [_, (_, _, _, byte), _, _] = V40_CAPACITY;
        byte
    };

    /// Capacity at level M for content with any non-ASCII byte: [`BYTE_CAPACITY_LEVEL_M`] minus the 12-bit ECI header.
    const UTF8_CAPACITY_LEVEL_M: usize = 2330;

    /// Upper bound on characters a scanning test tries before giving up.
    const SCAN_LIMIT: usize = 20_000;

    type TestResult<T = ()> = Result<T, Box<dyn std::error::Error>>;

    /// Whether real generation accepts `content`; any error other than `ContentTooLong` is propagated.
    fn generation_accepts(content: &str, level: QrErrorCorrection) -> TestResult<bool> {
        let config = QrConfig {
            size: 64,
            error_correction: level,
        };
        match generate_with_config(content, config) {
            Ok(_) => Ok(true),
            Err(QrError::ContentTooLong { .. }) => Ok(false),
            Err(other) => Err(other.into()),
        }
    }

    /// Asserts `content_fits_at_level` equals real generation and returns the verdict.
    fn fits_agreeing_with_generation(content: &str, level: QrErrorCorrection) -> TestResult<bool> {
        let fits = content_fits_at_level(content, level)?;
        let generated = generation_accepts(content, level)?;
        assert_eq!(
            fits,
            generated,
            "content_fits and generation disagree at level {level:?} for {} bytes",
            content.len()
        );
        Ok(fits)
    }

    /// Repeats `unit` char by char until the first overflow at level M.
    ///
    /// Returns `(longest fitting text, shortest overflowing text)`. Fails if the text overflows
    /// at or below `guarantee` bytes.
    fn scan_to_overflow(unit: &str, guarantee: usize) -> TestResult<(String, String)> {
        let mut text = String::new();
        for next in unit.chars().cycle().take(SCAN_LIMIT) {
            let previous_len = text.len();
            text.push(next);
            if !content_fits(&text)? {
                assert!(
                    previous_len + next.len_utf8() > guarantee,
                    "{} bytes of {unit:?} must always fit",
                    text.len()
                );
                let fitting = text.get(..previous_len).ok_or("not a char boundary")?.to_owned();
                return Ok((fitting, text));
            }
        }
        Err(format!("{unit:?} never overflowed within {SCAN_LIMIT} chars").into())
    }

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
        let at_limit = generate_png(&"a".repeat(BYTE_CAPACITY_LEVEL_M), 256)?;
        assert_eq!(at_limit.get(..8), Some(PNG_MAGIC), "content at the limit must encode");

        let over = generate_png(&"a".repeat(BYTE_CAPACITY_LEVEL_M + 1), 256);
        assert!(
            matches!(over, Err(QrError::ContentTooLong { .. })),
            "one byte over the limit must return ContentTooLong, got {over:?}"
        );
        Ok(())
    }

    #[test]
    fn multibyte_boundary_is_measured_in_utf8_bytes() -> Result<(), Box<dyn std::error::Error>> {
        let at_limit = "é".repeat(UTF8_CAPACITY_LEVEL_M / 2);
        assert_eq!(at_limit.len(), UTF8_CAPACITY_LEVEL_M);
        let encoded = generate_png(&at_limit, 256)?;
        assert_eq!(encoded.get(..8), Some(PNG_MAGIC), "content at the limit must encode");

        let over = format!("{at_limit}a");
        assert_eq!(over.len(), UTF8_CAPACITY_LEVEL_M + 1);
        let result = generate_png(&over, 256);
        assert!(
            matches!(result, Err(QrError::ContentTooLong { .. })),
            "one UTF-8 byte over the limit must return ContentTooLong, got {result:?}"
        );

        // Fewer characters than the limit, but more bytes: still too long.
        let few_chars = "é".repeat(UTF8_CAPACITY_LEVEL_M / 2 + 1);
        assert!(few_chars.chars().count() <= UTF8_CAPACITY_LEVEL_M);
        assert!(
            few_chars.len() > UTF8_CAPACITY_LEVEL_M,
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
        let digits = "7".repeat(BYTE_CAPACITY_LEVEL_M + 1);
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
        let result = generate_with_config(&"a".repeat(BYTE_CAPACITY_LEVEL_M), config);
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
            let text = pattern.repeat(BYTE_CAPACITY_LEVEL_M);
            let at_limit = text.get(..BYTE_CAPACITY_LEVEL_M).ok_or("pattern too short")?;
            let png = generate_png(at_limit, 256)?;
            assert_eq!(png.get(..8), Some(PNG_MAGIC), "{pattern:?} at the limit must encode");

            let over = text.get(..=BYTE_CAPACITY_LEVEL_M).ok_or("pattern too short")?;
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
        let text = "x12345".repeat(BYTE_CAPACITY_LEVEL_M);
        let at_limit = text.get(..BYTE_CAPACITY_LEVEL_M).ok_or("pattern too short")?;
        let png = generate_png(at_limit, 1024)?;
        let scan = crate::decoder::decode(&png)?;
        assert_eq!(scan.content, at_limit);
        Ok(())
    }

    #[test]
    fn with_config_holds_the_byte_capacity_of_each_level() -> Result<(), Box<dyn std::error::Error>> {
        for (level, _, _, capacity) in V40_CAPACITY {
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

    #[test]
    fn content_fits_matches_spec_capacity_per_mode_and_level() -> TestResult {
        for (level, numeric, alphanumeric, byte) in V40_CAPACITY {
            for (unit, capacity) in [("7", numeric), ("A", alphanumeric), ("a", byte)] {
                assert!(
                    content_fits_at_level(&unit.repeat(capacity), level)?,
                    "{capacity} x {unit:?} must fit at {level:?}"
                );
                assert!(
                    !content_fits_at_level(&unit.repeat(capacity + 1), level)?,
                    "{} x {unit:?} must not fit at {level:?}",
                    capacity + 1
                );
            }
        }
        assert!(content_fits(&"7".repeat(5596))?);
        assert!(!content_fits(&"7".repeat(5597))?);
        assert!(content_fits(&"A".repeat(3391))?);
        assert!(!content_fits(&"A".repeat(3392))?);
        assert!(content_fits(&"a".repeat(2331))?);
        assert!(!content_fits(&"a".repeat(2332))?);
        Ok(())
    }

    #[test]
    fn content_fits_agrees_with_generation_at_every_boundary() -> TestResult {
        for (level, numeric, alphanumeric, byte) in V40_CAPACITY {
            for (unit, capacity) in [("7", numeric), ("A", alphanumeric), ("a", byte)] {
                for length in [capacity - 1, capacity, capacity + 1] {
                    let content = unit.repeat(length);
                    fits_agreeing_with_generation(&content, level)?;
                }
            }
        }
        Ok(())
    }

    #[test]
    fn content_fits_measures_multibyte_utf8_in_bytes() -> TestResult {
        let at_limit = "é".repeat(UTF8_CAPACITY_LEVEL_M / 2);
        assert_eq!(at_limit.len(), UTF8_CAPACITY_LEVEL_M);
        assert!(content_fits(&at_limit)?, "2330 bytes must fit");
        assert!(!content_fits(&format!("{at_limit}a"))?, "2331 bytes must not fit");
        let few_chars = "é".repeat(UTF8_CAPACITY_LEVEL_M / 2 + 1);
        assert!(!content_fits(&few_chars)?, "1166 chars of 2 bytes must not fit");
        Ok(())
    }

    #[test]
    fn non_ascii_content_starts_with_the_utf8_eci_header() -> TestResult {
        // ECI mode indicator 0111, designator 26 = 0001_1010, then byte mode 0100.
        for text in ["こんにちは世界", "Café 東京 2026", "😀"] {
            let bytes = encode_bits(text.as_bytes(), qrcode::EcLevel::M)?.into_bytes();
            assert_eq!(
                bytes.get(..2),
                Some(&[0x71, 0xA4][..]),
                "{text:?} must open ECI 26 + byte mode"
            );
        }
        let ascii = encode_bits(b"hello", qrcode::EcLevel::M)?.into_bytes();
        assert_ne!(
            ascii.first().map(|byte| byte >> 4),
            Some(0b0111),
            "ASCII must not carry an ECI header"
        );
        Ok(())
    }

    #[test]
    fn non_ascii_content_with_ascii_runs_uses_compact_segments() -> TestResult {
        // A long digit run beside one accented letter must still use numeric mode.
        let text = format!("é{}", "7".repeat(3000));
        assert!(
            content_fits(&text)?,
            "digit run must keep numeric packing next to non-ASCII"
        );
        assert!(fits_agreeing_with_generation(&text, QrErrorCorrection::Medium)?);
        let png = generate_png(&text, 1024)?;
        assert_eq!(crate::decoder::decode(&png)?.content, text);
        Ok(())
    }

    #[test]
    fn non_ascii_content_round_trips_as_utf8() -> TestResult {
        for text in [
            "こんにちは世界",
            "Café 東京 2026",
            "世界",
            "한국어 テスト",
            "😀🎉 ok",
            "ÀÉÎ",
        ] {
            let png = generate_png(text, 512)?;
            assert_eq!(crate::decoder::decode(&png)?.content, text);
        }
        Ok(())
    }

    #[test]
    fn content_fits_cjk_and_emoji_stay_within_the_utf8_capacity() -> TestResult {
        for unit in ["世界", "😀"] {
            let (fitting, overflowing) = scan_to_overflow(unit, UTF8_CAPACITY_LEVEL_M)?;
            println!(
                "limit for {unit:?} at level M: {} chars ({} UTF-8 bytes)",
                fitting.chars().count(),
                fitting.len()
            );
            assert!(
                fitting.len() > UTF8_CAPACITY_LEVEL_M - unit.len() && fitting.len() <= UTF8_CAPACITY_LEVEL_M,
                "{unit:?} must fill the UTF-8 capacity, got {} bytes",
                fitting.len()
            );
            assert!(fits_agreeing_with_generation(&fitting, QrErrorCorrection::Medium)?);
            assert!(!fits_agreeing_with_generation(&overflowing, QrErrorCorrection::Medium)?);
        }
        Ok(())
    }

    #[test]
    fn content_fits_digit_runs_exceed_byte_capacity() -> TestResult {
        let unit = format!("id={};", "0123456789".repeat(4));
        let (fitting, overflowing) = scan_to_overflow(&unit, BYTE_CAPACITY_LEVEL_M)?;
        println!("limit for the id= digit-run unit at level M: {} bytes", fitting.len());
        assert!(
            fitting.len() > BYTE_CAPACITY_LEVEL_M,
            "digit-heavy text must exceed the byte capacity, got {}",
            fitting.len()
        );
        assert!(fits_agreeing_with_generation(&fitting, QrErrorCorrection::Medium)?);
        assert!(!fits_agreeing_with_generation(&overflowing, QrErrorCorrection::Medium)?);
        let png = generate_png(&fitting, 1024)?;
        assert_eq!(crate::decoder::decode(&png)?.content, fitting);
        Ok(())
    }

    #[test]
    fn content_fits_x12345_boundary_is_the_byte_fallback() -> TestResult {
        let text = "x12345".repeat(BYTE_CAPACITY_LEVEL_M);
        let at_limit = text.get(..BYTE_CAPACITY_LEVEL_M).ok_or("pattern too short")?;
        let over = text.get(..=BYTE_CAPACITY_LEVEL_M).ok_or("pattern too short")?;
        assert!(fits_agreeing_with_generation(at_limit, QrErrorCorrection::Medium)?);
        assert!(!fits_agreeing_with_generation(over, QrErrorCorrection::Medium)?);
        Ok(())
    }

    #[test]
    fn byte_capacity_level_m_comes_from_the_medium_row() {
        assert_eq!(V40_CAPACITY.get(1).map(|row| row.0), Some(QrErrorCorrection::Medium));
        assert_eq!(BYTE_CAPACITY_LEVEL_M, 2331);
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
    fn oversized_paste_is_rejected() -> TestResult {
        let paste = "a".repeat(100_000);
        assert!(!content_fits(&paste)?);
        let result = generate_png(&paste, 256);
        assert!(
            matches!(result, Err(QrError::ContentTooLong { .. })),
            "got {:?}",
            result.map(|png| png.len())
        );
        Ok(())
    }

    #[test]
    fn input_over_the_encodable_cap_is_rejected_before_parsing() -> TestResult {
        assert!(content_fits_at_level(
            &"7".repeat(MAX_ENCODABLE_BYTES),
            QrErrorCorrection::Low
        )?);
        for level in [QrErrorCorrection::Low, QrErrorCorrection::High] {
            assert!(!fits_agreeing_with_generation(
                &"7".repeat(MAX_ENCODABLE_BYTES + 1),
                level
            )?);
            assert!(!fits_agreeing_with_generation(&"a".repeat(1_000_000), level)?);
        }
        Ok(())
    }

    #[test]
    fn byte_fallback_picks_smallest_version() -> TestResult {
        let text = "x12345".repeat(400);
        let data = text.as_bytes().get(..1800).ok_or("short")?;
        let level = qrcode::EcLevel::M;
        assert!(
            qrcode::bits::encode_auto(data, level).is_err(),
            "must take the byte fallback"
        );
        assert_eq!(encode_bits(data, level)?.version(), qrcode::Version::Normal(35));
        assert!(byte_segment_bits(data, qrcode::Version::Normal(34), level, false).is_none());
        Ok(())
    }

    /// The previous linear search, kept as the oracle for the binary search.
    fn linear_byte_segment_bits(data: &[u8], level: qrcode::EcLevel, utf8: bool) -> Option<qrcode::bits::Bits> {
        (1..=40).find_map(|version| byte_segment_bits(data, qrcode::Version::Normal(version), level, utf8))
    }

    #[test]
    fn binary_version_search_matches_the_linear_search() -> TestResult {
        let text = "x12345".repeat(500);
        for (utf8, level) in [false, true].into_iter().flat_map(|utf8| {
            [
                qrcode::EcLevel::L,
                qrcode::EcLevel::M,
                qrcode::EcLevel::Q,
                qrcode::EcLevel::H,
            ]
            .map(move |level| (utf8, level))
        }) {
            // The `utf8` flag only changes the ECI header; the data is the same bytes either way.
            for length in 1..=3000 {
                let data = text.as_bytes().get(..length).ok_or("pattern too short")?;
                let binary = single_byte_segment_bits(data, level, utf8).ok();
                let linear = linear_byte_segment_bits(data, level, utf8);
                assert_eq!(
                    binary.as_ref().map(qrcode::bits::Bits::version),
                    linear.as_ref().map(qrcode::bits::Bits::version),
                    "version differs at {length} bytes, level {level:?}"
                );
                assert_eq!(
                    binary.map(qrcode::bits::Bits::into_bytes),
                    linear.map(qrcode::bits::Bits::into_bytes),
                    "bits differ at {length} bytes, level {level:?}"
                );
            }
        }
        Ok(())
    }
}
