//! Determinism tests: same input must always produce the same output.
//!
//! PRD 5.2 requires that identical inputs yield byte-identical outputs.

use rusty_qr_core::{encoder, QrConfig, QrErrorCorrection};

#[test]
fn generate_is_deterministic() {
    let a = encoder::generate_png("test", 256).unwrap();
    let b = encoder::generate_png("test", 256).unwrap();
    assert_eq!(
        a, b,
        "generate_png must be deterministic for identical inputs"
    );
}

#[test]
fn generate_with_config_is_deterministic() {
    let config_a = QrConfig {
        size: 512,
        error_correction: QrErrorCorrection::Quartile,
    };
    let config_b = QrConfig {
        size: 512,
        error_correction: QrErrorCorrection::Quartile,
    };
    let a = encoder::generate_with_config("determinism check", config_a).unwrap();
    let b = encoder::generate_with_config("determinism check", config_b).unwrap();
    assert_eq!(
        a, b,
        "generate_with_config must be deterministic for identical inputs"
    );
}
