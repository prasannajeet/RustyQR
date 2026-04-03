//! Performance benchmarks for rusty-qr-core.
//!
//! Validates PRD performance targets:
//! - QR generation (256px): < 10ms
//! - QR generation (1024px): < 50ms
//! - QR scanning (256px image): < 20ms
//! - QR scanning (1080p image): < 100ms

use criterion::{criterion_group, criterion_main, Criterion};
use rusty_qr_core::decoder;
use rusty_qr_core::encoder;
use rusty_qr_core::types::{QrConfig, QrErrorCorrection};

fn bench_generate_256(c: &mut Criterion) {
    c.bench_function("generate_png_256px", |b| {
        b.iter(|| encoder::generate_png("https://example.com", 256).unwrap());
    });
}

fn bench_generate_1024(c: &mut Criterion) {
    c.bench_function("generate_png_1024px", |b| {
        b.iter(|| encoder::generate_png("https://example.com", 1024).unwrap());
    });
}

fn bench_generate_4096(c: &mut Criterion) {
    c.bench_function("generate_png_4096px", |b| {
        b.iter(|| encoder::generate_png("https://example.com", 4096).unwrap());
    });
}

fn bench_decode_256(c: &mut Criterion) {
    let png = encoder::generate_png("https://example.com", 256).unwrap();
    c.bench_function("decode_png_256px", |b| {
        b.iter(|| decoder::decode(&png).unwrap());
    });
}

fn bench_decode_1080p(c: &mut Criterion) {
    let png = encoder::generate_png("https://example.com", 1080).unwrap();
    c.bench_function("decode_png_1080px", |b| {
        b.iter(|| decoder::decode(&png).unwrap());
    });
}

fn bench_decode_from_raw_256(c: &mut Criterion) {
    let png = encoder::generate_png("https://example.com", 256).unwrap();
    let img = image::load_from_memory(&png).unwrap();
    let luma = img.to_luma8();
    let (w, h) = (luma.width(), luma.height());
    let pixels = luma.into_raw();
    c.bench_function("decode_from_raw_256px", |b| {
        b.iter(|| decoder::decode_from_raw(&pixels, w, h).unwrap());
    });
}

fn bench_generate_with_config_all_ecl(c: &mut Criterion) {
    let levels = [
        ("Low", QrErrorCorrection::Low),
        ("Medium", QrErrorCorrection::Medium),
        ("Quartile", QrErrorCorrection::Quartile),
        ("High", QrErrorCorrection::High),
    ];
    for (name, ecl) in levels {
        c.bench_function(&format!("generate_with_config_{name}_256px"), |b| {
            b.iter(|| {
                let config = QrConfig {
                    size: 256,
                    error_correction: ecl,
                };
                encoder::generate_with_config("https://example.com", config).unwrap();
            });
        });
    }
}

fn bench_round_trip_256(c: &mut Criterion) {
    c.bench_function("round_trip_256px", |b| {
        b.iter(|| {
            let png = encoder::generate_png("https://example.com", 256).unwrap();
            decoder::decode(&png).unwrap()
        });
    });
}

criterion_group!(
    benches,
    bench_generate_256,
    bench_generate_1024,
    bench_generate_4096,
    bench_decode_256,
    bench_decode_1080p,
    bench_decode_from_raw_256,
    bench_generate_with_config_all_ecl,
    bench_round_trip_256,
);
criterion_main!(benches);
