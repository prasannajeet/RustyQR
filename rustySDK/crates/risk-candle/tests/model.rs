//! Tests for the Candle adapter, run against a tiny random-weight BERT.
//!
//! The fixture and its expected scores come from `ml/quishing/make_test_fixture.py`, which computes
//! the scores with an independent `NumPy` implementation of the same model. Agreement here means Candle
//! loads every layer, the pooler and the classifier the way the Hugging Face layout defines them.

use rusty_qr_core::risk::{RiskError, RiskVerdict, UrlRiskModel};
use rusty_qr_risk_candle::{CandleBertModel, UrlRiskChecker, MAX_TOKENS};
use serde::Deserialize;

const WEIGHTS: &[u8] = include_bytes!("fixtures/tiny-bert/model.safetensors");
const TOKENIZER: &[u8] = include_bytes!("fixtures/tiny-bert/tokenizer.json");
const CONFIG: &[u8] = include_bytes!("fixtures/tiny-bert/config.json");
const EXPECTED: &[u8] = include_bytes!("fixtures/tiny-bert/expected_scores.json");

/// Candle runs in f32 and the reference in f64.
const TOLERANCE: f32 = 1e-4;

/// Token ids in the fixture's vocabulary (see `make_test_fixture.py`).
const CLS: u32 = 2;
const SEP: u32 = 3;
const A: u32 = 5;
const CONTINUED_B: u32 = 55;

#[derive(Deserialize)]
struct Expected {
    url: String,
    tokens: usize,
    phishing_probability: f32,
}

fn model() -> Result<CandleBertModel, RiskError> {
    CandleBertModel::from_bytes(WEIGHTS.to_vec(), TOKENIZER, CONFIG)
}

fn expected() -> Result<Vec<Expected>, serde_json::Error> {
    serde_json::from_slice(EXPECTED)
}

#[test]
fn scores_match_the_independent_reference() -> Result<(), Box<dyn std::error::Error>> {
    let model = model()?;
    let expected = expected()?;
    assert!(!expected.is_empty());
    for case in expected {
        let score = model.score(&case.url)?.value();
        assert!(
            (score - case.phishing_probability).abs() < TOLERANCE,
            "{}: candle {score}, reference {}",
            case.url,
            case.phishing_probability
        );
        assert_eq!(model.token_ids(&case.url)?.len(), case.tokens, "{}", case.url);
    }
    Ok(())
}

#[test]
fn tokens_are_pinned_for_a_short_url() -> Result<(), Box<dyn std::error::Error>> {
    // "ab" is one word: "a", then "##b" as a continuation, between the [CLS] and [SEP] markers.
    assert_eq!(model()?.token_ids("ab")?, vec![CLS, A, CONTINUED_B, SEP]);
    Ok(())
}

#[test]
fn token_cap_holds_at_the_limit_and_one_past_it() -> Result<(), Box<dyn std::error::Error>> {
    let model = model()?;
    // Every letter is one token, plus [CLS] and [SEP].
    let cases = [
        (MAX_TOKENS - 3, MAX_TOKENS - 1),
        (MAX_TOKENS - 2, MAX_TOKENS),
        (MAX_TOKENS - 1, MAX_TOKENS),
        (10 * MAX_TOKENS, MAX_TOKENS),
    ];
    for (letters, expected_len) in cases {
        let ids = model.token_ids(&"a".repeat(letters))?;
        assert_eq!(ids.len(), expected_len, "{letters} letters");
        assert_eq!(ids.first(), Some(&CLS), "{letters} letters");
        assert_eq!(ids.last(), Some(&SEP), "{letters} letters: truncation must keep [SEP]");
    }
    Ok(())
}

#[test]
fn same_url_gives_the_same_score() -> Result<(), Box<dyn std::error::Error>> {
    let model = model()?;
    let url = "http://paypa1-login.verify-account.xyz/secure?id=42";
    let first = model.score(url)?;
    let second = model.score(url)?;
    assert_eq!(first, second);
    Ok(())
}

#[test]
fn the_phishing_label_decides_which_probability_is_the_score() -> Result<(), Box<dyn std::error::Error>> {
    let swapped = String::from_utf8(CONFIG.to_vec())?
        .replace("\"0\": \"legitimate\"", "\"0\": \"phishing\"")
        .replace("\"1\": \"phishing\"", "\"1\": \"legitimate\"");
    assert_ne!(
        swapped.as_bytes(),
        CONFIG,
        "the fixture config changed shape; update this test"
    );
    let swapped_model = CandleBertModel::from_bytes(WEIGHTS.to_vec(), TOKENIZER, swapped.as_bytes())?;
    let model = model()?;
    let url = "https://example.com";
    let sum = model.score(url)?.value() + swapped_model.score(url)?.value();
    assert!(
        (sum - 1.0).abs() < TOLERANCE,
        "the two labels' probabilities must sum to 1, got {sum}"
    );
    Ok(())
}

#[test]
fn checker_applies_the_threshold() -> Result<(), Box<dyn std::error::Error>> {
    let threshold = 0.1;
    let expected = expected()?;
    let above = expected
        .iter()
        .find(|c| c.url == "https://example.com")
        .ok_or("fixture case missing")?;
    let below = expected
        .iter()
        .find(|c| c.url.starts_with("aaa"))
        .ok_or("fixture case missing")?;
    assert!(
        above.phishing_probability >= threshold && below.phishing_probability < threshold,
        "fixture moved"
    );

    let checker = UrlRiskChecker::new(WEIGHTS.to_vec(), TOKENIZER, CONFIG, threshold)?;
    assert!(matches!(checker.assess(&above.url)?, RiskVerdict::Suspicious { .. }));
    assert_eq!(checker.assess(&below.url)?, RiskVerdict::NoWarning);
    Ok(())
}

#[test]
fn bad_files_are_errors_not_panics() {
    let no_label = String::from_utf8_lossy(CONFIG).replace("phishing", "fraud");
    let label_out_of_range = String::from_utf8_lossy(CONFIG).replace("\"1\": \"phishing\"", "\"5\": \"phishing\"");
    let cases: [(&str, Result<CandleBertModel, RiskError>); 5] = [
        (
            "config without a phishing label",
            CandleBertModel::from_bytes(WEIGHTS.to_vec(), TOKENIZER, no_label.as_bytes()),
        ),
        (
            "config whose phishing label points past the classifier",
            CandleBertModel::from_bytes(WEIGHTS.to_vec(), TOKENIZER, label_out_of_range.as_bytes()),
        ),
        (
            "config that is not JSON",
            CandleBertModel::from_bytes(WEIGHTS.to_vec(), TOKENIZER, b"not json"),
        ),
        (
            "tokenizer that is not JSON",
            CandleBertModel::from_bytes(WEIGHTS.to_vec(), b"not json", CONFIG),
        ),
        (
            "weights that are not safetensors",
            CandleBertModel::from_bytes(b"not weights".to_vec(), TOKENIZER, CONFIG),
        ),
    ];
    for (name, result) in cases {
        let expected_kind = if name.starts_with("weights") {
            matches!(result, Err(RiskError::ModelFailed { .. }))
        } else {
            matches!(result, Err(RiskError::InvalidInput { .. }))
        };
        assert!(expected_kind, "{name}: got {:?}", result.err());
    }
}

#[test]
fn checker_rejects_a_threshold_out_of_range() {
    let result = UrlRiskChecker::new(WEIGHTS.to_vec(), TOKENIZER, CONFIG, 1.5);
    assert!(
        matches!(result, Err(RiskError::InvalidScore { .. })),
        "got {:?}",
        result.err()
    );
}
