//! Performance benchmarks for rusty-qr-core.
//!
//! Validates PRD performance targets:
//! - QR generation (256px): < 10ms
//! - QR generation (1024px): < 50ms
//! - QR scanning (256px image): < 20ms
//! - QR scanning (1080p image): < 100ms

use std::error::Error;

use criterion::Criterion;
use rusty_qr_core::decoder;
use rusty_qr_core::encoder;
use rusty_qr_core::types::{QrConfig, QrErrorCorrection};

type BenchResult = Result<(), Box<dyn Error>>;

const URL: &str = "https://example.com";

/// Benchmarks `generate_png` at `size`. The call is validated once with `?`
/// before timing; the timed closure returns the `Result`, which criterion
/// passes through `black_box` so the work is not optimised away.
fn bench_generate(c: &mut Criterion, name: &str, size: u32) -> BenchResult {
    encoder::generate_png(URL, size)?;
    c.bench_function(name, |b| {
        b.iter(|| encoder::generate_png(URL, size));
    });
    Ok(())
}

fn bench_decode(c: &mut Criterion, name: &str, size: u32) -> BenchResult {
    let png = encoder::generate_png(URL, size)?;
    let _ = decoder::decode(&png)?;
    c.bench_function(name, |b| {
        b.iter(|| decoder::decode(&png));
    });
    Ok(())
}

fn bench_decode_from_raw_256(c: &mut Criterion) -> BenchResult {
    let png = encoder::generate_png(URL, 256)?;
    let img = image::load_from_memory(&png)?;
    let luma = img.to_luma8();
    let (w, h) = (luma.width(), luma.height());
    let pixels = luma.into_raw();
    let _ = decoder::decode_from_raw(&pixels, w, h)?;
    c.bench_function("decode_from_raw_256px", |b| {
        b.iter(|| decoder::decode_from_raw(&pixels, w, h));
    });
    Ok(())
}

fn bench_generate_with_config_all_ecl(c: &mut Criterion) -> BenchResult {
    let levels = [
        ("Low", QrErrorCorrection::Low),
        ("Medium", QrErrorCorrection::Medium),
        ("Quartile", QrErrorCorrection::Quartile),
        ("High", QrErrorCorrection::High),
    ];
    for (name, ecl) in levels {
        let make_config = || QrConfig {
            size: 256,
            error_correction: ecl,
        };
        encoder::generate_with_config(URL, make_config())?;
        c.bench_function(&format!("generate_with_config_{name}_256px"), |b| {
            b.iter(|| encoder::generate_with_config(URL, make_config()));
        });
    }
    Ok(())
}

fn bench_round_trip_256(c: &mut Criterion) -> BenchResult {
    let _ = decoder::decode(&encoder::generate_png(URL, 256)?)?;
    c.bench_function("round_trip_256px", |b| {
        b.iter(|| encoder::generate_png(URL, 256).and_then(|png| decoder::decode(&png)));
    });
    Ok(())
}

/// Benchmarks `content_fits` on `content`; the verdict is checked once before timing.
fn bench_content_fits(c: &mut Criterion, name: &str, content: &str, expected: bool) -> BenchResult {
    if encoder::content_fits(content)? != expected {
        return Err(format!("{name}: unexpected verdict").into());
    }
    c.bench_function(name, |b| {
        b.iter(|| encoder::content_fits(content));
    });
    Ok(())
}

/// Custom entry point so setup failures propagate via `?` instead of panicking.
fn main() -> BenchResult {
    let mut c = Criterion::default().configure_from_args();
    bench_generate(&mut c, "generate_png_256px", 256)?;
    bench_generate(&mut c, "generate_png_1024px", 1024)?;
    bench_generate(&mut c, "generate_png_4096px", 4096)?;
    bench_decode(&mut c, "decode_png_256px", 256)?;
    bench_decode(&mut c, "decode_png_1080px", 1080)?;
    bench_decode_from_raw_256(&mut c)?;
    bench_generate_with_config_all_ecl(&mut c)?;
    bench_round_trip_256(&mut c)?;
    bench_content_fits(&mut c, "content_fits_2331B_byte", &"a".repeat(2331), true)?;
    bench_content_fits(&mut c, "content_fits_5596_numeric", &"7".repeat(5596), true)?;
    // Worst case that still fits: one whole byte segment is shorter than every optimised segmentation.
    let mixed = "x12345".repeat(400);
    let mixed_2331 = mixed.get(..2331).ok_or("pattern too short")?;
    bench_content_fits(&mut c, "content_fits_2331B_mixed_whole_byte", mixed_2331, true)?;
    // Non-ASCII content: ECI 26 plus Kanji-free segmentation.
    let japanese = "日本語のテキスト、東京都渋谷区".repeat(100);
    let japanese_2328 = japanese.get(..2328).ok_or("pattern too short")?;
    bench_content_fits(&mut c, "content_fits_2328B_cjk", japanese_2328, true)?;
    // Worst non-ASCII fit: alternating accented letter and digits, right at the 2330-byte limit.
    let alternating = "é12345".repeat(400);
    let alternating_2330 = alternating.get(..2330).ok_or("pattern too short")?;
    bench_content_fits(&mut c, "content_fits_2330B_alternating", alternating_2330, true)?;
    // Worst non-ASCII overflow: the largest input the size cap lets through.
    let overflow = "é1".repeat(3000);
    let overflow_7089 = overflow.get(..7089).ok_or("pattern too short")?;
    bench_content_fits(&mut c, "content_fits_7089B_non_ascii_overflow", overflow_7089, false)?;
    bench_content_fits(&mut c, "content_fits_7000B_overflow", &"a".repeat(7000), false)?;
    // Input beyond the encodable cap is rejected before any parsing.
    bench_content_fits(&mut c, "content_fits_1MB_cap", &"a".repeat(1024 * 1024), false)?;
    c.final_summary();
    Ok(())
}
