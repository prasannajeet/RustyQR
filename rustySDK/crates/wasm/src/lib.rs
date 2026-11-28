#![forbid(unsafe_code)]
//! `rusty-qr-wasm` — `wasm-bindgen` exports of the `rusty-qr-core` engine for browsers.
//!
//! Every function delegates to the core in one expression. Core errors become JavaScript
//! `Error` objects carrying the core's message. This crate holds no business logic.
//!
//! `UrlRiskChecker` exposes the URL risk check from `rusty-qr-risk-candle`.

use rusty_qr_core::risk::RiskVerdict;
use rusty_qr_core::{decoder, encoder};
use wasm_bindgen::prelude::*;

/// Generates a QR code PNG for `content`, `size` pixels wide and high.
///
/// # Errors
///
/// Returns a JavaScript `Error` carrying the core's message if the content is empty or too
/// long, the size is out of range, or encoding fails.
#[wasm_bindgen(js_name = generatePng)]
pub fn generate_png(content: &str, size: u32) -> Result<Vec<u8>, JsError> {
    encoder::generate_png(content, size).map_err(JsError::from)
}

/// Decodes the QR code in an encoded image (PNG or JPEG) and returns its content.
///
/// # Errors
///
/// Returns a JavaScript `Error` carrying the core's message if the bytes are not a supported
/// image or hold no readable QR code.
#[wasm_bindgen(js_name = decodeImage)]
pub fn decode_image(bytes: &[u8]) -> Result<String, JsError> {
    decoder::decode(bytes).map(|scan| scan.content).map_err(JsError::from)
}

/// Returns the version of the Rust core library.
#[wasm_bindgen(js_name = libraryVersion)]
#[must_use]
pub fn library_version() -> String {
    encoder::get_library_version()
}

/// The URL risk check, with its model loaded. Exported to JavaScript as `UrlRiskChecker`.
#[wasm_bindgen(js_name = UrlRiskChecker)]
pub struct WasmUrlRiskChecker {
    inner: rusty_qr_risk_candle::UrlRiskChecker,
}

#[wasm_bindgen(js_class = UrlRiskChecker)]
impl WasmUrlRiskChecker {
    /// Loads the model from the bytes of its `model.safetensors`, `tokenizer.json` and `config.json`,
    /// and flags scores at or above `threshold`, which comes from the model's evaluation.
    ///
    /// # Errors
    ///
    /// Returns a JavaScript `Error` carrying the risk check's message if the threshold is out of range
    /// or the model files are not valid.
    #[wasm_bindgen(constructor)]
    pub fn new(
        weights: Vec<u8>,
        tokenizer_json: &[u8],
        config_json: &[u8],
        threshold: f32,
    ) -> Result<WasmUrlRiskChecker, JsError> {
        rusty_qr_risk_candle::UrlRiskChecker::new(weights, tokenizer_json, config_json, threshold)
            .map(|inner| Self { inner })
            .map_err(JsError::from)
    }

    /// Assesses one URL. Returns the model's score when the URL is suspicious, and `undefined` when the
    /// check has nothing to warn about. There is no "safe" result.
    ///
    /// # Errors
    ///
    /// Returns a JavaScript `Error` carrying the risk check's message if the URL is empty or the model
    /// cannot score it.
    #[wasm_bindgen(js_name = assessUrl)]
    pub fn assess_url(&self, url: &str) -> Result<Option<f32>, JsError> {
        self.inner.assess(url).map(suspicious_score).map_err(JsError::from)
    }
}

/// The score of a suspicious verdict, or `None` when there is nothing to warn about.
fn suspicious_score(verdict: RiskVerdict) -> Option<f32> {
    match verdict {
        RiskVerdict::Suspicious { score } => Some(score.value()),
        RiskVerdict::NoWarning => None,
    }
}
