//! Event-based command dispatch for the QR engine.
//!
//! This module implements the command-in / response-out pattern that mirrors
//! the MVI architecture used on the mobile side. Every public operation is
//! expressed as a [`QrCommand`] variant and routed through [`process`], which
//! returns a [`QrResponse`] or a [`QrError`].
//!
//! **Note:** The command dispatcher is intended for internal use and testing.
//! The FFI layer (Phase 3) deliberately exports individual functions rather
//! than `process()` because UniFFI works better with simple function
//! signatures than enum dispatch. Mobile callers use the generated bindings;
//! Rust-side consumers can use either the dispatcher or the direct functions.

use crate::decoder;
use crate::encoder;
use crate::error::QrError;
use crate::types::{QrConfig, ScanResult};

/// Commands that can be sent to the QR engine.
///
/// Each variant maps 1:1 to a mobile Intent that will cross the FFI boundary
/// in Phase 3.
#[derive(Debug, Clone, PartialEq)]
pub enum QrCommand {
    /// Generate a QR code PNG from text content at the given pixel size.
    GeneratePng {
        /// The text to encode. Must not be empty.
        content: String,
        /// Output image side length in pixels. Must be in `1..=4096`.
        size: u32,
    },
    /// Generate a QR code PNG with custom configuration.
    GenerateWithConfig {
        /// The text to encode. Must not be empty.
        content: String,
        /// Generation configuration (size and error correction level).
        config: QrConfig,
    },
    /// Decode a QR code from PNG or JPEG image bytes.
    Decode {
        /// Raw image bytes (PNG or JPEG). Must not be empty.
        image_data: Vec<u8>,
    },
    /// Decode a QR code from a raw grayscale pixel buffer (camera frame path).
    DecodeFromRaw {
        /// Raw grayscale (Y-plane) pixel data. Must have exactly `width * height` bytes.
        pixels: Vec<u8>,
        /// Frame width in pixels. Must be greater than 0.
        width: u32,
        /// Frame height in pixels. Must be greater than 0.
        height: u32,
    },
    /// Query the library version string.
    GetVersion,
}

/// Responses from the QR engine.
///
/// Each variant maps 1:1 to a state update on the mobile side.
#[derive(Debug, Clone, PartialEq)]
#[must_use]
pub enum QrResponse {
    /// A QR code PNG was successfully generated.
    PngGenerated {
        /// The raw PNG bytes.
        data: Vec<u8>,
    },
    /// A QR code was successfully decoded from an image.
    Decoded {
        /// The decoded scan result containing the text content.
        result: ScanResult,
    },
    /// The library version string.
    Version {
        /// The semver version string (e.g. `"0.1.0"`).
        version: String,
    },
}

/// Dispatches a [`QrCommand`] to the appropriate engine function and returns
/// the corresponding [`QrResponse`].
///
/// This is the canonical entry point for all QR operations. Individual
/// functions in [`crate::encoder`] and [`crate::decoder`] are also exposed
/// for direct use and FFI convenience.
///
/// # Errors
///
/// * [`QrError::InvalidInput`] – Any command variant received invalid
///   arguments (empty content, bad size, pixel buffer mismatch, etc.).
/// * [`QrError::EncodingFailed`] – The QR encoding step failed.
/// * [`QrError::ImageError`] – The PNG serialisation or image loading step
///   failed.
/// * [`QrError::DecodingFailed`] – No QR code was found in the provided image
///   or raw buffer, or the detected code could not be decoded.
pub fn process(command: QrCommand) -> Result<QrResponse, QrError> {
    match command {
        QrCommand::GeneratePng { content, size } => {
            let data = encoder::generate_png(&content, size)?;
            Ok(QrResponse::PngGenerated { data })
        }
        QrCommand::GenerateWithConfig { content, config } => {
            let data = encoder::generate_with_config(&content, config)?;
            Ok(QrResponse::PngGenerated { data })
        }
        QrCommand::Decode { image_data } => {
            let result = decoder::decode(&image_data)?;
            Ok(QrResponse::Decoded { result })
        }
        QrCommand::DecodeFromRaw { pixels, width, height } => {
            let result = decoder::decode_from_raw(&pixels, width, height)?;
            Ok(QrResponse::Decoded { result })
        }
        QrCommand::GetVersion => Ok(QrResponse::Version {
            version: encoder::get_library_version(),
        }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn process_generate_png() {
        let cmd = QrCommand::GeneratePng {
            content: "hi".into(),
            size: 128,
        };
        let resp = process(cmd).unwrap();
        assert!(matches!(resp, QrResponse::PngGenerated { .. }));
        if let QrResponse::PngGenerated { data } = resp {
            assert_eq!(&data[..8], b"\x89PNG\r\n\x1a\n");
        }
    }

    #[test]
    fn process_get_version() {
        let resp = process(QrCommand::GetVersion).unwrap();
        assert!(matches!(resp, QrResponse::Version { .. }));
        if let QrResponse::Version { version } = resp {
            assert_eq!(version, "0.1.0");
        }
    }

    #[test]
    fn process_decode() {
        // Generate a QR PNG first, then decode it through the dispatcher.
        let gen_resp = process(QrCommand::GeneratePng {
            content: "dispatch-test".into(),
            size: 256,
        })
        .unwrap();

        let png = if let QrResponse::PngGenerated { data } = gen_resp {
            data
        } else {
            panic!("expected PngGenerated");
        };

        let dec_resp = process(QrCommand::Decode { image_data: png }).unwrap();
        assert!(matches!(dec_resp, QrResponse::Decoded { .. }));
        if let QrResponse::Decoded { result } = dec_resp {
            assert_eq!(result.content, "dispatch-test");
        }
    }

    #[test]
    fn process_decode_from_raw() {
        use crate::encoder;
        let png = encoder::generate_png("raw-dispatch", 256).unwrap();
        let img = image::load_from_memory(&png).unwrap();
        let luma = img.to_luma8();
        let (w, h) = (luma.width(), luma.height());

        let resp = process(QrCommand::DecodeFromRaw {
            pixels: luma.as_raw().to_vec(),
            width: w,
            height: h,
        })
        .unwrap();

        assert!(matches!(resp, QrResponse::Decoded { .. }));
        if let QrResponse::Decoded { result } = resp {
            assert_eq!(result.content, "raw-dispatch");
        }
    }

    #[test]
    fn process_generate_with_config() {
        use crate::types::{QrConfig, QrErrorCorrection};
        let cmd = QrCommand::GenerateWithConfig {
            content: "configured".into(),
            config: QrConfig {
                size: 256,
                error_correction: QrErrorCorrection::High,
            },
        };
        let resp = process(cmd).unwrap();
        assert!(matches!(resp, QrResponse::PngGenerated { .. }));
    }
}
