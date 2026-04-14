#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT_DIR="$(cd "$SDK_DIR/.." && pwd)"
GENERATED_DIR="$PROJECT_DIR/iosApp/generated"
HEADERS_DIR="$GENERATED_DIR/headers"
FRAMEWORKS_DIR="$PROJECT_DIR/iosApp/Frameworks"

# ---------------------------------------------------------------------------
# Dependency checks — fail fast with a clear message before doing any work
# ---------------------------------------------------------------------------

MISSING=()

if ! command -v cargo &>/dev/null; then
  MISSING+=("cargo (Rust toolchain not found — install via https://rustup.rs)")
fi

if ! command -v xcodebuild &>/dev/null; then
  MISSING+=("xcodebuild (Xcode not found — install from the App Store)")
fi

# Check required Rust iOS targets
REQUIRED_TARGETS=("aarch64-apple-ios" "aarch64-apple-ios-sim")
for target in "${REQUIRED_TARGETS[@]}"; do
  if ! rustup target list --installed 2>/dev/null | grep -q "^$target$"; then
    MISSING+=("Rust target $target (run: rustup target add $target)")
  fi
done

if [[ ${#MISSING[@]} -gt 0 ]]; then
  echo "ERROR: Missing dependencies:" >&2
  for dep in "${MISSING[@]}"; do
    echo "  - $dep" >&2
  done
  exit 1
fi

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------

echo "=== Building Rust FFI for iOS ==="

cd "$SDK_DIR"

# Cross-compile for both iOS targets
echo "--- Compiling for aarch64-apple-ios (device) ---"
cargo build -p rusty-qr-ffi --release --target aarch64-apple-ios

echo "--- Compiling for aarch64-apple-ios-sim (simulator) ---"
cargo build -p rusty-qr-ffi --release --target aarch64-apple-ios-sim

echo "=== .a files built ==="

# Generate Swift bindings from the device .a (metadata is identical across targets)
mkdir -p "$GENERATED_DIR"
cargo run --bin uniffi-bindgen generate \
  --library target/aarch64-apple-ios/release/librusty_qr_ffi.a \
  --language swift \
  --out-dir "$GENERATED_DIR"

echo "=== Swift bindings generated in $GENERATED_DIR ==="

# Prepare headers directory for XCFramework creation
# xcodebuild -create-xcframework -headers expects ONLY .h and modulemap files
rm -rf "$HEADERS_DIR"
mkdir -p "$HEADERS_DIR"
cp "$GENERATED_DIR/RustyQrFFIFFI.h" "$HEADERS_DIR/"
cp "$GENERATED_DIR/RustyQrFFIFFI.modulemap" "$HEADERS_DIR/module.modulemap"

# Create XCFramework (delete existing first — xcodebuild won't overwrite)
rm -rf "$FRAMEWORKS_DIR/RustyQR.xcframework"
mkdir -p "$FRAMEWORKS_DIR"
xcodebuild -create-xcframework \
  -library target/aarch64-apple-ios/release/librusty_qr_ffi.a \
  -headers "$HEADERS_DIR" \
  -library target/aarch64-apple-ios-sim/release/librusty_qr_ffi.a \
  -headers "$HEADERS_DIR" \
  -output "$FRAMEWORKS_DIR/RustyQR.xcframework"

echo "=== XCFramework created at $FRAMEWORKS_DIR/RustyQR.xcframework ==="
echo "=== iOS build complete ==="
echo "Note: run './gradlew :composeApp:generateXcodeProject' to regenerate iosApp.xcodeproj"
