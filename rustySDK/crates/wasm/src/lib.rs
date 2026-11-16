#![forbid(unsafe_code)]
//! `rusty-qr-wasm` — `wasm-bindgen` exports of the `rusty-qr-core` engine for browsers.
//!
//! Every function delegates to the core in one expression. Core errors become JavaScript
//! `Error` objects carrying the core's message. This crate holds no business logic.

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
    encoder::generate_png(content, size).map_err(|error| JsError::new(&error.to_string()))
}

/// Decodes the QR code in an encoded image (PNG or JPEG) and returns its content.
///
/// # Errors
///
/// Returns a JavaScript `Error` carrying the core's message if the bytes are not a supported
/// image or hold no readable QR code.
#[wasm_bindgen(js_name = decodeImage)]
pub fn decode_image(bytes: &[u8]) -> Result<String, JsError> {
    decoder::decode(bytes)
        .map(|scan| scan.content)
        .map_err(|error| JsError::new(&error.to_string()))
}

/// Returns the version of the Rust core library.
#[wasm_bindgen(js_name = libraryVersion)]
#[must_use]
pub fn library_version() -> String {
    encoder::get_library_version()
}
