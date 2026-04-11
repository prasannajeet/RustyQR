# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this
repository.

## Project Overview

Rusty-QR is a Kotlin Multiplatform (KMM) Compose Multiplatform app targeting Android and iOS. The
end goal is a QR code library written in Rust with UniFFI-generated Kotlin/Swift bindings. The KMM
skeleton is complete; the Rust core library is not yet implemented. See
`docs/rusty-qr-implementation-plan.md` for the phased build plan.

## Build Commands

```bash
# Android
./gradlew :composeApp:assembleDebug

# iOS — open iosApp/ in Xcode and run, or use xcodebuild

# Linting (all three: ktlint, detekt, swiftlint)
./gradlew :composeApp:lintAll

# Individual linters
./gradlew :composeApp:ktlintCheck
./gradlew :composeApp:detekt
./gradlew :composeApp:swiftlint

# Auto-fix Kotlin formatting
./gradlew :composeApp:ktlintFormat

# Install git hooks (also runs automatically on preBuild/clean)
./gradlew :composeApp:installGitHooks

# Rust formatting (run from rustySDK/)
cd rustySDK && cargo fmt          # auto-fix
cd rustySDK && cargo fmt --check  # check only (used in pre-commit)
```

## Architecture

- **`composeApp/src/commonMain/`** — Shared Kotlin code for both platforms. Uses Compose
  Multiplatform for UI.
- **`composeApp/src/androidMain/`** — Android-specific actuals (`Platform.android.kt`,
  `MainActivity.kt`).
- **`composeApp/src/iosMain/`** — iOS-specific actuals (`Platform.ios.kt`, `MainViewController.kt`).
- **`iosApp/`** — Swift entry point wrapping the KMM ComposeApp framework via
  `UIViewControllerRepresentable`.
- **`config/detekt/detekt.yml`** — Detekt static analysis rules.
- **`.husky/`** — Git hook scripts (installed to `.git/hooks/` by Gradle task).

Package: `com.p2.apps.rustyqr`

## KMM Conventions

- **expect/actual files** follow the naming pattern `Platform.kt` (expect), `Platform.android.kt` /
  `Platform.ios.kt` (actuals). Do NOT rename actual files to match the class name —
  `MatchingDeclarationName` is disabled in detekt for this reason.
- Composable factory functions (uppercase names like `MainViewController()`) use
  `@Suppress("ktlint:standard:function-naming")` where needed.
- The iOS framework is named `ComposeApp` and is a static framework.

## Linting Rules

- **ktlint**: Android mode, configured via `composeApp/.editorconfig`. Continuation indent is 4 (not
  8) to avoid conflicts with detekt. `@Composable` functions are exempt from function naming rules.
- **detekt**: Max line length 120. `MagicNumber` and `WildcardImport` are disabled. Function naming
  allows uppercase start for Compose.
- **SwiftLint**: Runs on `iosApp/` only. `trailing_whitespace` and `line_length` disabled.
- **rustfmt**: Configured via `rustySDK/rustfmt.toml`. Edition 2024, max line width 120 (matches
  detekt). `use_field_init_shorthand` and `use_try_shorthand` enabled.
- All three Kotlin/Swift linters run as a pre-commit hook via `lintAll`. Rust formatting runs as a
  separate `cargo fmt --check` step in the same hook.

## Git Hooks

- **pre-commit**: Runs `lintAll` on staged `.kt`/`.kts`/`.swift` files; runs `cargo fmt --check`
  on staged `.rs` files. Skips if no relevant files are staged.
- **commit-msg**: Enforces conventional commits format (`type(scope): message`). Valid types: feat,
  fix, docs, style, refactor, perf, test, build, ci, chore, revert.

## Key Versions

- Kotlin: 2.3.20
- Compose Multiplatform: 1.11.0-beta01
- Android compileSdk/targetSdk: 36, minSdk: 29
- JVM target: 11
- Gradle configuration cache: enabled
