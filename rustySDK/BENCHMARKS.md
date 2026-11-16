# Benchmark Results

Measured on Apple Silicon (M-series) via `cargo bench -p rusty-qr-core` (Criterion, 100 samples).

| Benchmark               | Measured | PRD Target | Status      |
|-------------------------|----------|------------|-------------|
| `generate_png` 256px    | ~1.1 ms  | < 10 ms    | PASS        |
| `generate_png` 1024px   | ~9.9 ms  | < 50 ms    | PASS        |
| `generate_png` 4096px   | ~175 ms  | —          | stress test |
| `decode` PNG 256px      | ~2.3 ms  | < 20 ms    | PASS        |
| `decode` PNG 1080px     | ~15.4 ms | < 100 ms   | PASS        |
| `decode_from_raw` 256px | ~2.2 ms  | < 20 ms    | synthetic only; not yet measured on device |
| `round_trip` 256px      | ~3.4 ms  | —          | end-to-end  |

The PNG PRD 5.1 targets are met with significant margin; the camera-path target awaits an on-device measurement.

## How to reproduce

```bash
cd rustySDK && cargo bench -p rusty-qr-core
```

Results are stored in `target/criterion/` with HTML reports.
