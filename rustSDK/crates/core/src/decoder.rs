//! QR code decoding functions.
//!
//! This module provides two decode paths:
//! - [`decode`] — loads PNG or JPEG image bytes and scans for a QR code.
//! - [`decode_from_raw`] — accepts a raw grayscale pixel buffer directly,
//!   which is the low-overhead path for camera frames on mobile.

use crate::error::QrError;
use crate::types::ScanResult;

/// Decodes a QR code from PNG or JPEG image bytes.
///
/// The image is loaded into memory, converted to grayscale, and then scanned
/// for a QR code. Only the first detected QR code is decoded.
///
/// # Arguments
///
/// * `image_data` – Raw bytes of a PNG or JPEG image. Must not be empty.
///
/// # Errors
///
/// * [`QrError::InvalidInput`] – `image_data` is empty.
/// * [`QrError::ImageError`] – The bytes could not be decoded as a valid image.
/// * [`QrError::DecodingFailed`] – No QR code was found in the image, or the
///   detected QR code could not be decoded.
pub fn decode(image_data: &[u8]) -> Result<ScanResult, QrError> {
    if image_data.is_empty() {
        return Err(QrError::InvalidInput {
            reason: "image_data must not be empty".into(),
        });
    }

    let img = image::load_from_memory(image_data).map_err(|e| QrError::ImageError {
        reason: e.to_string(),
    })?;

    let luma = img.to_luma8();
    decode_grayscale(luma.width(), luma.height(), luma.as_raw())
}

/// Decodes a QR code from a raw grayscale pixel buffer.
///
/// This is the **camera frame path** — the caller provides the Y (luma) plane
/// directly so no PNG round-trip is required. The buffer must be exactly
/// `width × height` bytes long.
///
/// # Arguments
///
/// * `pixels` – Raw grayscale (Y-plane) pixel data. Must not be empty and must
///   have exactly `width * height` bytes.
/// * `width`  – Frame width in pixels. Must be greater than 0.
/// * `height` – Frame height in pixels. Must be greater than 0.
///
/// # Errors
///
/// * [`QrError::InvalidInput`] – `pixels` is empty, `width` or `height` is
///   zero, or `pixels.len()` does not equal `width * height`.
/// * [`QrError::DecodingFailed`] – No QR code was found in the buffer, or the
///   detected QR code could not be decoded.
pub fn decode_from_raw(pixels: &[u8], width: u32, height: u32) -> Result<ScanResult, QrError> {
    if pixels.is_empty() {
        return Err(QrError::InvalidInput {
            reason: "pixels must not be empty".into(),
        });
    }
    if width == 0 || height == 0 {
        return Err(QrError::InvalidInput {
            reason: "width and height must be greater than 0".into(),
        });
    }
    let expected = (width as usize)
        .checked_mul(height as usize)
        .ok_or_else(|| QrError::InvalidInput {
            reason: "width * height overflows usize".into(),
        })?;
    if pixels.len() != expected {
        return Err(QrError::InvalidInput {
            reason: format!(
                "pixels.len() ({}) must equal width * height ({})",
                pixels.len(),
                expected
            ),
        });
    }

    decode_grayscale(width, height, pixels)
}

/// Shared grayscale-decode logic used by both public decode paths.
///
/// Prepares an `rqrr` image from a grayscale pixel buffer, detects grids,
/// and decodes the first one found.
///
/// # Performance
///
/// `prepare_from_greyscale` invokes the closure once per pixel (O(width×height)).
/// This is `rqrr`'s API — there is no zero-copy alternative. For a 640×480
/// camera frame (~307 K pixels) the overhead is sub-millisecond on modern
/// hardware, well within the PRD's 20 ms target for the camera decode path.
fn decode_grayscale(width: u32, height: u32, pixels: &[u8]) -> Result<ScanResult, QrError> {
    let mut prepared =
        rqrr::PreparedImage::prepare_from_greyscale(width as usize, height as usize, |x, y| {
            pixels[y * width as usize + x]
        });

    let grids = prepared.detect_grids();
    if grids.is_empty() {
        return Err(QrError::DecodingFailed {
            reason: "no QR code found in image".into(),
        });
    }

    let (_, content) = grids[0].decode().map_err(|e| QrError::DecodingFailed {
        reason: e.to_string(),
    })?;

    Ok(ScanResult { content })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::encoder;
    use crate::types::{QrConfig, QrErrorCorrection};

    #[test]
    fn decode_valid_qr_png() {
        let png = encoder::generate_png("test", 256).unwrap();
        let result = decode(&png).unwrap();
        assert_eq!(result.content, "test");
    }

    #[test]
    fn decode_empty_data_fails() {
        let err = decode(&[]).unwrap_err();
        assert!(matches!(err, QrError::InvalidInput { .. }));
    }

    #[test]
    fn decode_invalid_image_data_fails() {
        let err = decode(&[0xFF, 0x00, 0x42]).unwrap_err();
        assert!(matches!(err, QrError::ImageError { .. }));
    }

    #[test]
    fn decode_image_without_qr_fails() {
        // Generate a valid QR PNG and then replace its pixel data with white pixels
        // by creating a plain white 16x16 PNG via the image crate.
        use image::codecs::png::PngEncoder;
        use image::{ExtendedColorType, ImageEncoder};

        let white_pixels = vec![255u8; 16 * 16];
        let mut buf: Vec<u8> = Vec::new();
        PngEncoder::new(&mut buf)
            .write_image(&white_pixels, 16, 16, ExtendedColorType::L8)
            .unwrap();

        let err = decode(&buf).unwrap_err();
        assert!(matches!(err, QrError::DecodingFailed { .. }));
    }

    #[test]
    fn decode_from_raw_valid() {
        let png = encoder::generate_png("hello raw", 256).unwrap();
        let img = image::load_from_memory(&png).unwrap();
        let luma = img.to_luma8();
        let (w, h) = (luma.width(), luma.height());
        let result = decode_from_raw(luma.as_raw(), w, h).unwrap();
        assert_eq!(result.content, "hello raw");
    }

    #[test]
    fn decode_from_raw_empty_fails() {
        let err = decode_from_raw(&[], 0, 0).unwrap_err();
        assert!(matches!(err, QrError::InvalidInput { .. }));
    }

    #[test]
    fn decode_from_raw_size_mismatch_fails() {
        // 10 pixels but claiming 100x100
        let err = decode_from_raw(&[0u8; 10], 100, 100).unwrap_err();
        assert!(matches!(err, QrError::InvalidInput { .. }));
    }

    #[test]
    fn decode_from_raw_zero_dimensions_fails() {
        let err = decode_from_raw(&[128u8; 100], 0, 100).unwrap_err();
        assert!(matches!(err, QrError::InvalidInput { .. }));
    }

    #[test]
    fn decode_with_config_high_ec() {
        let config = QrConfig {
            size: 512,
            error_correction: QrErrorCorrection::High,
        };
        let png = encoder::generate_with_config("hi", config).unwrap();
        let result = decode(&png).unwrap();
        assert_eq!(result.content, "hi");
    }
}
