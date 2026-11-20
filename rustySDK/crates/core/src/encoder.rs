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
//! 2. **Encode** content into a QR matrix via the `qrcode` crate: the smallest version that fits the shortest of
//!    the three per-class optimisations and one byte segment (see `encode_bits`).
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
/// All input goes through [`segmented_bits`]: segments that are never Kanji, the shortest of the
/// three per-class optimisations and one whole byte segment, and the smallest fitting version.
///
/// * ASCII-only `data` carries no ECI header. ASCII bytes are below 0x80, so they can never
///   form Kanji pairs, and the result is never larger than `qrcode`'s own automatic choice or
///   than one byte-mode segment would need.
/// * Any non-ASCII byte switches to UTF-8 mode: an ECI 26 header first. `qrcode`'s own parser
///   would pack UTF-8 byte pairs that fall in Shift-JIS ranges into Kanji mode, which other
///   scanners (iOS Camera, `ZXing`, Google Lens) decode as Shift-JIS and garble.
///
/// Input over [`MAX_ENCODABLE_BYTES`] is rejected first, so a huge paste costs no parsing.
fn encode_bits(data: &[u8], level: qrcode::EcLevel) -> Result<qrcode::bits::Bits, QrError> {
    if data.len() > MAX_ENCODABLE_BYTES {
        return Err(QrError::ContentTooLong {
            reason: format!(
                "{} UTF-8 bytes exceed the {MAX_ENCODABLE_BYTES} a QR code can hold",
                data.len()
            ),
        });
    }
    segmented_bits(data, level, (!data.is_ascii()).then_some(ECI_UTF8)).ok_or_else(|| QrError::ContentTooLong {
        reason: format!(
            "{} UTF-8 bytes do not fit a version 40 QR code at level {level:?}",
            data.len()
        ),
    })
}

/// Splits `data` into segments that are never Kanji, before any optimisation.
///
/// ASCII runs are segmented by the `qrcode` parser (it cannot emit Kanji for bytes
/// below 0x80); each run of bytes of 0x80 or more becomes one byte segment.
fn kanji_free_segments(data: &[u8]) -> Vec<qrcode::optimize::Segment> {
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
    segments
}

/// Character-count-width classes of normal versions: `(lowest, highest)` version of each.
const CLASSES: [(i16, i16); 3] = [(1, 9), (10, 26), (27, 40)];

/// Candidate segmentations of `data_len` bytes: `segments` optimised for each class in
/// [`CLASSES`], then one whole byte segment.
///
/// The optimizer merges neighbours only pairwise, and every extra segment header costs bits, so
/// one whole byte segment is sometimes smaller, and a list optimised for one class is sometimes
/// shorter than another class's own optimum at that class's widths. Offering every candidate to
/// every class never loses to any single one of them.
fn candidate_segmentations(
    segments: &[qrcode::optimize::Segment],
    data_len: usize,
) -> Vec<Vec<qrcode::optimize::Segment>> {
    let mut candidates = Vec::from(CLASSES.map(|(_, high)| {
        qrcode::optimize::Optimizer::new(segments.iter().copied(), qrcode::Version::Normal(high)).collect::<Vec<_>>()
    }));
    candidates.push(vec![qrcode::optimize::Segment {
        mode: qrcode::types::Mode::Byte,
        begin: 0,
        end: data_len,
    }]);
    candidates
}

/// Packs `segments` of `data` at `version`, behind an ECI `eci` header when set, or `None` if they do not fit.
///
/// `None` can only mean capacity: the segments are never Kanji and the designator is valid,
/// so for `Version::Normal(1..=40)` the only error the `qrcode` push functions return is `DataTooLong`.
fn bits_at(
    data: &[u8],
    segments: &[qrcode::optimize::Segment],
    version: qrcode::Version,
    level: qrcode::EcLevel,
    eci: Option<u32>,
) -> Option<qrcode::bits::Bits> {
    let mut bits = qrcode::bits::Bits::new(version);
    if let Some(designator) = eci {
        bits.push_eci_designator(designator).ok()?;
    }
    bits.push_segments(data, segments.iter().copied()).ok()?;
    bits.push_terminator(level).ok()?;
    Some(bits)
}

/// Size in bits of the ECI header for `designator`, measured by packing it (12 for [`ECI_UTF8`]).
fn eci_header_bits(designator: u32) -> Option<usize> {
    let mut header = qrcode::bits::Bits::new(qrcode::Version::Normal(1));
    header.push_eci_designator(designator).ok()?;
    Some(header.len())
}

/// Segments `data` without Kanji and packs it, behind an ECI `eci` header when set.
///
/// Segments come from [`kanji_free_segments`]. For each class in [`CLASSES`] in turn, the shortest of
/// [`candidate_segmentations`] at that class's widths wins (ties keep the earlier, optimised
/// list). Its length, plus the ECI header, gives the smallest version of the class whose data
/// capacity holds it, and the data is packed once at that version. The class widths are fixed
/// within a class, so the length does not change between its versions, and a length field
/// cannot overflow before capacity does.
///
/// That single pack is the deciding check: if it fails, the next class is tried. Today it cannot
/// fail: the computed length is exact and a length field cannot overflow before capacity runs
/// out, so the fallback is purely defensive. `None` means the data does not fit version 40 at `level`.
fn segmented_bits(data: &[u8], level: qrcode::EcLevel, eci: Option<u32>) -> Option<qrcode::bits::Bits> {
    let header_bits = eci.map_or(Some(0), eci_header_bits)?;
    let candidates = candidate_segmentations(&kanji_free_segments(data), data.len());
    CLASSES.into_iter().find_map(|(low, high)| {
        let top = qrcode::Version::Normal(high);
        let chosen = candidates
            .iter()
            .min_by_key(|candidate| qrcode::optimize::total_encoded_len(candidate, top))?;
        let needed = qrcode::optimize::total_encoded_len(chosen, top) + header_bits;
        let version = (low..=high).map(qrcode::Version::Normal).find(|&version| {
            qrcode::bits::Bits::new(version)
                .max_len(level)
                .is_ok_and(|capacity| needed <= capacity)
        })?;
        bits_at(data, chosen, version, level, eci)
    })
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

    /// Capacity at level M for content with any non-ASCII byte. The 12 ECI bits take the 4 spare bits
    /// of the byte-mode segment plus one whole byte, so it is one byte below [`BYTE_CAPACITY_LEVEL_M`].
    const UTF8_CAPACITY_LEVEL_M: usize = BYTE_CAPACITY_LEVEL_M - 1;

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
            // Non-ASCII content holds one byte less than ASCII byte mode (the ECI header).
            let utf8_capacity = byte - 1;
            for length in [utf8_capacity - 1, utf8_capacity, utf8_capacity + 1] {
                let content = format!("{}{}", "é".repeat(length / 2), "a".repeat(length % 2));
                assert_eq!(content.len(), length);
                let fits = fits_agreeing_with_generation(&content, level)?;
                assert_eq!(fits, length <= utf8_capacity, "{length} non-ASCII bytes at {level:?}");
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
    fn content_fits_x12345_boundary_is_one_whole_byte_segment() -> TestResult {
        let text = "x12345".repeat(BYTE_CAPACITY_LEVEL_M);
        let at_limit = text.get(..BYTE_CAPACITY_LEVEL_M).ok_or("pattern too short")?;
        let over = text.get(..=BYTE_CAPACITY_LEVEL_M).ok_or("pattern too short")?;
        assert!(
            whole_byte_segment_is_strictly_shortest(at_limit.as_bytes(), 40),
            "the limit must come from one whole byte segment"
        );
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

    /// Packs `data` as one byte-mode segment at `version`, or `None` if it does not fit. Test-only oracle.
    fn single_byte_bits(data: &[u8], version: qrcode::Version, level: qrcode::EcLevel) -> Option<qrcode::bits::Bits> {
        let mut bits = qrcode::bits::Bits::new(version);
        (bits.push_byte_data(data).is_ok() && bits.push_terminator(level).is_ok()).then_some(bits)
    }

    /// Smallest version a single byte segment needs for `data`, probed linearly. Test-only oracle.
    fn single_byte_version(data: &[u8], level: qrcode::EcLevel) -> Option<i16> {
        (1..=40).find(|&number| single_byte_bits(data, qrcode::Version::Normal(number), level).is_some())
    }

    /// Version number of `bits`, or 0 for a non-normal version (never produced here).
    fn version_number(bits: &qrcode::bits::Bits) -> i16 {
        match bits.version() {
            qrcode::Version::Normal(number) => number,
            qrcode::Version::Micro(_) => 0,
        }
    }

    /// Whether one whole byte segment is strictly shorter than every optimised candidate at the widths of `version`.
    fn whole_byte_segment_is_strictly_shortest(data: &[u8], version: i16) -> bool {
        let version = qrcode::Version::Normal(version);
        let candidates = candidate_segmentations(&kanji_free_segments(data), data.len());
        let Some((whole, optimised)) = candidates.split_last() else {
            return false;
        };
        let whole_len = qrcode::optimize::total_encoded_len(whole, version);
        optimised
            .iter()
            .all(|candidate| whole_len < qrcode::optimize::total_encoded_len(candidate, version))
    }

    /// Linear search for [`segmented_bits`]: the first version in 1..=40 at which the shortest candidate at that
    /// version's own widths packs. It reuses the production candidates, so it checks the class and capacity
    /// search only, not the choice of candidates.
    fn linear_segmented_bits(data: &[u8], level: qrcode::EcLevel, eci: Option<u32>) -> Option<qrcode::bits::Bits> {
        let candidates = candidate_segmentations(&kanji_free_segments(data), data.len());
        (1..=40).find_map(|number| {
            let version = qrcode::Version::Normal(number);
            let chosen = candidates
                .iter()
                .min_by_key(|candidate| qrcode::optimize::total_encoded_len(candidate, version))?;
            bits_at(data, chosen, version, level, eci)
        })
    }

    const ALL_LEVELS: [qrcode::EcLevel; 4] = [
        qrcode::EcLevel::L,
        qrcode::EcLevel::M,
        qrcode::EcLevel::Q,
        qrcode::EcLevel::H,
    ];

    #[test]
    fn utf8_eci_header_is_twelve_bits() {
        assert_eq!(eci_header_bits(ECI_UTF8), Some(12));
    }

    #[test]
    fn whole_byte_segment_picks_smallest_version() -> TestResult {
        let text = "x12345".repeat(400);
        let data = text.as_bytes().get(..1800).ok_or("short")?;
        let level = qrcode::EcLevel::M;
        assert!(whole_byte_segment_is_strictly_shortest(data, 35));
        assert_eq!(encode_bits(data, level)?.version(), qrcode::Version::Normal(35));
        assert!(single_byte_bits(data, qrcode::Version::Normal(34), level).is_none());
        Ok(())
    }

    #[test]
    fn ascii_search_equals_the_linear_search() -> TestResult {
        let text = "x12345".repeat(500);
        for level in ALL_LEVELS {
            for length in 1..=3000 {
                let data = text.as_bytes().get(..length).ok_or("pattern too short")?;
                let searched = segmented_bits(data, level, None);
                let linear = linear_segmented_bits(data, level, None);
                assert_eq!(
                    searched.as_ref().map(version_number),
                    linear.as_ref().map(version_number),
                    "version differs at {length} bytes, level {level:?}"
                );
                assert_eq!(
                    searched.map(qrcode::bits::Bits::into_bytes),
                    linear.map(qrcode::bits::Bits::into_bytes),
                    "bits differ at {length} bytes, level {level:?}"
                );
            }
        }
        Ok(())
    }

    /// Deterministic ASCII samples: random runs of digits, upper-case, lower-case and mixed text, plus prefixes
    /// of repeated realistic units (a URL, `x12345`, a sentence with phone numbers).
    fn mixed_ascii_samples() -> Vec<String> {
        let mut samples = Vec::new();
        let mut state = 0x2545_F491_u32;
        for _ in 0..40 {
            let mut text = String::new();
            while text.len() < 1500 {
                state = state.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
                let run = usize::try_from((state >> 16) % 14).unwrap_or(1) + 1;
                let alphabet = match (state >> 8) % 4 {
                    0 => "0123456789",
                    1 => "ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:",
                    2 => "abcdefghijklmnopqrstuvwxyz",
                    _ => "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ!?@",
                };
                text.extend(
                    alphabet
                        .chars()
                        .cycle()
                        .skip(usize::try_from(state % 7).unwrap_or(0))
                        .take(run),
                );
            }
            samples.push(text);
        }
        for unit in [
            "https://example.com/a/b?id=12345&ref=ABC",
            "x12345",
            "Call me at 5551234 or 5559876 tomorrow. ",
        ] {
            samples.push(unit.repeat(3000 / unit.len() + 1));
        }
        samples
    }

    #[test]
    fn ascii_version_is_the_minimum_of_all_candidates_and_never_above_the_old_choice() -> TestResult {
        let (mut compared, mut improved, mut worse) = (0_usize, 0_usize, 0_usize);
        for text in mixed_ascii_samples() {
            for level in ALL_LEVELS {
                for length in (40..=text.len().min(3000)).step_by(41) {
                    let data = text.as_bytes().get(..length).ok_or("prefix")?;
                    let candidates = candidate_segmentations(&kanji_free_segments(data), data.len());
                    let expected = (1..=40).find(|&number| {
                        let version = qrcode::Version::Normal(number);
                        candidates
                            .iter()
                            .any(|candidate| bits_at(data, candidate, version, level, None).is_some())
                    });
                    let chosen = encode_bits(data, level).ok().as_ref().map(version_number);
                    assert_eq!(chosen, expected, "version differs at {length} bytes, level {level:?}");
                    if let (Some(chosen), Some(single)) = (chosen, single_byte_version(data, level)) {
                        assert!(chosen <= single, "larger than a single byte segment at {length} bytes");
                    }
                    let old = qrcode::bits::encode_auto(data, level).ok().as_ref().map(version_number);
                    if let (Some(chosen), Some(old)) = (chosen, old) {
                        worse += usize::from(chosen > old);
                        improved += usize::from(chosen < old);
                    }
                    compared += 1;
                }
            }
        }
        println!("ascii comparisons: {compared}, smaller than encode_auto: {improved}, larger: {worse}");
        assert!(compared > 1_000);
        assert!(improved > 0, "the samples must exercise the improvement");
        assert_eq!(worse, 0, "never larger than the old choice");
        Ok(())
    }

    #[test]
    fn ascii_text_across_a_width_class_edge_keeps_the_smaller_class_optimum() -> TestResult {
        // 219 bytes at level M: the version 10-26 optimisation is 1322 bits and fits version 9's 1456, while
        // the version 1-9 optimisation (1470 bits) and one byte segment (1764 bits) do not.
        let mut text = String::new();
        for letter in 'a'..='q' {
            text.push(letter);
            text.push_str("ABCDEF");
        }
        text.push_str(&"7".repeat(100));
        assert_eq!(text.len(), 219);
        let level = qrcode::EcLevel::M;
        assert_eq!(
            qrcode::bits::encode_auto(text.as_bytes(), level)?.version(),
            qrcode::Version::Normal(9),
            "the old choice"
        );
        assert_eq!(
            encode_bits(text.as_bytes(), level)?.version(),
            qrcode::Version::Normal(9)
        );
        let png = generate_png(&text, 512)?;
        assert_eq!(crate::decoder::decode(&png)?.content, text);
        Ok(())
    }

    #[test]
    fn ascii_text_that_fits_one_byte_segment_gets_the_smaller_version() -> TestResult {
        // 26 bytes: `qrcode`'s pairwise-merged segments need version 3 at level M, but one byte
        // segment (26 bytes is version 2's byte capacity at level M) fits version 2.
        let text = "abcdefghijklmn6789ABCDabcd";
        let data = text.as_bytes();
        let level = qrcode::EcLevel::M;
        assert_eq!(
            qrcode::bits::encode_auto(data, level)?.version(),
            qrcode::Version::Normal(3),
            "the old choice"
        );
        assert_eq!(encode_bits(data, level)?.version(), qrcode::Version::Normal(2));
        let png = generate_png(text, 512)?;
        assert_eq!(crate::decoder::decode(&png)?.content, text);
        Ok(())
    }

    #[test]
    fn utf8_segments_never_contain_kanji() {
        // CJK text whose UTF-8 byte pairs fall in the Shift-JIS Kanji ranges, and interleavings with ASCII.
        let samples = [
            "こんにちは世界".repeat(300),
            "日本語のテキスト、東京都渋谷区".repeat(100),
            "你好，世界。欢迎使用二维码生成器".repeat(100),
            "東1京A".repeat(500),
            "東12京ABC都-".repeat(300),
        ];
        for text in &samples {
            let data = text.as_bytes();
            let segments = kanji_free_segments(data);
            assert!(
                segments
                    .iter()
                    .all(|segment| segment.mode != qrcode::types::Mode::Kanji),
                "parser output contains Kanji for {text:.12}"
            );
            for version in [9, 26, 40] {
                let version = qrcode::Version::Normal(version);
                let optimized = qrcode::optimize::Optimizer::new(segments.iter().copied(), version).collect::<Vec<_>>();
                assert!(
                    optimized
                        .iter()
                        .all(|segment| segment.mode != qrcode::types::Mode::Kanji),
                    "optimizer output contains Kanji at {version:?} for {text:.12}"
                );
            }
            let candidates = candidate_segmentations(&segments, data.len());
            assert!(
                candidates
                    .iter()
                    .flatten()
                    .all(|segment| segment.mode != qrcode::types::Mode::Kanji),
                "candidate segmentations contain Kanji for {text:.12}"
            );
        }
        // The plain `qrcode` parser does produce Kanji for the same text, so the check above is meaningful.
        let plain = qrcode::optimize::Parser::new("こんにちは世界".as_bytes()).collect::<Vec<_>>();
        assert!(plain.iter().any(|segment| segment.mode == qrcode::types::Mode::Kanji));
    }

    #[test]
    fn utf8_search_equals_the_linear_search() -> TestResult {
        let pattern = "é12345日AB-".repeat(800);
        let text = pattern.get(..MAX_ENCODABLE_BYTES).map_or(pattern.as_str(), |cut| cut);
        let mut comparisons = 0_usize;
        for level in [
            qrcode::EcLevel::L,
            qrcode::EcLevel::M,
            qrcode::EcLevel::Q,
            qrcode::EcLevel::H,
        ] {
            for (end, _) in text.char_indices().skip(1) {
                let data = text.as_bytes().get(..end).ok_or("prefix")?;
                let class_search = segmented_bits(data, level, Some(ECI_UTF8));
                let linear = linear_segmented_bits(data, level, Some(ECI_UTF8));
                assert_eq!(
                    class_search.as_ref().map(qrcode::bits::Bits::version),
                    linear.as_ref().map(qrcode::bits::Bits::version),
                    "version differs at {end} bytes, level {level:?}"
                );
                assert_eq!(
                    class_search.map(qrcode::bits::Bits::into_bytes),
                    linear.map(qrcode::bits::Bits::into_bytes),
                    "bits differ at {end} bytes, level {level:?}"
                );
                comparisons += 1;
            }
        }
        println!("utf8 oracle comparisons: {comparisons}");
        assert!(comparisons > 10_000);
        Ok(())
    }

    #[test]
    fn utf8_encoding_is_never_larger_than_a_single_byte_segment() -> TestResult {
        // "é12345" at level Q: 10 bytes fit version 1 as one byte segment.
        let bits = encode_bits("é12345".as_bytes(), qrcode::EcLevel::Q)?;
        assert_eq!(bits.version(), qrcode::Version::Normal(1));
        Ok(())
    }
}
