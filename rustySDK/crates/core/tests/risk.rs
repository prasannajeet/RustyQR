//! Public-API tests for the URL risk check: the input limits, the threshold rule and the score range.

use std::cell::RefCell;

use rusty_qr_core::risk::{assess, RiskError, RiskPolicy, RiskScore, RiskVerdict, UrlRiskModel, MAX_URL_BYTES};

/// A fake model: returns a fixed score and records the URL it was given.
struct FakeModel {
    score: f32,
    seen: RefCell<Option<String>>,
}

impl FakeModel {
    fn scoring(score: f32) -> Self {
        Self {
            score,
            seen: RefCell::new(None),
        }
    }

    fn seen_len(&self) -> Option<usize> {
        self.seen.borrow().as_ref().map(String::len)
    }
}

impl UrlRiskModel for FakeModel {
    fn score(&self, url: &str) -> Result<RiskScore, RiskError> {
        self.seen.replace(Some(url.to_owned()));
        RiskScore::new(self.score)
    }
}

/// A fake model whose runtime fails.
struct FailingModel;

impl UrlRiskModel for FailingModel {
    fn score(&self, _url: &str) -> Result<RiskScore, RiskError> {
        Err(RiskError::ModelFailed {
            reason: "runtime fell over".into(),
        })
    }
}

#[test]
fn threshold_is_inclusive() -> Result<(), Box<dyn std::error::Error>> {
    let policy = RiskPolicy::new(0.8)?;
    let cases = [(0.8_f32, true), (0.8_f32.next_down(), false), (1.0, true), (0.0, false)];
    for (score, suspicious) in cases {
        let verdict = assess("https://example.com", &FakeModel::scoring(score), &policy)?;
        assert_eq!(
            matches!(verdict, RiskVerdict::Suspicious { .. }),
            suspicious,
            "score {score} gave {verdict:?}"
        );
    }
    Ok(())
}

#[test]
fn suspicious_verdict_carries_the_model_score() -> Result<(), Box<dyn std::error::Error>> {
    let verdict = assess("https://example.com", &FakeModel::scoring(0.93), &RiskPolicy::new(0.5)?)?;
    assert_eq!(
        verdict,
        RiskVerdict::Suspicious {
            score: RiskScore::new(0.93)?
        }
    );
    Ok(())
}

#[test]
fn url_is_cut_to_the_byte_limit() -> Result<(), Box<dyn std::error::Error>> {
    let policy = RiskPolicy::new(0.5)?;
    let cases = [
        (MAX_URL_BYTES - 1, MAX_URL_BYTES - 1),
        (MAX_URL_BYTES, MAX_URL_BYTES),
        (MAX_URL_BYTES + 1, MAX_URL_BYTES),
        (10 * MAX_URL_BYTES, MAX_URL_BYTES),
    ];
    for (input_len, expected_len) in cases {
        let model = FakeModel::scoring(0.1);
        let verdict = assess(&"a".repeat(input_len), &model, &policy)?;
        assert_eq!(verdict, RiskVerdict::NoWarning);
        assert_eq!(model.seen_len(), Some(expected_len), "input of {input_len} bytes");
    }
    Ok(())
}

#[test]
fn cut_never_splits_a_character() -> Result<(), Box<dyn std::error::Error>> {
    // 2047 ASCII bytes, then a two-byte "é" that straddles the limit: the cut must drop the whole "é".
    let url = format!("{}é", "a".repeat(MAX_URL_BYTES - 1));
    let model = FakeModel::scoring(0.1);
    let verdict = assess(&url, &model, &RiskPolicy::new(0.5)?)?;
    assert_eq!(verdict, RiskVerdict::NoWarning);
    assert_eq!(model.seen_len(), Some(MAX_URL_BYTES - 1));
    Ok(())
}

#[test]
fn surrounding_whitespace_is_ignored() -> Result<(), Box<dyn std::error::Error>> {
    let model = FakeModel::scoring(0.1);
    let verdict = assess("  https://example.com \n", &model, &RiskPolicy::new(0.5)?)?;
    assert_eq!(verdict, RiskVerdict::NoWarning);
    assert_eq!(model.seen.borrow().as_deref(), Some("https://example.com"));
    Ok(())
}

#[test]
fn empty_or_blank_url_is_rejected_before_the_model_runs() -> Result<(), Box<dyn std::error::Error>> {
    for url in ["", "   ", "\n\t"] {
        let model = FakeModel::scoring(0.1);
        let result = assess(url, &model, &RiskPolicy::new(0.5)?);
        assert!(
            matches!(result, Err(RiskError::InvalidInput { .. })),
            "{url:?} gave {result:?}"
        );
        assert_eq!(model.seen_len(), None, "the model must not run for {url:?}");
    }
    Ok(())
}

#[test]
fn model_errors_pass_through() -> Result<(), Box<dyn std::error::Error>> {
    let result = assess("https://example.com", &FailingModel, &RiskPolicy::new(0.5)?);
    assert!(matches!(result, Err(RiskError::ModelFailed { .. })), "got {result:?}");
    Ok(())
}

#[test]
fn scores_outside_zero_to_one_are_rejected() {
    let cases = [
        (0.0_f32, true),
        (1.0, true),
        (0.5, true),
        (0.0_f32.next_down(), false),
        (1.0_f32.next_up(), false),
        (f32::NAN, false),
        (f32::INFINITY, false),
        (f32::NEG_INFINITY, false),
    ];
    for (value, valid) in cases {
        let score = RiskScore::new(value);
        assert_eq!(score.is_ok(), valid, "{value} gave {score:?}");
        if !valid {
            assert!(
                matches!(score, Err(RiskError::InvalidScore { .. })),
                "{value} gave {score:?}"
            );
        }
        let policy = RiskPolicy::new(value);
        assert_eq!(policy.is_ok(), valid, "threshold {value} gave {policy:?}");
    }
}

#[test]
fn a_model_returning_an_invalid_score_is_an_error_not_a_verdict() -> Result<(), Box<dyn std::error::Error>> {
    let result = assess("https://example.com", &FakeModel::scoring(1.5), &RiskPolicy::new(0.5)?);
    assert!(matches!(result, Err(RiskError::InvalidScore { .. })), "got {result:?}");
    Ok(())
}
