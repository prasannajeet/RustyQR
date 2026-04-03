//! JPEG decode test: validates the JPEG decode path in `decoder::decode`.
//!
//! Generates a QR code as PNG, re-encodes the pixels as JPEG, and then
//! decodes the JPEG bytes to confirm the content survives the lossy format.

use rusty_qr_core::{decoder, encoder};

#[test]
fn decode_jpeg_encoded_qr() {
    // Generate a QR PNG at a large size so the QR code survives JPEG compression.
    let png = encoder::generate_png("jpeg test", 1024).unwrap();

    // Load the PNG and re-encode as JPEG.
    let img = image::load_from_memory(&png).unwrap();
    let mut jpeg_buf: Vec<u8> = Vec::new();
    let mut cursor = std::io::Cursor::new(&mut jpeg_buf);
    img.write_to(&mut cursor, image::ImageFormat::Jpeg).unwrap();

    // Decode the JPEG bytes through the public API.
    let result = decoder::decode(&jpeg_buf).unwrap();
    assert_eq!(
        result.content, "jpeg test",
        "JPEG decode must preserve QR content"
    );
}
