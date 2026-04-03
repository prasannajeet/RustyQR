//! Round-trip integration tests: encode a QR code then decode it and verify
//! the content is preserved exactly.
//!
//! These tests treat `rusty_qr_core` as an external consumer (no access to
//! private internals), validating the full public API surface.

use rusty_qr_core::{
    decoder, encoder, process, QrCommand, QrConfig, QrErrorCorrection, QrResponse,
};

#[test]
fn round_trip_preserves_content() {
    let content = "https://example.com?foo=bar&baz=qux";
    let png = encoder::generate_png(content, 256).unwrap();
    let result = decoder::decode(&png).unwrap();
    assert_eq!(result.content, content);
}

#[test]
fn round_trip_unicode() {
    let content = "Hello 世界 🌍";
    let png = encoder::generate_png(content, 256).unwrap();
    let result = decoder::decode(&png).unwrap();
    assert_eq!(result.content, content);
}

#[test]
fn round_trip_with_config() {
    let content = "configured QR";
    let config = QrConfig {
        size: 512,
        error_correction: QrErrorCorrection::High,
    };
    let png = encoder::generate_with_config(content, config).unwrap();
    let result = decoder::decode(&png).unwrap();
    assert_eq!(result.content, content);
}

#[test]
fn round_trip_through_process_dispatcher() {
    let content = "dispatcher round-trip";
    let gen_resp = process(QrCommand::GeneratePng {
        content: content.into(),
        size: 256,
    })
    .unwrap();

    let QrResponse::PngGenerated { data } = gen_resp else {
        panic!("expected PngGenerated");
    };

    let dec_resp = process(QrCommand::Decode { image_data: data }).unwrap();
    assert_eq!(
        dec_resp,
        QrResponse::Decoded {
            result: rusty_qr_core::ScanResult {
                content: content.into(),
            },
        }
    );
}

#[test]
fn round_trip_long_content() {
    // PRD 6.6: encode and decode a 2000+ character string.
    let content: String = (0..2100)
        .map(|i| char::from(b'A' + (i % 26) as u8))
        .collect();
    let config = QrConfig {
        size: 1024,
        error_correction: QrErrorCorrection::Low,
    };
    let png = encoder::generate_with_config(&content, config).unwrap();
    let result = decoder::decode(&png).unwrap();
    assert_eq!(
        result.content, content,
        "long content must survive round-trip"
    );
}

#[test]
fn round_trip_all_ecl_levels() {
    let content = "round-trip ECL test";
    let levels = [
        QrErrorCorrection::Low,
        QrErrorCorrection::Medium,
        QrErrorCorrection::Quartile,
        QrErrorCorrection::High,
    ];
    for level in levels {
        let config = QrConfig {
            size: 512,
            error_correction: level,
        };
        let png = encoder::generate_with_config(content, config).unwrap();
        let result = decoder::decode(&png).unwrap();
        assert_eq!(
            result.content, content,
            "round-trip must preserve content at EC level {level:?}"
        );
    }
}
