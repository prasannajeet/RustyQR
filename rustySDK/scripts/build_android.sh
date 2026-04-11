#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT_DIR="$(cd "$SDK_DIR/.." && pwd)"
JNILIBS_DIR="$PROJECT_DIR/composeApp/src/androidMain/jniLibs"
BINDINGS_DIR="$PROJECT_DIR/composeApp/src/androidMain/kotlin/generated"

# ---------------------------------------------------------------------------
# Dependency checks — fail fast with a clear message before doing any work
# ---------------------------------------------------------------------------

MISSING=()

if ! command -v cargo &>/dev/null; then
  MISSING+=("cargo (Rust toolchain not found — install via https://rustup.rs)")
fi

if ! command -v cargo-ndk &>/dev/null; then
  MISSING+=("cargo-ndk (run: cargo install cargo-ndk)")
fi

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  MISSING+=("ANDROID_NDK_HOME (set this env var to your NDK path, e.g. ~/Library/Android/sdk/ndk/<version>)")
elif [[ ! -d "$ANDROID_NDK_HOME" ]]; then
  MISSING+=("ANDROID_NDK_HOME directory not found: $ANDROID_NDK_HOME")
fi

# Check required Rust Android targets
REQUIRED_TARGETS=("aarch64-linux-android" "armv7-linux-androideabi" "x86_64-linux-android")
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

echo "=== Building Rust FFI for Android ==="

cd "$SDK_DIR"

mkdir -p "$JNILIBS_DIR"

# Cross-compile for all 3 Android architectures
cargo ndk \
  --platform 29 \
  --target arm64-v8a \
  --target armeabi-v7a \
  --target x86_64 \
  -o "$JNILIBS_DIR" \
  build -p rusty-qr-ffi --release

echo "=== .so files placed in $JNILIBS_DIR ==="

# Generate Kotlin bindings from the arm64-v8a .so (metadata is identical across ABIs)
mkdir -p "$BINDINGS_DIR"
cargo run --bin uniffi-bindgen generate \
  --library "$JNILIBS_DIR/arm64-v8a/librusty_qr_ffi.so" \
  --language kotlin \
  --out-dir "$BINDINGS_DIR"

echo "=== Kotlin bindings generated in $BINDINGS_DIR ==="

# Format generated Kotlin via Gradle ktlint
(cd "$PROJECT_DIR" && ./gradlew :composeApp:ktlintFormat --quiet 2>/dev/null) || true

echo "=== Android build complete ==="
