#!/usr/bin/env bash
#
# Rusty-QR developer onboarding — checks (and optionally installs) every tool
# required to build the project on Android and iOS. Run this once after cloning.
#
# Usage:
#   scripts/bootstrap.sh            # check, prompt to auto-install missing tools
#   scripts/bootstrap.sh --check    # report only, never install, non-zero exit if anything missing
#   scripts/bootstrap.sh --yes      # non-interactive, auto-install everything possible
#
set -euo pipefail

MODE="interactive"
for arg in "$@"; do
    case "$arg" in
        --check) MODE="check" ;;
        --yes|-y) MODE="yes" ;;
        --help|-h)
            sed -n '2,/^set -euo/p' "$0" | sed 's/^#//; s/^ //'
            exit 0
            ;;
        *) echo "unknown flag: $arg" >&2; exit 2 ;;
    esac
done

# ---------------------------------------------------------------------------
# Output helpers
# ---------------------------------------------------------------------------
if [[ -t 1 ]]; then
    BOLD="$(tput bold 2>/dev/null || true)"
    DIM="$(tput dim 2>/dev/null || true)"
    RED="$(tput setaf 1 2>/dev/null || true)"
    GREEN="$(tput setaf 2 2>/dev/null || true)"
    YELLOW="$(tput setaf 3 2>/dev/null || true)"
    BLUE="$(tput setaf 4 2>/dev/null || true)"
    RESET="$(tput sgr0 2>/dev/null || true)"
else
    BOLD=""; DIM=""; RED=""; GREEN=""; YELLOW=""; BLUE=""; RESET=""
fi

OK=()
MISSING=()
INSTALLED=()
SKIPPED=()

section() { echo "${BOLD}${BLUE}▸ $1${RESET}"; }
pass()    { echo "  ${GREEN}✓${RESET} $1"; OK+=("$1"); }
fail()    { echo "  ${RED}✗${RESET} $1"; MISSING+=("$1"); }
warn()    { echo "  ${YELLOW}!${RESET} $1"; }
installed() { echo "  ${GREEN}↓ installed${RESET} $1"; INSTALLED+=("$1"); }
skipped() { echo "  ${DIM}- skipped${RESET} $1"; SKIPPED+=("$1"); }

# ---------------------------------------------------------------------------
# Platform detection
# ---------------------------------------------------------------------------
UNAME="$(uname -s)"
IS_MACOS=false
IS_LINUX=false
case "$UNAME" in
    Darwin) IS_MACOS=true ;;
    Linux)  IS_LINUX=true ;;
    *) echo "${RED}Unsupported host OS: $UNAME${RESET}" >&2; exit 1 ;;
esac

echo "${BOLD}Rusty-QR bootstrap${RESET} — host: $UNAME, mode: $MODE"
echo

# ---------------------------------------------------------------------------
# Install-prompt helper
# ---------------------------------------------------------------------------
# $1 = human name, $2 = install command (string)
maybe_install() {
    local name="$1"
    local cmd="$2"
    case "$MODE" in
        check) return 1 ;;
        yes)
            echo "  ${DIM}→ $cmd${RESET}"
            bash -c "$cmd"
            installed "$name"
            return 0
            ;;
        interactive)
            read -r -p "  install $name now? [Y/n] " reply
            if [[ -z "$reply" || "$reply" =~ ^[Yy]$ ]]; then
                echo "  ${DIM}→ $cmd${RESET}"
                bash -c "$cmd"
                installed "$name"
                return 0
            else
                skipped "$name"
                return 1
            fi
            ;;
    esac
}

# ---------------------------------------------------------------------------
# Homebrew (macOS only) — prereq for several other tools
# ---------------------------------------------------------------------------
if $IS_MACOS; then
    section "Homebrew"
    if command -v brew &>/dev/null; then
        pass "brew $(brew --version | head -1 | awk '{print $2}')"
    else
        fail "Homebrew not installed"
        warn "Install manually: /bin/bash -c \"\$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)\""
        warn "Cannot auto-install Homebrew (requires sudo + manual PATH setup). Re-run after installing."
    fi
    echo
fi

# ---------------------------------------------------------------------------
# Rust toolchain
# ---------------------------------------------------------------------------
section "Rust toolchain"
if command -v rustc &>/dev/null && command -v cargo &>/dev/null; then
    pass "rustc $(rustc --version | awk '{print $2}')"
    pass "cargo $(cargo --version | awk '{print $2}')"
else
    fail "rustup / cargo not installed"
    maybe_install "rustup" \
        "curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y" \
        || true
    # shellcheck disable=SC1091
    [[ -f "$HOME/.cargo/env" ]] && source "$HOME/.cargo/env"
fi

# Android Rust targets
if command -v rustup &>/dev/null; then
    ANDROID_TARGETS=(aarch64-linux-android armv7-linux-androideabi x86_64-linux-android)
    INSTALLED_TARGETS="$(rustup target list --installed 2>/dev/null || true)"
    for t in "${ANDROID_TARGETS[@]}"; do
        if grep -q "^$t$" <<<"$INSTALLED_TARGETS"; then
            pass "rust target $t"
        else
            fail "rust target $t missing"
            maybe_install "rust target $t" "rustup target add $t" || true
        fi
    done

    # iOS Rust targets — macOS only
    if $IS_MACOS; then
        IOS_TARGETS=(aarch64-apple-ios aarch64-apple-ios-sim)
        for t in "${IOS_TARGETS[@]}"; do
            if grep -q "^$t$" <<<"$INSTALLED_TARGETS"; then
                pass "rust target $t"
            else
                fail "rust target $t missing"
                maybe_install "rust target $t" "rustup target add $t" || true
            fi
        done
    fi
fi

# cargo-ndk
if command -v cargo-ndk &>/dev/null; then
    pass "cargo-ndk $(cargo ndk --version 2>/dev/null | awk '{print $2}')"
else
    fail "cargo-ndk missing"
    maybe_install "cargo-ndk" "cargo install cargo-ndk" || true
fi
echo

# ---------------------------------------------------------------------------
# JDK
# ---------------------------------------------------------------------------
section "Java (JDK 11+)"
if command -v java &>/dev/null; then
    JAVA_VER="$(java -version 2>&1 | head -1 | awk -F\" '{print $2}')"
    JAVA_MAJOR="$(echo "$JAVA_VER" | awk -F. '{ if ($1=="1") print $2; else print $1 }')"
    if [[ "$JAVA_MAJOR" =~ ^[0-9]+$ ]] && (( JAVA_MAJOR >= 11 )); then
        pass "java $JAVA_VER"
    else
        fail "java $JAVA_VER — need 11+"
        if $IS_MACOS; then
            maybe_install "temurin JDK 21" "brew install --cask temurin@21" || true
        else
            warn "install OpenJDK 11+ via your distro package manager"
        fi
    fi
else
    fail "java not installed"
    if $IS_MACOS; then
        maybe_install "temurin JDK 21" "brew install --cask temurin@21" || true
    else
        warn "install OpenJDK 11+ via your distro package manager"
    fi
fi
echo

# ---------------------------------------------------------------------------
# Android SDK + NDK
# ---------------------------------------------------------------------------
section "Android SDK / NDK"
ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$ANDROID_SDK" ]]; then
    if $IS_MACOS && [[ -d "$HOME/Library/Android/sdk" ]]; then
        ANDROID_SDK="$HOME/Library/Android/sdk"
    elif [[ -d "$HOME/Android/Sdk" ]]; then
        ANDROID_SDK="$HOME/Android/Sdk"
    fi
fi

if [[ -n "$ANDROID_SDK" && -d "$ANDROID_SDK" ]]; then
    pass "Android SDK at $ANDROID_SDK"
else
    fail "Android SDK not found"
    warn "Install Android Studio (https://developer.android.com/studio) then open SDK Manager to install platform-tools + an SDK."
fi

if [[ -n "${ANDROID_NDK_HOME:-}" && -d "$ANDROID_NDK_HOME" ]]; then
    pass "ANDROID_NDK_HOME → $ANDROID_NDK_HOME"
elif [[ -n "$ANDROID_SDK" && -d "$ANDROID_SDK/ndk" ]]; then
    LATEST_NDK="$(ls "$ANDROID_SDK/ndk" 2>/dev/null | sort -V | tail -1)"
    if [[ -n "$LATEST_NDK" ]]; then
        warn "NDK found at $ANDROID_SDK/ndk/$LATEST_NDK but ANDROID_NDK_HOME is not exported"
        warn "Add to your shell rc: export ANDROID_NDK_HOME=\"$ANDROID_SDK/ndk/$LATEST_NDK\""
        MISSING+=("ANDROID_NDK_HOME env var")
    else
        fail "Android NDK not installed"
        warn "Install via Android Studio → SDK Manager → SDK Tools → NDK (Side by side)"
    fi
else
    fail "Android NDK not found"
    warn "Install via Android Studio → SDK Manager → SDK Tools → NDK (Side by side)"
fi
echo

# ---------------------------------------------------------------------------
# iOS toolchain — macOS only
# ---------------------------------------------------------------------------
if $IS_MACOS; then
    section "iOS toolchain"

    if command -v xcodebuild &>/dev/null; then
        XCODE_VER="$(xcodebuild -version 2>/dev/null | head -1 | awk '{print $2}')"
        pass "Xcode $XCODE_VER"
        if [[ "$XCODE_VER" != 26.* ]]; then
            warn "project pins Xcode 26.x — you have $XCODE_VER; builds may drift"
        fi
    else
        fail "Xcode not installed"
        warn "Install Xcode 26.x from the Mac App Store, then run: sudo xcode-select --install"
    fi

    for tool in xcodegen swiftlint swiftformat; do
        if command -v "$tool" &>/dev/null; then
            pass "$tool $($tool --version 2>/dev/null | head -1)"
        else
            fail "$tool missing"
            if command -v brew &>/dev/null; then
                maybe_install "$tool" "brew install $tool" || true
            else
                warn "install Homebrew first, then: brew install $tool"
            fi
        fi
    done
    echo
else
    section "iOS toolchain"
    skipped "iOS build requires macOS — skipping Xcode / xcodegen / swiftlint / swiftformat checks"
    echo
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo "${BOLD}Summary${RESET}"
echo "  ${GREEN}✓ ${#OK[@]}${RESET} ready · ${GREEN}↓ ${#INSTALLED[@]}${RESET} installed · ${YELLOW}- ${#SKIPPED[@]}${RESET} skipped · ${RED}✗ ${#MISSING[@]}${RESET} still missing"
echo

if (( ${#MISSING[@]} > 0 )); then
    echo "${RED}${BOLD}Still missing:${RESET}"
    for m in "${MISSING[@]}"; do echo "  - $m"; done
    echo
    echo "Re-run after resolving, or run with ${BOLD}--yes${RESET} to auto-install everything possible."
    exit 1
fi

echo "${GREEN}${BOLD}All set.${RESET} Build with:"
echo "  ./gradlew :composeApp:buildRustAndroid :composeApp:assembleDebug   # Android"
if $IS_MACOS; then
    echo "  ./gradlew :composeApp:buildRustIos                                 # iOS"
fi
