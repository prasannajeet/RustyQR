# Quishing model tooling

Python scripts for the on-device URL risk check ("quishing" check). They run on a developer machine
and never ship in the apps.

## Test fixture

`make_test_fixture.py` builds the tiny random-weight BERT in
`rustySDK/crates/risk-candle/tests/fixtures/tiny-bert/`. It has the same files as the real model
(`model.safetensors`, `tokenizer.json`, `config.json`), so the adapter tests load it with the same code.
It also writes `expected_scores.json`, the scores an independent NumPy implementation of the model
gives, which the Rust tests compare against Candle.

```bash
pip install -r ml/quishing/requirements-fixture.txt
python ml/quishing/make_test_fixture.py rustySDK/crates/risk-candle/tests/fixtures/tiny-bert
```

The output is deterministic (fixed seed), so rerunning it changes no files unless the script changes.
It needs no network and no API key.
