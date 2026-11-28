"""Builds the tiny random-weight BERT that the risk-candle tests run against.

The fixture has the same file layout as the real model (model.safetensors, tokenizer.json,
config.json), so the adapter code under test is the code that loads the real one. It is a few
kilobytes, carries no meaning, and needs no network: the vocabulary is built here, the weights
come from a fixed seed.

The script also scores a handful of URLs with an independent NumPy implementation of the BERT
forward pass and writes the results to expected_scores.json. The Rust tests compare Candle's
scores against these, so a wrong layer, a missing pooler or a swapped label fails a test.

Usage (from the repo root):

    pip install -r ml/quishing/requirements-fixture.txt
    python ml/quishing/make_test_fixture.py rustySDK/crates/risk-candle/tests/fixtures/tiny-bert
"""

import json
import math
import sys
from pathlib import Path

import numpy as np
from safetensors.numpy import save_file
from tokenizers import Tokenizer, models, normalizers, pre_tokenizers, processors

SEED = 20261128
HIDDEN = 16
LAYERS = 2
HEADS = 2
INTERMEDIATE = 32
MAX_POSITIONS = 128
MAX_TOKENS = 128  # must match MAX_TOKENS in the risk-candle crate
LAYER_NORM_EPS = 1e-12
SPECIALS = ["[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]"]
CHARS = list("abcdefghijklmnopqrstuvwxyz0123456789")
PUNCTUATION = list(":/.-_?=&%#@+~")

URLS = [
    "https://example.com",
    "http://paypa1-login.verify-account.xyz/secure?id=42",
    "https://kotlinlang.org/docs/multiplatform.html",
    "a" * (MAX_TOKENS - 2),  # exactly fills the token cap with [CLS] and [SEP]
    "b" * 300,  # longer than the cap, so the tokenizer truncates it
]


def build_vocab():
    vocab = {}
    for token in SPECIALS + CHARS + PUNCTUATION + ["##" + c for c in CHARS]:
        vocab[token] = len(vocab)
    return vocab


def build_tokenizer(vocab):
    tokenizer = Tokenizer(models.WordPiece(vocab, unk_token="[UNK]", max_input_chars_per_word=4096))
    tokenizer.normalizer = normalizers.BertNormalizer(lowercase=True)
    tokenizer.pre_tokenizer = pre_tokenizers.BertPreTokenizer()
    tokenizer.post_processor = processors.TemplateProcessing(
        single="[CLS] $A [SEP]",
        special_tokens=[("[CLS]", vocab["[CLS]"]), ("[SEP]", vocab["[SEP]"])],
    )
    return tokenizer


def build_weights(vocab_size, rng):
    def normal(*shape, scale=0.2):
        return (rng.standard_normal(shape) * scale).astype(np.float32)

    def ones_ish(n):
        return (1.0 + rng.standard_normal(n) * 0.05).astype(np.float32)

    w = {
        "bert.embeddings.word_embeddings.weight": normal(vocab_size, HIDDEN),
        "bert.embeddings.position_embeddings.weight": normal(MAX_POSITIONS, HIDDEN),
        "bert.embeddings.token_type_embeddings.weight": normal(2, HIDDEN),
        "bert.embeddings.LayerNorm.weight": ones_ish(HIDDEN),
        "bert.embeddings.LayerNorm.bias": normal(HIDDEN, scale=0.05),
        "bert.pooler.dense.weight": normal(HIDDEN, HIDDEN),
        "bert.pooler.dense.bias": normal(HIDDEN, scale=0.05),
        "classifier.weight": normal(2, HIDDEN, scale=0.5),
        "classifier.bias": normal(2, scale=0.05),
    }
    for layer in range(LAYERS):
        p = f"bert.encoder.layer.{layer}."
        for name in ["query", "key", "value"]:
            w[p + f"attention.self.{name}.weight"] = normal(HIDDEN, HIDDEN)
            w[p + f"attention.self.{name}.bias"] = normal(HIDDEN, scale=0.05)
        w[p + "attention.output.dense.weight"] = normal(HIDDEN, HIDDEN)
        w[p + "attention.output.dense.bias"] = normal(HIDDEN, scale=0.05)
        w[p + "attention.output.LayerNorm.weight"] = ones_ish(HIDDEN)
        w[p + "attention.output.LayerNorm.bias"] = normal(HIDDEN, scale=0.05)
        w[p + "intermediate.dense.weight"] = normal(INTERMEDIATE, HIDDEN)
        w[p + "intermediate.dense.bias"] = normal(INTERMEDIATE, scale=0.05)
        w[p + "output.dense.weight"] = normal(HIDDEN, INTERMEDIATE)
        w[p + "output.dense.bias"] = normal(HIDDEN, scale=0.05)
        w[p + "output.LayerNorm.weight"] = ones_ish(HIDDEN)
        w[p + "output.LayerNorm.bias"] = normal(HIDDEN, scale=0.05)
    return w


def config_json(vocab_size):
    return {
        "architectures": ["BertForSequenceClassification"],
        "model_type": "bert",
        "vocab_size": vocab_size,
        "hidden_size": HIDDEN,
        "num_hidden_layers": LAYERS,
        "num_attention_heads": HEADS,
        "intermediate_size": INTERMEDIATE,
        "hidden_act": "gelu",
        "hidden_dropout_prob": 0.1,
        "attention_probs_dropout_prob": 0.1,
        "max_position_embeddings": MAX_POSITIONS,
        "type_vocab_size": 2,
        "initializer_range": 0.02,
        "layer_norm_eps": LAYER_NORM_EPS,
        "pad_token_id": 0,
        "position_embedding_type": "absolute",
        "use_cache": True,
        "classifier_dropout": None,
        "id2label": {"0": "legitimate", "1": "phishing"},
        "label2id": {"legitimate": 0, "phishing": 1},
    }


# --- Independent NumPy reference of BertForSequenceClassification (float64) ---


def layer_norm(x, weight, bias):
    mean = x.mean(-1, keepdims=True)
    var = ((x - mean) ** 2).mean(-1, keepdims=True)
    return (x - mean) / np.sqrt(var + LAYER_NORM_EPS) * weight + bias


def gelu(x):
    return 0.5 * x * (1.0 + np.vectorize(math.erf)(x / math.sqrt(2.0)))


def softmax(x):
    e = np.exp(x - x.max(-1, keepdims=True))
    return e / e.sum(-1, keepdims=True)


def linear(x, w, b):
    return x @ w.T + b


def reference_phishing_probability(ids, w):
    w = {k: v.astype(np.float64) for k, v in w.items()}
    seq = len(ids)
    head_dim = HIDDEN // HEADS
    h = (
        w["bert.embeddings.word_embeddings.weight"][ids]
        + w["bert.embeddings.position_embeddings.weight"][:seq]
        + w["bert.embeddings.token_type_embeddings.weight"][0]
    )
    h = layer_norm(h, w["bert.embeddings.LayerNorm.weight"], w["bert.embeddings.LayerNorm.bias"])
    for layer in range(LAYERS):
        p = f"bert.encoder.layer.{layer}."

        def heads(name):
            x = linear(h, w[p + f"attention.self.{name}.weight"], w[p + f"attention.self.{name}.bias"])
            return x.reshape(seq, HEADS, head_dim).transpose(1, 0, 2)

        q, k, v = heads("query"), heads("key"), heads("value")
        probs = softmax(q @ k.transpose(0, 2, 1) / math.sqrt(head_dim))
        context = (probs @ v).transpose(1, 0, 2).reshape(seq, HIDDEN)
        attention = linear(context, w[p + "attention.output.dense.weight"], w[p + "attention.output.dense.bias"])
        h1 = layer_norm(attention + h, w[p + "attention.output.LayerNorm.weight"], w[p + "attention.output.LayerNorm.bias"])
        inter = gelu(linear(h1, w[p + "intermediate.dense.weight"], w[p + "intermediate.dense.bias"]))
        out = linear(inter, w[p + "output.dense.weight"], w[p + "output.dense.bias"])
        h = layer_norm(out + h1, w[p + "output.LayerNorm.weight"], w[p + "output.LayerNorm.bias"])
    pooled = np.tanh(linear(h[0], w["bert.pooler.dense.weight"], w["bert.pooler.dense.bias"]))
    logits = linear(pooled, w["classifier.weight"], w["classifier.bias"])
    return float(softmax(logits)[1])


def main(out_dir):
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(SEED)
    vocab = build_vocab()
    tokenizer = build_tokenizer(vocab)
    weights = build_weights(len(vocab), rng)

    tokenizer.save(str(out / "tokenizer.json"))
    save_file(weights, str(out / "model.safetensors"))
    (out / "config.json").write_text(json.dumps(config_json(len(vocab)), indent=2) + "\n")

    tokenizer.enable_truncation(max_length=MAX_TOKENS)
    expected = []
    for url in URLS:
        ids = tokenizer.encode(url).ids
        expected.append({"url": url, "tokens": len(ids), "phishing_probability": reference_phishing_probability(ids, weights)})
    (out / "expected_scores.json").write_text(json.dumps(expected, indent=2) + "\n")
    for row in expected:
        print(f"{row['tokens']:>4} tokens  {row['phishing_probability']:.6f}  {row['url'][:60]}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "rustySDK/crates/risk-candle/tests/fixtures/tiny-bert")
