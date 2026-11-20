//! Capacity decision and segmentation for QR data.
//!
//! [`encode_bits`] is THE capacity decision: generation and `content_fits` both call it, so "the
//! bits were built" is exactly "generation will not return `ContentTooLong`".

use std::ops::RangeInclusive;

use qrcode::bits::Bits;
use qrcode::optimize::{total_encoded_len, Optimizer, Parser, Segment};
use qrcode::types::Mode;
use qrcode::{EcLevel, Version};

/// Most bytes any QR code can hold: numeric mode at version 40, level L.
///
/// Longer input cannot fit at any level, so [`encode_bits`] rejects it before parsing.
const MAX_ENCODABLE_BYTES: usize = 7089;

/// ECI designator for UTF-8 (ISO/IEC 18004, Table 4 / AIM ECI registry).
const ECI_UTF8: u32 = 26;

/// Normal versions grouped by character-count width. Within a class every segment header has the
/// same size, so the encoded length of a segmentation does not change between its versions.
const CLASSES: [RangeInclusive<i16>; 3] = [1..=9, 10..=26, 27..=40];

/// Builds the QR data bits for `data` at `level`, or `None` if they do not fit version 40.
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
pub(super) fn encode_bits(data: &[u8], level: EcLevel) -> Option<Bits> {
    if data.len() > MAX_ENCODABLE_BYTES {
        return None;
    }
    segmented_bits(data, level, (!data.is_ascii()).then_some(ECI_UTF8))
}

/// Splits `data` into segments that are never Kanji, before any optimisation.
///
/// ASCII runs are segmented by the `qrcode` parser (it cannot emit Kanji for bytes
/// below 0x80); each run of bytes of 0x80 or more becomes one byte segment.
fn kanji_free_segments(data: &[u8]) -> Vec<Segment> {
    let mut segments = Vec::new();
    let mut offset = 0;
    for run in data.chunk_by(|a, b| a.is_ascii() == b.is_ascii()) {
        let end = offset + run.len();
        if run.first().is_some_and(u8::is_ascii) {
            segments.extend(Parser::new(run).map(|segment| Segment {
                mode: segment.mode,
                begin: segment.begin + offset,
                end: segment.end + offset,
            }));
        } else {
            segments.push(Segment {
                mode: Mode::Byte,
                begin: offset,
                end,
            });
        }
        offset = end;
    }
    segments
}

/// Candidate segmentations of `data_len` bytes: `segments` optimised for each class in
/// [`CLASSES`], then one whole byte segment.
///
/// The optimizer merges neighbours only pairwise, and every extra segment header costs bits, so
/// one whole byte segment is sometimes smaller, and a list optimised for one class is sometimes
/// shorter than another class's own optimum at that class's widths. Offering every candidate to
/// every class never loses to any single one of them.
fn candidate_segmentations(segments: &[Segment], data_len: usize) -> Vec<Vec<Segment>> {
    let mut candidates = Vec::from(CLASSES.map(|versions| {
        Optimizer::new(segments.iter().copied(), Version::Normal(*versions.end())).collect::<Vec<_>>()
    }));
    candidates.push(vec![Segment {
        mode: Mode::Byte,
        begin: 0,
        end: data_len,
    }]);
    candidates
}

/// Packs `segments` of `data` at `version`, behind an ECI `eci` header when set, or `None` if they do not fit.
///
/// The fit is decided arithmetically before anything is packed: the ECI header plus the encoded length of
/// `segments` at `version` must not exceed the data capacity at `level`. `None` can only mean capacity: the
/// segments are never Kanji and the designator is valid, so for `Version::Normal(1..=40)` the only error the
/// `qrcode` push functions return is `DataTooLong`. The check rules out overflowing capacity, and a length
/// field cannot overflow before capacity does, so the pushes after it succeed.
fn bits_at(data: &[u8], segments: &[Segment], version: Version, level: EcLevel, eci: Option<u32>) -> Option<Bits> {
    let mut bits = Bits::new(version);
    if let Some(designator) = eci {
        bits.push_eci_designator(designator).ok()?;
    }
    if bits.len() + total_encoded_len(segments, version) > bits.max_len(level).ok()? {
        return None;
    }
    bits.push_segments(data, segments.iter().copied()).ok()?;
    bits.push_terminator(level).ok()?;
    Some(bits)
}

/// Segments `data` without Kanji and packs it at the smallest fitting version, behind an ECI `eci` header
/// when set. `None` means the data does not fit version 40 at `level`.
///
/// Segments come from [`kanji_free_segments`]. For each class in [`CLASSES`] in turn, the shortest of
/// [`candidate_segmentations`] at that class's widths wins (ties keep the earlier, optimised list), and
/// the first version of the class that [`bits_at`] can pack it at is the answer. The class widths are
/// fixed within a class, so the chosen list is the same for all its versions.
fn segmented_bits(data: &[u8], level: EcLevel, eci: Option<u32>) -> Option<Bits> {
    let candidates = candidate_segmentations(&kanji_free_segments(data), data.len());
    CLASSES.into_iter().find_map(|versions| {
        let top = Version::Normal(*versions.end());
        let cheapest = candidates
            .iter()
            .min_by_key(|candidate| total_encoded_len(candidate, top))?;
        versions
            .map(Version::Normal)
            .find_map(|version| bits_at(data, cheapest, version, level, eci))
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    type TestResult<T = ()> = Result<T, Box<dyn std::error::Error>>;

    const ALL_LEVELS: [EcLevel; 4] = [EcLevel::L, EcLevel::M, EcLevel::Q, EcLevel::H];

    /// Version number of `bits`, or 0 for a non-normal version (never produced here).
    fn version_number(bits: &Bits) -> i16 {
        match bits.version() {
            Version::Normal(number) => number,
            Version::Micro(_) => 0,
        }
    }

    /// Packs `data` as one byte-mode segment at `version`, or `None` if it does not fit. Test-only oracle.
    fn single_byte_bits(data: &[u8], version: Version, level: EcLevel) -> Option<Bits> {
        let mut bits = Bits::new(version);
        (bits.push_byte_data(data).is_ok() && bits.push_terminator(level).is_ok()).then_some(bits)
    }

    /// Smallest version a single byte segment needs for `data`, probed linearly. Test-only oracle.
    fn single_byte_version(data: &[u8], level: EcLevel) -> Option<i16> {
        (1..=40).find(|&number| single_byte_bits(data, Version::Normal(number), level).is_some())
    }

    /// Whether one whole byte segment is strictly shorter than every optimised candidate at the widths of `version`.
    fn whole_byte_segment_is_strictly_shortest(data: &[u8], version: i16) -> bool {
        let version = Version::Normal(version);
        let candidates = candidate_segmentations(&kanji_free_segments(data), data.len());
        let Some((whole, optimised)) = candidates.split_last() else {
            return false;
        };
        let whole_len = total_encoded_len(whole, version);
        optimised
            .iter()
            .all(|candidate| whole_len < total_encoded_len(candidate, version))
    }

    /// Deterministic samples: random runs of digits, upper-case, lower-case and mixed ASCII text, plus prefixes
    /// of repeated realistic units (a URL, `x12345`, a sentence with phone numbers) and of non-ASCII units.
    fn mixed_samples() -> Vec<String> {
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
            "é12345日AB-",
            "東1京A",
            "é1",
        ] {
            samples.push(unit.repeat(3000 / unit.len() + 1));
        }
        samples
    }

    #[test]
    fn non_ascii_content_starts_with_the_utf8_eci_header() -> TestResult {
        // ECI mode indicator 0111, designator 26 = 0001_1010, then byte mode 0100.
        for text in ["こんにちは世界", "Café 東京 2026", "😀"] {
            let bytes = encode_bits(text.as_bytes(), EcLevel::M).ok_or("must fit")?.into_bytes();
            assert_eq!(
                bytes.get(..2),
                Some(&[0x71, 0xA4][..]),
                "{text:?} must open ECI 26 + byte mode"
            );
        }
        let ascii = encode_bits(b"hello", EcLevel::M).ok_or("must fit")?.into_bytes();
        assert_ne!(
            ascii.first().map(|byte| byte >> 4),
            Some(0b0111),
            "ASCII must not carry an ECI header"
        );
        Ok(())
    }

    #[test]
    fn input_over_the_encodable_cap_is_rejected_at_every_level() {
        for level in ALL_LEVELS {
            assert!(encode_bits(&b"a".repeat(1_000_000), level).is_none());
            assert!(encode_bits(&b"7".repeat(MAX_ENCODABLE_BYTES + 1), level).is_none());
        }
    }

    #[test]
    fn whole_byte_segment_picks_smallest_version() -> TestResult {
        let text = "x12345".repeat(400);
        let data = text.as_bytes().get(..1800).ok_or("short")?;
        let level = EcLevel::M;
        assert!(whole_byte_segment_is_strictly_shortest(data, 35));
        assert_eq!(
            encode_bits(data, level).ok_or("must fit")?.version(),
            Version::Normal(35)
        );
        assert!(single_byte_bits(data, Version::Normal(34), level).is_none());
        Ok(())
    }

    #[test]
    fn x12345_capacity_comes_from_one_whole_byte_segment() -> TestResult {
        // The capacity boundary itself is asserted in tests/capacity.rs; this checks where it comes from.
        let text = "x12345".repeat(400);
        let at_limit = text.get(..2331).ok_or("pattern too short")?;
        assert!(whole_byte_segment_is_strictly_shortest(at_limit.as_bytes(), 40));
        Ok(())
    }

    #[test]
    fn version_is_the_minimum_over_all_candidates_and_ascii_is_never_above_the_old_choice() -> TestResult {
        let (mut compared, mut improved, mut worse) = (0_usize, 0_usize, 0_usize);
        for text in mixed_samples() {
            for level in ALL_LEVELS {
                for eci in [None, Some(ECI_UTF8)] {
                    for length in (40..=text.len().min(3000)).step_by(41) {
                        let data = text.as_bytes().get(..length).ok_or("prefix")?;
                        let candidates = candidate_segmentations(&kanji_free_segments(data), data.len());
                        // Trial-packs every candidate at every version: the oracle for the cheap-reject arithmetic.
                        let expected = (1..=40).find(|&number| {
                            let version = Version::Normal(number);
                            candidates
                                .iter()
                                .any(|candidate| trial_pack(data, candidate, version, level, eci))
                        });
                        let chosen = segmented_bits(data, level, eci);
                        let chosen = chosen.as_ref().map(version_number);
                        assert_eq!(
                            chosen, expected,
                            "version differs at {length} bytes, level {level:?}, eci {eci:?}"
                        );
                        if eci.is_some() || !data.is_ascii() {
                            continue;
                        }
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
        }
        assert!(compared > 1_000, "compared {compared}");
        assert!(improved > 0, "the samples must exercise the improvement");
        assert_eq!(worse, 0, "never larger than the old choice");
        Ok(())
    }

    /// Whether `segments` really pack at `version`: every `qrcode` push is attempted and its error observed.
    fn trial_pack(data: &[u8], segments: &[Segment], version: Version, level: EcLevel, eci: Option<u32>) -> bool {
        let mut bits = Bits::new(version);
        let eci_ok = eci.is_none_or(|designator| bits.push_eci_designator(designator).is_ok());
        eci_ok && bits.push_segments(data, segments.iter().copied()).is_ok() && bits.push_terminator(level).is_ok()
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
        let level = EcLevel::M;
        assert_eq!(
            qrcode::bits::encode_auto(text.as_bytes(), level)?.version(),
            Version::Normal(9),
            "the old choice"
        );
        assert_eq!(
            encode_bits(text.as_bytes(), level).ok_or("must fit")?.version(),
            Version::Normal(9)
        );
        let png = crate::encoder::generate_png(&text, 512)?;
        assert_eq!(crate::decoder::decode(&png)?.content, text);
        Ok(())
    }

    #[test]
    fn ascii_text_that_fits_one_byte_segment_gets_the_smaller_version() -> TestResult {
        // 26 bytes: `qrcode`'s pairwise-merged segments need version 3 at level M, but one byte
        // segment (26 bytes is version 2's byte capacity at level M) fits version 2.
        let text = "abcdefghijklmn6789ABCDabcd";
        let data = text.as_bytes();
        let level = EcLevel::M;
        assert_eq!(
            qrcode::bits::encode_auto(data, level)?.version(),
            Version::Normal(3),
            "the old choice"
        );
        assert_eq!(
            encode_bits(data, level).ok_or("must fit")?.version(),
            Version::Normal(2)
        );
        let png = crate::encoder::generate_png(text, 512)?;
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
                segments.iter().all(|segment| segment.mode != Mode::Kanji),
                "parser output contains Kanji for {text:.12}"
            );
            for version in [9, 26, 40] {
                let version = Version::Normal(version);
                let optimized = Optimizer::new(segments.iter().copied(), version).collect::<Vec<_>>();
                assert!(
                    optimized.iter().all(|segment| segment.mode != Mode::Kanji),
                    "optimizer output contains Kanji at {version:?} for {text:.12}"
                );
            }
            let candidates = candidate_segmentations(&segments, data.len());
            assert!(
                candidates.iter().flatten().all(|segment| segment.mode != Mode::Kanji),
                "candidate segmentations contain Kanji for {text:.12}"
            );
        }
        // The plain `qrcode` parser does produce Kanji for the same text, so the check above is meaningful.
        let plain = Parser::new("こんにちは世界".as_bytes()).collect::<Vec<_>>();
        assert!(plain.iter().any(|segment| segment.mode == Mode::Kanji));
    }

    #[test]
    fn short_utf8_with_digits_fits_version_one() -> TestResult {
        // "é12345" is 7 bytes; at level Q they fit version 1 as one byte segment behind the ECI header.
        let bits = encode_bits("é12345".as_bytes(), EcLevel::Q).ok_or("must fit")?;
        assert_eq!(bits.version(), Version::Normal(1));
        Ok(())
    }
}
