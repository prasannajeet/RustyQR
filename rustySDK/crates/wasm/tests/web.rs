//! Browser tests for the `wasm-bindgen` exports; run with `wasm-pack test --headless --chrome`.

use rusty_qr_wasm::{decode_image, generate_png, library_version};
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
