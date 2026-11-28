# Benchmark Results

Measured on Apple Silicon (M-series) via `cargo bench -p rusty-qr-core` (Criterion, 100 samples). Only the `content_fits` and `generate_png` rows were re-measured; the other rows keep their earlier values. The `generate_png` rows were measured on a heavily loaded machine, so they are upper bounds: rendering at whole pixels per module replaced a render at 8 px per module followed by a resize.

| Benchmark                                                   | Measured | Target          | Status                                     |
|-------------------------------------------------------------|----------|-----------------|--------------------------------------------|
| `generate_png` 256px                                        | ~0.5 ms  | < 10 ms         | PASS                                       |
| `generate_png` 1024px                                       | ~1.3 ms  | < 50 ms         | PASS                                       |
| `generate_png` 4096px                                       | ~9 ms    | —               | stress test                                |
| `decode` PNG 256px                                          | ~2.3 ms  | < 20 ms         | PASS                                       |
| `decode` PNG 1080px                                         | ~15.4 ms | < 100 ms        | PASS                                       |
| `decode_from_raw` 256px                                     | ~2.2 ms  | < 20 ms         | synthetic only; not yet measured on device |
| `round_trip` 256px                                          | ~3.4 ms  | —               | end-to-end                                 |
| `content_fits` 2331 B byte-mode                             | ~15 µs   | < 1 ms (design) | PASS                                       |
| `content_fits` 5596 digits (numeric)                        | ~20 µs   | < 1 ms (design) | PASS                                       |
| `content_fits` 2331 B mixed whole byte segment (`x12345`)   | ~54 µs   | < 1 ms (design) | PASS                                       |
| `content_fits` 2328 B Japanese text (UTF-8 + ECI 26)        | ~8 µs    | < 1 ms (design) | PASS                                       |
| `content_fits` 2330 B alternating `é12345` (UTF-8 + ECI 26) | ~48 µs   | < 1 ms (design) | PASS                                       |
| `content_fits` 7089 B non-ASCII overflow (`é1`)             | ~95 µs   | < 1 ms (design) | PASS                                       |
| `content_fits` 7000 B overflow                              | ~23 µs   | < 1 ms (design) | PASS                                       |
| `content_fits` 1 MB paste, size cap [^cap]                  | ~65 ns   | < 1 ms (design) | PASS                                       |

The PNG PRD 5.1 targets are met with significant margin; the camera-path target awaits an on-device measurement.

## How to reproduce

```bash
cd rustySDK && cargo bench -p rusty-qr-core
```

Results are stored in `target/criterion/` with HTML reports.

[^cap]: Times the Rust call only. It does not include copying the 1 MB UTF-8 string across the FFI boundary.

## URL risk check (`rusty-qr-risk-candle`)

Measured on an x86_64 Linux host with `cargo bench -p rusty-qr-risk-candle --bench risk_benchmarks` (Criterion,
1 s warm-up, 2 s measurement), against the tiny random-weight test fixture (2 layers, hidden size 16). These numbers
show the overhead around the model (tokenizer, tensor set-up, Candle dispatch), not the cost of the real model.
Phone timings with the real model are still to be measured.

| Benchmark                      | Measured | Target          | Status                         |
|--------------------------------|----------|-----------------|--------------------------------|
| `assess` `https://example.com` | ~0.66 ms | < 50 ms (phone) | fixture only                   |
| `assess` 53-token URL          | ~0.71 ms | < 50 ms (phone) | fixture only                   |
| `load model` (fixture)         | ~0.13 ms | —               | fixture only                   |

To measure the real model, point `RUSTYQR_RISK_MODEL_DIR` at the folder that holds its `model.safetensors`,
`tokenizer.json` and `config.json`:

```bash
cd rustySDK && RUSTYQR_RISK_MODEL_DIR=/path/to/model cargo bench -p rusty-qr-risk-candle --bench risk_benchmarks
```

### Library size

Candle and tokenizers make the FFI library larger. Stripped `librusty_qr_ffi.so` on x86_64 Linux, host build:

| Build                                              | Before   | After    | Added    |
|----------------------------------------------------|----------|----------|----------|
| `--release`                                        | 1.29 MB  | 7.96 MB  | +6.67 MB |
| `--release`, fat LTO, `codegen-units=1`, `opt-level="z"` | 0.96 MB  | 4.58 MB  | +3.62 MB |

Both are above the 3 MB per architecture target. Android and iOS sizes are still to be measured.
