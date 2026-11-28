//! Error type for the risk domain.
//!
//! The risk check is its own bounded context, so it has its own error type rather than adding
//! variants to [`crate::QrError`]: QR encoding and decoding callers never see risk errors, and
//! foreign callers that handle every `QrError` variant are not affected by this module.

/// Errors from the URL risk check.
///
/// All variants use named fields to stay compatible with `UniFFI`.
#[derive(Debug, thiserror::Error)]
pub enum RiskError {
    /// The caller passed input the check cannot use (an empty URL, model files that are not valid).
    #[error("Invalid input: {reason}")]
    InvalidInput {
        /// What was wrong with the input.
        reason: String,
    },
    /// A score or threshold was outside `0.0..=1.0`, or not a number.
    #[error("Invalid score: {reason}")]
    InvalidScore {
        /// The value and why it was rejected.
        reason: String,
    },
    /// The model failed to load or to score a URL.
    #[error("Model failed: {reason}")]
    ModelFailed {
        /// The model runtime's error message.
        reason: String,
    },
}
