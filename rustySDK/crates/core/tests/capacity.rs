//! Capacity contract of the public API: how much content fits, per mode and error-correction level.
//!
//! `content_fits` and generation must agree at every boundary, and what fits must scan back. White-box checks of
//! the segmentation itself live beside the code in `src/encoder/segment.rs`.

use rusty_qr_core::encoder::{content_fits, generate_png, generate_with_config};
use rusty_qr_core::{decoder, QrConfig, QrError, QrErrorCorrection};

type TestResult<T = ()> = Result<T, Box<dyn std::error::Error>>;

/// Size used where only the capacity verdict matters; 256 px holds a version 40 code at one pixel per module.
const SIZE: u32 = 256;

/// Size used where the result is scanned back; large enough for several pixels per module at version 40.
const SCAN_SIZE: u32 = 1024;

/// Upper bound on characters a scanning test tries before giving up.
const SCAN_LIMIT: usize = 20_000;

/// Byte-mode capacity of version 40 at level M, the level `content_fits` and `generate_png` use.
const BYTE_CAPACITY_M: usize = 2331;

/// Capacity at level M for content with any non-ASCII byte: the 12 ECI bits take the 4 spare bits
/// of the byte-mode segment plus one whole byte.
const UTF8_CAPACITY_M: usize = BYTE_CAPACITY_M - 1;

/// ISO 18004 version 40 capacities as `(level, numeric, alphanumeric, byte)`.
const V40_CAPACITY: [(QrErrorCorrection, usize, usize, usize); 4] = [
    (QrErrorCorrection::Low, 7089, 4296, 2953),
    (QrErrorCorrection::Medium, 5596, 3391, BYTE_CAPACITY_M),
    (QrErrorCorrection::Quartile, 3993, 2420, 1663),
    (QrErrorCorrection::High, 3057, 1852, 1273),
];

/// Builds text of exactly `length` bytes by cycling the characters of `unit`, padding with `a` when the next
/// character would overshoot (only possible for multi-byte units).
fn text_of_len(unit: &str, length: usize) -> String {
    let mut text = String::with_capacity(length);
    for next in unit.chars().cycle() {
        if text.len() + next.len_utf8() > length {
            break;
        }
        text.push(next);
    }
    while text.len() < length {
        text.push('a');
    }
    text
}

/// Every `(unit, level, capacity)` row: the content of `capacity` bytes fits at `level`, one byte more does not.
fn capacity_rows() -> Vec<(&'static str, QrErrorCorrection, usize)> {
    V40_CAPACITY
        .into_iter()
        .flat_map(|(level, numeric, alphanumeric, byte)| {
            [
                ("7", level, numeric),
                ("A", level, alphanumeric),
                ("a", level, byte),
                // Digits inside byte text: one whole byte segment beats the optimiser's split.
                ("x12345", level, byte),
                // Non-ASCII: the ECI header costs one byte of capacity.
                ("é", level, byte - 1),
            ]
        })
        .chain([
            // Digit runs inside prose: the optimiser splits them, one whole byte segment is shorter.
            (
                "Call me at 5551234 or 5559876 tomorrow. ",
                QrErrorCorrection::Medium,
                BYTE_CAPACITY_M,
            ),
            (
                "order 48213 shipped, tracking 9400111 ok; ",
                QrErrorCorrection::Medium,
                BYTE_CAPACITY_M,
            ),
        ])
        .collect()
}

/// Whether real generation accepts `content`; any error other than `ContentTooLong` is propagated.
fn generation_accepts(content: &str, level: QrErrorCorrection) -> TestResult<bool> {
    let config = QrConfig {
        size: SIZE,
        error_correction: level,
    };
    match generate_with_config(content, config) {
        Ok(_) => Ok(true),
        Err(QrError::ContentTooLong { .. }) => Ok(false),
        Err(other) => Err(other.into()),
    }
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
fn every_capacity_boundary_holds_for_generation() -> TestResult {
    for (unit, level, capacity) in capacity_rows() {
        assert!(
            generation_accepts(&text_of_len(unit, capacity), level)?,
            "{capacity} bytes of {unit:?} must generate at {level:?}"
        );
        assert!(
            !generation_accepts(&text_of_len(unit, capacity + 1), level)?,
            "{} bytes of {unit:?} must be ContentTooLong at {level:?}",
            capacity + 1
        );
    }
    Ok(())
}

#[test]
fn content_fits_agrees_with_the_boundary_table_at_level_m() -> TestResult {
    for (unit, _, capacity) in capacity_rows()
        .into_iter()
        .filter(|(_, level, _)| *level == QrErrorCorrection::Medium)
    {
        assert!(
            content_fits(&text_of_len(unit, capacity))?,
            "{capacity} bytes of {unit:?} must fit"
        );
        assert!(
            !content_fits(&text_of_len(unit, capacity + 1))?,
            "{} bytes of {unit:?} must not fit",
            capacity + 1
        );
    }
    Ok(())
}

#[test]
fn utf8_capacity_is_measured_in_bytes_not_characters() -> TestResult {
    // One more two-byte character than fits: fewer characters than the byte capacity, but more bytes.
    let few_chars = "é".repeat(UTF8_CAPACITY_M / 2 + 1);
    assert!(few_chars.chars().count() <= UTF8_CAPACITY_M);
    assert!(few_chars.len() > UTF8_CAPACITY_M);
    assert!(!content_fits(&few_chars)?);
    assert!(matches!(
        generate_png(&few_chars, SIZE),
        Err(QrError::ContentTooLong { .. })
    ));
    Ok(())
}

#[test]
fn digits_exceed_the_byte_capacity() -> TestResult {
    let digits = "7".repeat(BYTE_CAPACITY_M + 1);
    assert!(content_fits(&digits)?, "numeric mode packs more characters");
    assert!(generate_png(&digits, SIZE).is_ok());
    Ok(())
}

#[test]
fn oversized_paste_is_rejected() -> TestResult {
    let paste = "a".repeat(100_000);
    assert!(!content_fits(&paste)?);
    let result = generate_png(&paste, SIZE);
    assert!(
        matches!(result, Err(QrError::ContentTooLong { .. })),
        "got {:?}",
        result.map(|png| png.len())
    );
    Ok(())
}

#[test]
fn digit_heavy_text_fits_beyond_the_byte_capacity_and_scans() -> TestResult {
    let unit = format!("id={};", "0123456789".repeat(4));
    let (fitting, overflowing) = scan_to_overflow(&unit, BYTE_CAPACITY_M)?;
    assert!(
        fitting.len() > BYTE_CAPACITY_M,
        "digit-heavy text must exceed the byte capacity, got {}",
        fitting.len()
    );
    assert!(generation_accepts(&fitting, QrErrorCorrection::Medium)?);
    assert!(!generation_accepts(&overflowing, QrErrorCorrection::Medium)?);
    let png = generate_png(&fitting, SCAN_SIZE)?;
    assert_eq!(decoder::decode(&png)?.content, fitting);
    Ok(())
}

#[test]
fn x12345_at_the_limit_scans() -> TestResult {
    let at_limit = text_of_len("x12345", BYTE_CAPACITY_M);
    let png = generate_png(&at_limit, SCAN_SIZE)?;
    assert_eq!(decoder::decode(&png)?.content, at_limit);
    Ok(())
}

#[test]
fn cjk_and_emoji_fill_the_utf8_capacity() -> TestResult {
    for unit in ["世界", "😀"] {
        let (fitting, overflowing) = scan_to_overflow(unit, UTF8_CAPACITY_M)?;
        assert!(
            fitting.len() > UTF8_CAPACITY_M - unit.len() && fitting.len() <= UTF8_CAPACITY_M,
            "{unit:?} must fill the UTF-8 capacity, got {} bytes",
            fitting.len()
        );
        assert!(generation_accepts(&fitting, QrErrorCorrection::Medium)?);
        assert!(!generation_accepts(&overflowing, QrErrorCorrection::Medium)?);
    }
    Ok(())
}

#[test]
fn digit_run_beside_non_ascii_keeps_numeric_packing_and_scans() -> TestResult {
    let text = format!("é{}", "7".repeat(3000));
    assert!(
        content_fits(&text)?,
        "the digit run must stay numeric next to non-ASCII"
    );
    let png = generate_png(&text, SCAN_SIZE)?;
    assert_eq!(decoder::decode(&png)?.content, text);
    Ok(())
}

#[test]
fn non_ascii_text_scans_back_as_utf8() -> TestResult {
    for text in [
        "こんにちは世界",
        "Café 東京 2026",
        "世界",
        "한국어 テスト",
        "😀🎉 ok",
        "ÀÉÎ",
    ] {
        let png = generate_png(text, 512)?;
        assert_eq!(decoder::decode(&png)?.content, text);
    }
    Ok(())
}
