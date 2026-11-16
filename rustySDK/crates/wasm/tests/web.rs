//! Browser tests for the `wasm-bindgen` exports; run with `wasm-pack test --headless --chrome`.

use rusty_qr_wasm::{decode_image, generate_png, library_version};
use wasm_bindgen_test::{wasm_bindgen_test, wasm_bindgen_test_configure};

wasm_bindgen_test_configure!(run_in_browser);

#[wasm_bindgen_test]
fn generated_png_decodes_back_to_the_content() {
    let png = generate_png("hello", 256);
    assert!(png.is_ok(), "generation failed");
    let decoded = png.ok().map(|bytes| decode_image(&bytes));
    assert!(
        matches!(decoded, Some(Ok(ref content)) if content == "hello"),
        "round trip failed"
    );
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
fn library_version_is_not_empty() {
    assert!(!library_version().is_empty());
}
