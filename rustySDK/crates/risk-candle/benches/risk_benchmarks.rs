//! Benchmarks for the URL risk check.
//!
//! By default these run against the tiny test fixture, which only shows the overhead around the model.
//! To measure the real model, point `RUSTYQR_RISK_MODEL_DIR` at a folder holding its
//! `model.safetensors`, `tokenizer.json` and `config.json`:
//!
//! ```text
//! RUSTYQR_RISK_MODEL_DIR=/path/to/model cargo bench -p rusty-qr-risk-candle
//! ```

use std::hint::black_box;
use std::path::PathBuf;

use criterion::{criterion_group, criterion_main, Criterion};
use rusty_qr_risk_candle::UrlRiskChecker;

const FIXTURE: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/tiny-bert");
const URLS: [&str; 3] = [
    "https://example.com",
    "http://paypa1-login.verify-account.xyz/secure?id=42",
    "https://kotlinlang.org/docs/multiplatform.html",
];

fn load() -> Result<(UrlRiskChecker, String), Box<dyn std::error::Error>> {
    let dir = std::env::var_os("RUSTYQR_RISK_MODEL_DIR").map_or_else(|| PathBuf::from(FIXTURE), PathBuf::from);
    let weights = std::fs::read(dir.join("model.safetensors"))?;
    let tokenizer = std::fs::read(dir.join("tokenizer.json"))?;
    let config = std::fs::read(dir.join("config.json"))?;
    let checker = UrlRiskChecker::new(weights, &tokenizer, &config, 0.5)?;
    Ok((checker, dir.display().to_string()))
}

fn bench_assess(c: &mut Criterion) {
    let (checker, source) = match load() {
        Ok(loaded) => loaded,
        Err(error) => {
            eprintln!("risk benchmarks skipped: could not load the model: {error}");
            return;
        }
    };
    eprintln!("risk benchmarks use the model in {source}");
    let mut group = c.benchmark_group("assess");
    for url in URLS {
        group.bench_function(url, |b| b.iter(|| checker.assess(black_box(url))));
    }
    group.finish();
}

fn bench_load(c: &mut Criterion) {
    c.bench_function("load model", |b| b.iter(load));
}

criterion_group!(benches, bench_assess, bench_load);
criterion_main!(benches);
