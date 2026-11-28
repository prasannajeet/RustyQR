//! Browser tests for the `wasm-bindgen` exports; run with `wasm-pack test --headless --chrome`.

use rusty_qr_wasm::{decode_image, generate_png, library_version, WasmUrlRiskChecker};
use wasm_bindgen::JsValue;
use wasm_bindgen_test::{wasm_bindgen_test, wasm_bindgen_test_configure};

wasm_bindgen_test_configure!(run_in_browser);

#[wasm_bindgen_test]
fn generated_png_decodes_back_to_the_content() -> Result<(), JsValue> {
    let png = generate_png("hello", 256)?;
    assert_eq!(decode_image(&png)?, "hello");
    Ok(())
}

#[wasm_bindgen_test]
fn non_ascii_content_round_trips_as_utf8() -> Result<(), JsValue> {
    for text in ["こんにちは世界こんにちは世界こんにちは世界", "Café 東京 2026"] {
        let png = generate_png(text, 512)?;
        assert_eq!(decode_image(&png)?, text);
    }
    Ok(())
}

#[wasm_bindgen_test]
fn empty_content_is_an_error() {
    assert!(generate_png("", 256).is_err());
}

#[wasm_bindgen_test]
fn bytes_that_are_not_an_image_are_an_error() {
    assert!(decode_image(b"definitely not an image").is_err());
}

#[wasm_bindgen_test]
fn library_version_matches_core() {
    assert_eq!(library_version(), rusty_qr_core::encoder::get_library_version());
}

const RISK_WEIGHTS: &[u8] = include_bytes!("../../risk-candle/tests/fixtures/tiny-bert/model.safetensors");
const RISK_TOKENIZER: &[u8] = include_bytes!("../../risk-candle/tests/fixtures/tiny-bert/tokenizer.json");
const RISK_CONFIG: &[u8] = include_bytes!("../../risk-candle/tests/fixtures/tiny-bert/config.json");
/// Between the fixture's scores for `https://example.com` (about 0.121) and 126 "a"s (about 0.066).
const RISK_THRESHOLD: f32 = 0.1;

#[wasm_bindgen_test]
fn risk_checker_flags_only_scores_at_the_threshold() -> Result<(), JsValue> {
    let checker = WasmUrlRiskChecker::new(RISK_WEIGHTS.to_vec(), RISK_TOKENIZER, RISK_CONFIG, RISK_THRESHOLD)?;
    let suspicious = checker.assess_url("https://example.com")?;
    assert!(
        suspicious.is_some_and(|score| score >= RISK_THRESHOLD),
        "{suspicious:?}"
    );
    assert_eq!(checker.assess_url(&"a".repeat(126))?, None);
    Ok(())
}

#[wasm_bindgen_test]
fn risk_checker_rejects_a_bad_threshold_and_an_empty_url() -> Result<(), JsValue> {
    assert!(WasmUrlRiskChecker::new(RISK_WEIGHTS.to_vec(), RISK_TOKENIZER, RISK_CONFIG, 1.5).is_err());
    let checker = WasmUrlRiskChecker::new(RISK_WEIGHTS.to_vec(), RISK_TOKENIZER, RISK_CONFIG, RISK_THRESHOLD)?;
    assert!(checker.assess_url(" ").is_err());
    Ok(())
}
