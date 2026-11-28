//! URL risk check for scanned QR codes ("quishing" check).
//!
//! This module is the risk domain. It owns the language ([`RiskScore`], [`RiskVerdict`],
//! [`RiskPolicy`]), the rules (input limits, the threshold) and the port, [`UrlRiskModel`], that a
//! scoring model implements. No model runtime is imported here: the Candle adapter lives in the
//! `rusty-qr-risk-candle` crate and is passed in through the port, so the rules are tested with a fake.
//!
//! There is deliberately no "safe" verdict. A score below the threshold means the check has nothing
//! to warn about, which is not the same as the URL being safe.
//!
//! # Example
//!
//! ```rust
//! use rusty_qr_core::risk::{assess, RiskError, RiskPolicy, RiskScore, RiskVerdict, UrlRiskModel};
//!
//! struct Fixed(f32);
//! impl UrlRiskModel for Fixed {
//!     fn score(&self, _url: &str) -> Result<RiskScore, RiskError> {
//!         RiskScore::new(self.0)
//!     }
//! }
//!
//! let policy = RiskPolicy::new(0.8)?;
//! let verdict = assess("https://example.com/login", &Fixed(0.93), &policy)?;
//! assert!(matches!(verdict, RiskVerdict::Suspicious { .. }));
//! # Ok::<(), Box<dyn std::error::Error>>(())
//! ```

mod error;

pub use error::RiskError;

/// The longest URL, in bytes, that reaches the model. Longer URLs are cut to this length, at a
/// character boundary, before scoring.
pub const MAX_URL_BYTES: usize = 2048;

/// A model's estimate that a URL is phishing, from 0.0 (no sign of it) to 1.0.
///
/// Built only through [`RiskScore::new`], so every score in the domain is a finite number in range.
#[derive(Debug, Clone, Copy, PartialEq, PartialOrd)]
#[must_use]
pub struct RiskScore(f32);

impl RiskScore {
    /// Creates a score from a model's output.
    ///
    /// # Errors
    ///
    /// Returns [`RiskError::InvalidScore`] if `value` is NaN, infinite, below 0.0 or above 1.0.
    pub fn new(value: f32) -> Result<Self, RiskError> {
        if (0.0..=1.0).contains(&value) {
            Ok(Self(value))
        } else {
            Err(RiskError::InvalidScore {
                reason: format!("a score must be in 0.0..=1.0, got {value}"),
            })
        }
    }

    /// The score as a number in `0.0..=1.0`.
    #[must_use]
    pub fn value(self) -> f32 {
        self.0
    }
}

/// When a score counts as suspicious.
///
/// The threshold comes from the model's evaluation (the score at which it flags at most 2% of benign
/// URLs), not from a number chosen in code.
#[derive(Debug, Clone, Copy, PartialEq)]
#[must_use]
pub struct RiskPolicy {
    threshold: RiskScore,
}

impl RiskPolicy {
    /// Creates a policy that flags scores at or above `threshold`.
    ///
    /// # Errors
    ///
    /// Returns [`RiskError::InvalidScore`] if `threshold` is not a finite number in `0.0..=1.0`.
    pub fn new(threshold: f32) -> Result<Self, RiskError> {
        Ok(Self {
            threshold: RiskScore::new(threshold)?,
        })
    }

    /// The score at or above which a URL is suspicious.
    pub fn threshold(self) -> RiskScore {
        self.threshold
    }
}

/// The outcome of a risk check.
///
/// There is no `Safe` variant: below the threshold the check has nothing to say.
#[derive(Debug, Clone, Copy, PartialEq)]
#[must_use]
pub enum RiskVerdict {
    /// The score reached the policy's threshold. The app shows a warning.
    Suspicious {
        /// The model's score for the URL.
        score: RiskScore,
    },
    /// The score stayed below the threshold. The app shows nothing about risk.
    NoWarning,
}

/// The port a URL scoring model implements.
///
/// The domain calls it with a URL already checked against the input limits, so an adapter never sees
/// an empty URL or one longer than [`MAX_URL_BYTES`].
pub trait UrlRiskModel {
    /// Scores one URL.
    ///
    /// # Errors
    ///
    /// Returns [`RiskError::ModelFailed`] if the model cannot score the URL, or
    /// [`RiskError::InvalidScore`] if its output is not a valid score.
    fn score(&self, url: &str) -> Result<RiskScore, RiskError>;
}

/// Checks one scanned URL against the policy.
///
/// `url` is cut to [`MAX_URL_BYTES`] at a character boundary before the model sees it. The caller
/// decides what counts as a URL; this function scores whatever text it is given.
///
/// # Errors
///
/// Returns [`RiskError::InvalidInput`] if `url` is empty or only whitespace, and passes on any error
/// the model returns.
pub fn assess(url: &str, model: &dyn UrlRiskModel, policy: &RiskPolicy) -> Result<RiskVerdict, RiskError> {
    let url = url.trim();
    if url.is_empty() {
        return Err(RiskError::InvalidInput {
            reason: "the URL must not be empty".into(),
        });
    }
    let score = model.score(truncate_to_char_boundary(url, MAX_URL_BYTES))?;
    if score >= policy.threshold() {
        Ok(RiskVerdict::Suspicious { score })
    } else {
        Ok(RiskVerdict::NoWarning)
    }
}

/// The longest prefix of `text` that is at most `max_bytes` long and ends on a character boundary.
fn truncate_to_char_boundary(text: &str, max_bytes: usize) -> &str {
    if text.len() <= max_bytes {
        return text;
    }
    let end = text.floor_char_boundary(max_bytes);
    // floor_char_boundary returns an index that is <= max_bytes and on a boundary, so get() succeeds;
    // the fallback is unreachable.
    text.get(..end).unwrap_or("")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn truncation_keeps_whole_characters() {
        // "é" is two bytes, so a cut at byte 3 of "aéé" must stop after the first "é".
        assert_eq!(truncate_to_char_boundary("aéé", 3), "aé");
        assert_eq!(truncate_to_char_boundary("aéé", 4), "aé");
        assert_eq!(truncate_to_char_boundary("aéé", 5), "aéé");
        assert_eq!(truncate_to_char_boundary("abc", 3), "abc");
    }
}
