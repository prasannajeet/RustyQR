//! QR code decoding functions.
//!
//! This module provides two decode paths:
//! - [`decode`] — loads PNG or JPEG image bytes and scans for a QR code.
//! - [`decode_from_raw`] — accepts a raw grayscale pixel buffer directly,
//!   which is the low-overhead path for camera frames on mobile.
//!
//! Both paths converge on the private `decode_grayscale` helper, which uses
//! the `rqrr` crate's grid detection and decoding pipeline.

use std::io::Cursor;

use image::{ImageReader, Limits};

use crate::error::QrError;
use crate::types::ScanResult;

/// Most bytes the image decoder may allocate for one image (256 MiB), counting the decoded pixel buffer.
///
/// A 48 MP phone photo (8064 x 6048) needs 146 MB as RGB and 195 MB as RGBA, so it still decodes. Without
/// this the `image` default of 512 MiB lets a tiny, highly compressed file claim a 484 MP image, and the
/// grayscale copy and the `rqrr` buffer then triple that.
const MAX_DECODE_ALLOC_BYTES: u64 = 256 * 1024 * 1024;

/// Longest side of an image the decoder accepts, in pixels. Strict, and checked from the file header
/// before any pixel is allocated.
const MAX_DECODE_DIMENSION: u32 = 16_384;

/// Decodes a QR code from PNG or JPEG image bytes.
///
/// The image is loaded into memory, converted to grayscale, and then scanned
/// for a QR code. Only the first detected QR code is decoded. Images over
/// [`MAX_DECODE_DIMENSION`] pixels on a side, or needing more than
/// [`MAX_DECODE_ALLOC_BYTES`] to decode, are rejected before their pixels are allocated.
///
/// # Arguments
///
/// * `image_data` – Raw bytes of a PNG or JPEG image. Must not be empty.
///
/// # Errors
///
/// * [`QrError::InvalidInput`] – `image_data` is empty.
/// * [`QrError::ImageError`] – The bytes could not be decoded as a valid image, or the image is larger
///   than the decoder's limits.
/// * [`QrError::DecodingFailed`] – No QR code was found in the image, or the
///   detected QR code could not be decoded.
pub fn decode(image_data: &[u8]) -> Result<ScanResult, QrError> {
    if image_data.is_empty() {
        return Err(QrError::InvalidInput {
            reason: "image_data must not be empty".into(),
        });
    }

    let image_error = |reason: String| QrError::ImageError { reason };
    let mut reader = ImageReader::new(Cursor::new(image_data))
        .with_guessed_format()
        .map_err(|e| image_error(e.to_string()))?;
    // `Limits` is non-exhaustive, so it cannot be built with a struct expression.
    let mut limits = Limits::default();
    limits.max_image_width = Some(MAX_DECODE_DIMENSION);
    limits.max_image_height = Some(MAX_DECODE_DIMENSION);
    limits.max_alloc = Some(MAX_DECODE_ALLOC_BYTES);
    reader.limits(limits);
    let luma = reader.decode().map_err(|e| image_error(e.to_string()))?.into_luma8();
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
    decode_grayscale(width, height, pixels)
}

/// Shared grayscale-decode logic used by both public decode paths.
///
/// Validates that `pixels.len() == width * height`, prepares an `rqrr` image
/// from the grayscale buffer, detects grids, and decodes the first one found.
///
/// # Errors
///
/// * [`QrError::InvalidInput`] – the length check fails or overflows.
/// * [`QrError::DecodingFailed`] – no QR code found or it could not be decoded.
///
/// # Performance
///
/// `prepare_from_greyscale` invokes the closure once per pixel (O(width×height)).
/// With `rqrr`'s default features off, as this crate builds it, this copies
/// each pixel into `rqrr`'s own buffer. `rqrr`'s optional `img` feature
/// (`GrayImage::from_raw` + `PreparedImage::prepare`) would avoid that copy at
/// the cost of taking ownership of a `Vec<u8>`.
fn decode_grayscale(width: u32, height: u32, pixels: &[u8]) -> Result<ScanResult, QrError> {
    let (w, h) = (width as usize, height as usize);
    let expected = w.checked_mul(h).ok_or_else(|| QrError::InvalidInput {
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

    // Unreachable fallback: pixels.len() == w * h is checked above, and rqrr calls fill only with x < w and y < h.
    let mut prepared =
        rqrr::PreparedImage::prepare_from_greyscale(w, h, |x, y| pixels.get(y * w + x).copied().unwrap_or(0));

    let grids = prepared.detect_grids();
    let grid = grids.first().ok_or_else(|| QrError::DecodingFailed {
        reason: "no QR code found in image".into(),
    })?;

    let (_, content) = grid
        .decode()
        .map_err(|e| QrError::DecodingFailed { reason: e.to_string() })?;

    Ok(ScanResult { content })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::encoder;
    use crate::types::{QrConfig, QrErrorCorrection};

    #[test]
    fn decode_valid_qr_png() -> Result<(), Box<dyn std::error::Error>> {
        let png = encoder::generate_png("test", 256)?;
        let result = decode(&png)?;
        assert_eq!(result.content, "test");
        Ok(())
    }

    #[test]
    fn decode_empty_data_fails() {
        let result = decode(&[]);
        assert!(matches!(result, Err(QrError::InvalidInput { .. })), "got {result:?}");
    }

    #[test]
    fn decode_empty_data_reports_reason() {
        let result = decode(&[]);
        assert!(
            matches!(&result, Err(QrError::InvalidInput { reason }) if reason == "image_data must not be empty"),
            "got {result:?}"
        );
    }

    #[test]
    fn decode_invalid_image_data_fails() {
        let result = decode(&[0xFF, 0x00, 0x42]);
        assert!(matches!(result, Err(QrError::ImageError { .. })), "got {result:?}");
    }

    #[test]
    fn decode_image_without_qr_fails() -> Result<(), Box<dyn std::error::Error>> {
        // Generate a valid QR PNG and then replace its pixel data with white pixels
        // by creating a plain white 16x16 PNG via the image crate.
        use image::codecs::png::PngEncoder;
        use image::{ExtendedColorType, ImageEncoder};

        let white_pixels = vec![255u8; 16 * 16];
        let mut buf: Vec<u8> = Vec::new();
        PngEncoder::new(&mut buf).write_image(&white_pixels, 16, 16, ExtendedColorType::L8)?;

        let result = decode(&buf);
        assert!(matches!(result, Err(QrError::DecodingFailed { .. })), "got {result:?}");
        Ok(())
    }

    #[test]
    fn decode_from_raw_valid() -> Result<(), Box<dyn std::error::Error>> {
        let png = encoder::generate_png("hello raw", 256)?;
        let img = image::load_from_memory(&png)?;
        let luma = img.to_luma8();
        let (w, h) = (luma.width(), luma.height());
        let result = decode_from_raw(luma.as_raw(), w, h)?;
        assert_eq!(result.content, "hello raw");
        Ok(())
    }

    #[test]
    fn decode_from_raw_empty_fails() {
        let result = decode_from_raw(&[], 0, 0);
        assert!(matches!(result, Err(QrError::InvalidInput { .. })), "got {result:?}");
    }

    #[test]
    fn decode_from_raw_size_mismatch_fails() {
        // 10 pixels but claiming 100x100
        let result = decode_from_raw(&[0u8; 10], 100, 100);
        assert!(matches!(result, Err(QrError::InvalidInput { .. })), "got {result:?}");
    }

    #[test]
    fn decode_from_raw_zero_dimensions_fails() {
        let result = decode_from_raw(&[128u8; 100], 0, 100);
        assert!(matches!(result, Err(QrError::InvalidInput { .. })), "got {result:?}");
    }

    #[test]
    fn decode_from_raw_reports_each_invalid_input_reason() {
        let reason = |result: Result<ScanResult, QrError>| match result {
            Err(QrError::InvalidInput { reason }) => Some(reason),
            _ => None,
        };
        assert_eq!(
            reason(decode_from_raw(&[], 0, 0)).as_deref(),
            Some("pixels must not be empty")
        );
        assert_eq!(
            reason(decode_from_raw(&[1u8; 4], 0, 4)).as_deref(),
            Some("width and height must be greater than 0")
        );
        assert_eq!(
            reason(decode_from_raw(&[1u8; 10], 100, 100)).as_deref(),
            Some("pixels.len() (10) must equal width * height (10000)")
        );
    }

    #[test]
    fn decode_with_config_high_ec() -> Result<(), Box<dyn std::error::Error>> {
        let config = QrConfig {
            size: 512,
            error_correction: QrErrorCorrection::High,
        };
        let png = encoder::generate_with_config("hi", config)?;
        let result = decode(&png)?;
        assert_eq!(result.content, "hi");
        Ok(())
    }

    /// CRC-32 (IEEE) as PNG chunks use it.
    fn crc32(bytes: &[u8]) -> u32 {
        let mut crc = u32::MAX;
        for &byte in bytes {
            crc ^= u32::from(byte);
            for _ in 0..8 {
                crc = if crc & 1 == 1 {
                    (crc >> 1) ^ 0xEDB8_8320
                } else {
                    crc >> 1
                };
            }
        }
        !crc
    }

    fn png_chunk(kind: [u8; 4], data: &[u8]) -> Vec<u8> {
        let mut chunk = Vec::new();
        chunk.extend_from_slice(&u32::try_from(data.len()).unwrap_or(0).to_be_bytes());
        let mut body = kind.to_vec();
        body.extend_from_slice(data);
        chunk.extend_from_slice(&body);
        chunk.extend_from_slice(&crc32(&body).to_be_bytes());
        chunk
    }

    /// A few dozen bytes that declare a `width` x `height` 8-bit RGB PNG whose pixels are never stored.
    fn png_declaring(width: u32, height: u32) -> Vec<u8> {
        let mut header = Vec::new();
        header.extend_from_slice(&width.to_be_bytes());
        header.extend_from_slice(&height.to_be_bytes());
        header.extend_from_slice(&[8, 2, 0, 0, 0]);
        let mut png = b"\x89PNG\r\n\x1a\n".to_vec();
        png.extend(png_chunk(*b"IHDR", &header));
        // An empty stored zlib stream, so the header parses to the point where limits are checked.
        png.extend(png_chunk(*b"IDAT", &[0x78, 0x01, 0x03, 0x00, 0x00, 0x00, 0x00, 0x01]));
        png.extend(png_chunk(*b"IEND", &[]));
        png
    }

    #[test]
    fn decode_rejects_images_over_the_limits_without_allocating_them() {
        // 30000 x 30000 breaks the dimension limit; 15000 x 15000 RGB (675 MB) only the allocation limit.
        for (width, height) in [(30_000, 30_000), (15_000, 15_000), (MAX_DECODE_DIMENSION + 1, 1)] {
            let png = png_declaring(width, height);
            assert!(png.len() < 200);
            let result = decode(&png);
            assert!(
                matches!(&result, Err(QrError::ImageError { reason }) if reason.to_lowercase().contains("limit")),
                "{width}x{height}: got {result:?}"
            );
        }
    }

    #[test]
    fn decode_accepts_a_photo_sized_image() -> Result<(), Box<dyn std::error::Error>> {
        use image::codecs::png::{CompressionType, FilterType, PngEncoder};
        use image::{imageops, DynamicImage, GrayImage, ImageEncoder, Luma};

        // 8064 x 6048 (48 MP) RGB with a QR code in the middle.
        let code = image::load_from_memory(&encoder::generate_png("photo", 1024)?)?.to_luma8();
        let mut photo = GrayImage::from_pixel(8064, 6048, Luma([255]));
        imageops::replace(&mut photo, &code, 3520, 2512);
        let rgb = DynamicImage::ImageLuma8(photo).to_rgb8();
        let mut png = Vec::new();
        PngEncoder::new_with_quality(&mut png, CompressionType::Fast, FilterType::NoFilter).write_image(
            rgb.as_raw(),
            rgb.width(),
            rgb.height(),
            image::ExtendedColorType::Rgb8,
        )?;
        drop(rgb);
        assert_eq!(decode(&png)?.content, "photo");
        Ok(())
    }
}
