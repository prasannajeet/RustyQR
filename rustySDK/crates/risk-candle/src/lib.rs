#![forbid(unsafe_code)]
//! `rusty-qr-risk-candle` — the Candle adapter for the URL risk check.
//!
//! This crate implements the [`UrlRiskModel`] port from `rusty_qr_core::risk` with a BERT sequence
//! classifier run by [Candle](https://github.com/huggingface/candle) on the CPU, and the Hugging Face
//! `tokenizers` crate. It is the only crate in the workspace that imports either library, so swapping
//! the runtime touches this crate and nothing else.
//!
//! The model is loaded from bytes (`model.safetensors`, `tokenizer.json`, `config.json`), so it needs
//! no filesystem and runs the same way on Android, iOS and in the browser. The rules (input limits,
//! the threshold) stay in the core's risk domain; this crate only turns a URL into a score.
//!
//! [`UrlRiskChecker`] is the one entry point the FFI and wasm crates use: it loads the model and wires
//! it to a [`RiskPolicy`], so each export stays one expression.

use candle_core::{DType, Device, IndexOp, Tensor};
use candle_nn::{Linear, Module, VarBuilder};
use candle_transformers::models::bert::{BertModel, Config};
use rusty_qr_core::risk::{self, RiskError, RiskPolicy, RiskScore, RiskVerdict, UrlRiskModel};
use serde::Deserialize;
use tokenizers::{Tokenizer, TruncationParams};

/// The most tokens one URL becomes, including the `[CLS]` and `[SEP]` markers. Longer URLs are
/// truncated by the tokenizer; scoring cost grows with this number.
pub const MAX_TOKENS: usize = 128;

/// The label in `config.json`'s `id2label` that the model's phishing probability is read from.
const PHISHING_LABEL: &str = "phishing";

/// The part of a Hugging Face `config.json` this crate reads besides the BERT shape.
#[derive(Deserialize)]
struct LabelConfig {
    id2label: std::collections::HashMap<String, String>,
}

/// A BERT sequence classifier that scores URLs, run by Candle on the CPU.
pub struct CandleBertModel {
    bert: BertModel,
    pooler: Linear,
    classifier: Linear,
    tokenizer: Tokenizer,
    phishing_index: usize,
}

impl CandleBertModel {
    /// Loads a model saved in the Hugging Face `BertForSequenceClassification` layout.
    ///
    /// `config_json` must name a `phishing` label in `id2label`; its index is the class whose
    /// probability becomes the score.
    ///
    /// # Errors
    ///
    /// Returns [`RiskError::InvalidInput`] if `config_json` or `tokenizer_json` cannot be parsed, or the
    /// config has no `phishing` label, and [`RiskError::ModelFailed`] if the weights do not match the
    /// config.
    pub fn from_bytes(weights: Vec<u8>, tokenizer_json: &[u8], config_json: &[u8]) -> Result<Self, RiskError> {
        let config: Config = serde_json::from_slice(config_json).map_err(|e| invalid_input("config.json", &e))?;
        let labels: LabelConfig = serde_json::from_slice(config_json).map_err(|e| invalid_input("config.json", &e))?;
        let phishing_index = phishing_index(&labels)?;

        let mut tokenizer = Tokenizer::from_bytes(tokenizer_json).map_err(|e| invalid_input("tokenizer.json", &*e))?;
        tokenizer
            .with_truncation(Some(TruncationParams {
                max_length: MAX_TOKENS,
                ..TruncationParams::default()
            }))
            .map_err(|e| invalid_input("tokenizer.json", &*e))?;

        let device = Device::Cpu;
        let vb = VarBuilder::from_buffered_safetensors(weights, DType::F32, &device).map_err(|e| model_failed(&e))?;
        let bert = BertModel::load(vb.clone(), &config).map_err(|e| model_failed(&e))?;
        let pooler = candle_nn::linear(config.hidden_size, config.hidden_size, vb.pp("bert.pooler.dense"))
            .map_err(|e| model_failed(&e))?;
        let classifier = candle_nn::linear(config.hidden_size, labels.id2label.len(), vb.pp("classifier"))
            .map_err(|e| model_failed(&e))?;

        Ok(Self {
            bert,
            pooler,
            classifier,
            tokenizer,
            phishing_index,
        })
    }

    /// The token ids the model sees for `url`, including `[CLS]` and `[SEP]`, cut to [`MAX_TOKENS`].
    ///
    /// # Errors
    ///
    /// Returns [`RiskError::ModelFailed`] if the tokenizer fails.
    pub fn token_ids(&self, url: &str) -> Result<Vec<u32>, RiskError> {
        self.tokenizer
            .encode(url, true)
            .map(|encoding| encoding.get_ids().to_vec())
            .map_err(|e| RiskError::ModelFailed { reason: e.to_string() })
    }

    /// Runs the classifier and returns the phishing probability.
    fn phishing_probability(&self, ids: &[u32]) -> candle_core::Result<f32> {
        let input = Tensor::new(ids, &Device::Cpu)?.unsqueeze(0)?;
        let token_types = input.zeros_like()?;
        let hidden = self.bert.forward(&input, &token_types, None)?;
        // BertForSequenceClassification: the [CLS] vector goes through the pooler (dense + tanh), then
        // the classifier, then a softmax over the labels.
        let cls = hidden.i((.., 0, ..))?;
        let pooled = self.pooler.forward(&cls)?.tanh()?;
        let logits = self.classifier.forward(&pooled)?;
        let probabilities = candle_nn::ops::softmax_last_dim(&logits)?;
        probabilities.squeeze(0)?.get(self.phishing_index)?.to_scalar::<f32>()
    }
}

impl UrlRiskModel for CandleBertModel {
    fn score(&self, url: &str) -> Result<RiskScore, RiskError> {
        let ids = self.token_ids(url)?;
        let probability = self.phishing_probability(&ids).map_err(|e| model_failed(&e))?;
        RiskScore::new(probability)
    }
}

/// A loaded model wired to its policy: the risk check, ready to assess URLs.
pub struct UrlRiskChecker {
    model: CandleBertModel,
    policy: RiskPolicy,
}

impl UrlRiskChecker {
    /// Loads the model from its three files and sets the threshold from the model's evaluation.
    ///
    /// # Errors
    ///
    /// Returns [`RiskError::InvalidScore`] if `threshold` is not in `0.0..=1.0`, and any error
    /// [`CandleBertModel::from_bytes`] returns.
    pub fn new(weights: Vec<u8>, tokenizer_json: &[u8], config_json: &[u8], threshold: f32) -> Result<Self, RiskError> {
        let policy = RiskPolicy::new(threshold)?;
        let model = CandleBertModel::from_bytes(weights, tokenizer_json, config_json)?;
        Ok(Self { model, policy })
    }

    /// Assesses one scanned URL.
    ///
    /// # Errors
    ///
    /// Returns any error [`rusty_qr_core::risk::assess`] returns.
    pub fn assess(&self, url: &str) -> Result<RiskVerdict, RiskError> {
        risk::assess(url, &self.model, &self.policy)
    }
}

/// The index of the `phishing` label in `id2label`.
fn phishing_index(labels: &LabelConfig) -> Result<usize, RiskError> {
    let (index, _) = labels
        .id2label
        .iter()
        .find(|(_, label)| label.eq_ignore_ascii_case(PHISHING_LABEL))
        .ok_or_else(|| RiskError::InvalidInput {
            reason: format!("config.json has no \"{PHISHING_LABEL}\" label in id2label"),
        })?;
    let index: usize = index.parse().map_err(|e| invalid_input("config.json id2label", &e))?;
    if index >= labels.id2label.len() {
        return Err(RiskError::InvalidInput {
            reason: format!(
                "config.json labels the {PHISHING_LABEL} class {index}, but has only {} labels",
                labels.id2label.len()
            ),
        });
    }
    Ok(index)
}

fn invalid_input(what: &str, error: &dyn std::fmt::Display) -> RiskError {
    RiskError::InvalidInput {
        reason: format!("{what}: {error}"),
    }
}

fn model_failed(error: &candle_core::Error) -> RiskError {
    RiskError::ModelFailed {
        reason: error.to_string(),
    }
}
