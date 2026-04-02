---
name: verify-phase
description: Run all gate verification checks for a project phase (0-8) and report pass/fail.
---

# Verify Phase Gate

## Input

Argument: phase number (0-8).

## Phase Gates

### Phase 0: Toolchain Setup

```bash
rustc --version | grep -E '1\.(7[0-9]|[89][0-9]|[0-9]{3})'
cargo ndk --version
rustup target list --installed | grep -c -E 'android|ios' | grep -q 5
echo $ANDROID_NDK_HOME | grep -q 'ndk'
test -f rustSDK/.cargo/config.toml
cd rustSDK && cargo check --workspace
grep -q 'rustSDK/target' .gitignore
```

### Phase 1: Rust Core — Encoder

```bash
cd rustSDK && cargo test -p rusty-qr-core -- encoder
cd rustSDK && cargo clippy --workspace -- -D warnings
```

### Phase 2: Rust Core — Decoder + Round-Trip

```bash
cd rustSDK && cargo test -p rusty-qr-core
cd rustSDK && cargo test -p rusty-qr-core -- round_trip
```

### Phase 3: FFI Crate + UniFFI Bindings

```bash
cd rustSDK && cargo build -p rusty-qr-ffi
cd rustSDK && cargo run -p uniffi-bindgen generate --library target/debug/librusty_qr_ffi.dylib --language kotlin --out-dir /tmp/uniffi-check-kt
cd rustSDK && cargo run -p uniffi-bindgen generate --library target/debug/librusty_qr_ffi.dylib --language swift --out-dir /tmp/uniffi-check-swift
test -f /tmp/uniffi-check-kt/rusty_qr_ffi.kt
test -f /tmp/uniffi-check-swift/rusty_qr_ffi.swift
```

### Phase 4: Android Build Pipeline

```bash
test -f composeApp/src/androidMain/jniLibs/arm64-v8a/librusty_qr_ffi.so
test -f composeApp/src/androidMain/jniLibs/armeabi-v7a/librusty_qr_ffi.so
test -f composeApp/src/androidMain/jniLibs/x86_64/librusty_qr_ffi.so
./gradlew :composeApp:compileDebugKotlinAndroid
```

### Phase 5: KMM Integration — Android UI

```bash
./gradlew :composeApp:lintAll
./gradlew :composeApp:assembleDebug
```

### Phase 6: iOS Build Pipeline

```bash
test -d iosApp/Frameworks/RustyQR.xcframework
ls iosApp/Frameworks/RustyQR.xcframework/ | grep -q ios-arm64
```

### Phase 7: KMM Integration — iOS UI

```bash
swiftlint lint --path iosApp/
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 16' build 2>&1 | tail -5
```

### Phase 8: Polish & Documentation

```bash
cd rustSDK && cargo test --workspace
cd rustSDK && cargo clippy --workspace -- -D warnings
make -C rustSDK android
./gradlew :composeApp:assembleDebug
make -C rustSDK ios
ls -la rustSDK/target/aarch64-linux-android/release/librusty_qr_ffi.so | awk '{print $5}' # should be < 3MB
```

## Instructions

1. Parse the phase number from `$ARGUMENTS`
2. Run each gate check command for that phase sequentially
3. Track pass/fail for each check
4. Report results:

```
## Phase <N> Gate: <phase name>

| Check | Status |
|-------|--------|
| <description> | PASS / FAIL |
| ... | ... |

**Result: <N>/<total> checks passed — GATE OPEN / GATE BLOCKED**
```

5. If any check fails, show the error output to help diagnose
