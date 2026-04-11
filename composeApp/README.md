# Android Build: From Rust to APK

This document explains how the Rust QR library gets compiled, wrapped in Kotlin bindings, and packaged into the Android app. If you've never used `make` or worked with native bindings before, start here.

---

## What Problem Does This Solve?

The QR code logic is written in **Rust**, but Android apps are written in **Kotlin**. Kotlin can't call Rust directly — they're different languages running in different runtimes. We need two things:

1. **A compiled Rust library** (`.so` file) that Android can load at runtime
2. **Generated Kotlin code** that knows how to call the functions inside that library

The build pipeline automates both steps. You have two ways to run it:

---

## Building the App

### Option A: Gradle all the way (recommended)

```bash
# Compile Rust + generate Kotlin bindings
./gradlew :composeApp:buildRustAndroid

# Build the Android APK (picks up everything from above automatically)
./gradlew :composeApp:assembleDebug

# Or chain both in one line:
./gradlew :composeApp:buildRustAndroid :composeApp:assembleDebug
```

`buildRustAndroid` is a Gradle task that calls `make android` under the hood. You stay in Gradle-land and don't need to `cd` anywhere.

### Option B: Make directly

```bash
cd rustySDK && make android
./gradlew :composeApp:assembleDebug
```

Same result, just invokes `make` yourself. Useful when you want to see the raw Rust build output.

### When do I need to rebuild Rust?

Only when you change Rust code in `rustySDK/`. If you're only editing Kotlin or UI, skip `buildRustAndroid` and just run `assembleDebug` — it's much faster.

Everything below explains what happens inside these commands.

---

## What is `make`?

`make` is a build automation tool that's been around since 1976. It reads a file called `Makefile` and runs the commands defined for each "target". Think of it as a script runner with named entry points:

```makefile
# rustySDK/Makefile

android:              # ← this is a "target" (the name you pass to make)
    ./scripts/build_android.sh    # ← this is what runs when you type "make android"

test:
    cargo test --workspace
    cargo clippy --workspace -- -D warnings
    cargo fmt --check

clean:
    cargo clean
    rm -rf ../composeApp/src/androidMain/jniLibs
    rm -rf ../composeApp/src/androidMain/kotlin/generated
```

| Command | What it does |
|---------|-------------|
| `make android` | Cross-compiles Rust for Android + generates Kotlin bindings |
| `make test` | Runs all Rust tests, linter, and format checker |
| `make clean` | Deletes all compiled artifacts (start fresh) |
| `make` | Runs `make all` (= `test` then `android`) |

---

## What Happens Inside `make android`

`make android` calls `rustySDK/scripts/build_android.sh`, which does three things in order:

### 1. Check dependencies

The script verifies your machine has everything installed before doing any work. If anything is missing, it tells you exactly what to install:

```
ERROR: Missing dependencies:
  - cargo-ndk (run: cargo install cargo-ndk)
  - ANDROID_NDK_HOME (set this env var to your NDK path)
  - Rust target aarch64-linux-android (run: rustup target add aarch64-linux-android)
```

### 2. Cross-compile Rust for Android

The Rust compiler normally produces binaries for your Mac. To produce binaries that run on Android phones, we need a **cross-compiler** — it runs on your Mac but outputs ARM/x86 code that Android understands.

`cargo-ndk` handles this. It invokes the Rust compiler three times, once per CPU architecture:

| Architecture | Who uses it | Output file |
|-------------|------------|-------------|
| `arm64-v8a` | All modern Android phones (64-bit ARM) | `jniLibs/arm64-v8a/librusty_qr_ffi.so` |
| `armeabi-v7a` | Older 32-bit ARM devices | `jniLibs/armeabi-v7a/librusty_qr_ffi.so` |
| `x86_64` | Android emulator on your Mac | `jniLibs/x86_64/librusty_qr_ffi.so` |

The `.so` files (shared objects) are native libraries — the equivalent of a `.dll` on Windows or a `.dylib` on Mac.

All `.so` files are built with **16KB ELF page alignment** (`align 2**14`), compliant with [Android's 16KB page size requirement](https://developer.android.com/guide/practices/page-sizes) effective November 2025. This is handled automatically by NDK 28+ (we use NDK 30) — no extra linker flags needed. AGP 9.1 handles the zip alignment at APK packaging time.

### 3. Generate Kotlin bindings

The `.so` file contains Rust functions, but Kotlin doesn't know their names, parameter types, or return types. **UniFFI** solves this by reading metadata embedded in the `.so` and generating a Kotlin file that wraps every Rust function in a Kotlin-friendly API:

```
Rust function:  generate_png(content: &str, size: u32) -> Result<Vec<u8>, QrError>
                              ↓ uniffi-bindgen generates ↓
Kotlin function: fun generatePng(content: String, size: UInt): List<UByte>
                 (throws FfiQrException on error)
```

The generated file lands at:
```
composeApp/src/androidMain/kotlin/generated/com/p2/apps/rustyqr/rust/rusty_qr_ffi.kt
```

---

## The Full Pipeline

```mermaid
sequenceDiagram
    participant Dev as Developer
    participant Gradle as buildRustAndroid
    participant Make as make android
    participant Script as build_android.sh
    participant CNDK as cargo-ndk
    participant Rustc as Rust cross-compiler
    participant UB as uniffi-bindgen
    participant Gradle as ./gradlew assembleDebug

    Dev->>Gradle: ./gradlew buildRustAndroid
    Gradle->>Make: make android (from rustySDK/)
    Make->>Script: ./scripts/build_android.sh

    Note over Script: Step 1: Check dependencies
    Script->>Script: cargo installed?
    Script->>Script: cargo-ndk installed?
    Script->>Script: ANDROID_NDK_HOME set?
    Script->>Script: Rust Android targets installed?

    Note over Script: Step 2: Cross-compile Rust
    Script->>CNDK: cargo ndk build --release

    CNDK->>Rustc: compile for arm64-v8a
    Rustc-->>CNDK: librusty_qr_ffi.so

    CNDK->>Rustc: compile for armeabi-v7a
    Rustc-->>CNDK: librusty_qr_ffi.so

    CNDK->>Rustc: compile for x86_64
    Rustc-->>CNDK: librusty_qr_ffi.so

    Note over Script: Step 3: Generate Kotlin bindings
    Script->>UB: read .so metadata, emit Kotlin
    UB-->>Script: rusty_qr_ffi.kt

    Script-->>Dev: Done!

    Note over Dev,Gradle: Separate step
    Dev->>Gradle: ./gradlew assembleDebug
    Note over Gradle: Picks up .so from jniLibs/<br/>Picks up .kt from kotlin/generated/<br/>Compiles Kotlin + packages APK
    Gradle-->>Dev: app-debug.apk
```

---

## How Gradle Picks Up the Artifacts

Gradle doesn't need any special configuration to find the outputs. It uses **convention-based source directories** — if files are in the right place, Gradle includes them automatically:

```mermaid
graph TB
    subgraph "make android produces these"
        SO1["composeApp/src/androidMain/<br/>jniLibs/arm64-v8a/<br/>librusty_qr_ffi.so"]
        SO2["composeApp/src/androidMain/<br/>jniLibs/armeabi-v7a/<br/>librusty_qr_ffi.so"]
        SO3["composeApp/src/androidMain/<br/>jniLibs/x86_64/<br/>librusty_qr_ffi.so"]
        KT["composeApp/src/androidMain/<br/>kotlin/generated/<br/>rusty_qr_ffi.kt"]
    end

    subgraph "Gradle convention directories"
        JNIDIR["jniLibs/ = native libraries<br/>(packaged into APK as-is)"]
        KTDIR["kotlin/ = Kotlin source files<br/>(compiled by Kotlin compiler)"]
    end

    subgraph "Runtime on device"
        JNA["JNA library<br/>(declared in build.gradle.kts)"]
        LOAD["System.loadLibrary<br/>loads the matching .so<br/>for the device's CPU"]
        CALL["Generated Kotlin calls<br/>Rust functions via JNA"]
    end

    SO1 --> JNIDIR
    SO2 --> JNIDIR
    SO3 --> JNIDIR
    KT --> KTDIR

    JNIDIR -->|"APK packages all ABIs"| LOAD
    KTDIR -->|"compiled into classes.dex"| CALL
    JNA --> CALL
    LOAD --> CALL
```

**`jniLibs/`** is a magic directory name for Android Gradle Plugin — any `.so` files inside `jniLibs/<abi>/` are automatically copied into the APK. The device extracts only the `.so` matching its own CPU when the app is installed.

**`kotlin/`** (or `java/`) under a source set is where Gradle looks for source files to compile. The generated `rusty_qr_ffi.kt` is treated like any other Kotlin file.

**JNA** (Java Native Access) is a runtime library declared in `build.gradle.kts`. The generated Kotlin code uses JNA to load `librusty_qr_ffi.so` and call its functions. Without JNA, the Kotlin code would compile but crash at runtime.

---

## How UniFFI Metadata Works

You might wonder: how does `uniffi-bindgen` know what Kotlin code to generate?

The answer is **proc-macros** — Rust compile-time code generators. When the Rust FFI crate is compiled, annotations like `#[uniffi::export]` expand into metadata that gets embedded directly into the `.so` binary:

```mermaid
graph LR
    subgraph "Rust source (what you write)"
        A["#[uniffi::export]<br/>fn generate_png(content: String, size: u32)<br/>-> Result<Vec<u8>, FfiQrError>"]
    end

    subgraph "Compile time"
        B["uniffi proc-macro<br/>reads the function signature"]
        C["Embeds metadata section<br/>into the .so binary"]
    end

    subgraph "uniffi-bindgen (after compile)"
        D["Reads metadata<br/>from .so file"]
        E["Generates Kotlin:<br/>fun generatePng(content: String, size: UInt): List<UByte>"]
    end

    A --> B
    B --> C
    C -->|".so file"| D
    D --> E
```

This means the generated Kotlin is always in sync with the Rust code — if you change a function signature in Rust, re-running `make android` regenerates the Kotlin to match.

---

## Why Three `.so` Files but One `.kt` File?

**Three `.so` files** because each CPU architecture needs its own machine code. ARM code can't run on x86 and vice versa.

**One `.kt` file** because the Kotlin code is architecture-independent — it calls Rust functions by name (e.g., "generate_png"), and JNA resolves those names against whichever `.so` was loaded on the device. The UniFFI metadata in all three `.so` files is identical (same function names, same types), so any one can be used as the source for code generation.

---

## First-Time Setup

If the dependency check fails, run this once:

```bash
# 1. Install Rust
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh

# 2. Add Android cross-compilation targets
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android

# 3. Install cargo-ndk (the cross-compilation wrapper)
cargo install cargo-ndk

# 4. Set ANDROID_NDK_HOME (add to your ~/.zshrc or ~/.bashrc)
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/$(ls $HOME/Library/Android/sdk/ndk | tail -1)"
```

After that, every build is:

```bash
./gradlew :composeApp:buildRustAndroid :composeApp:assembleDebug
```

---

## Cleaning Up

```bash
cd rustySDK && make clean
```

This removes:
- `rustySDK/target/` — all Rust compiled objects
- `composeApp/src/androidMain/jniLibs/` — the `.so` files
- `composeApp/src/androidMain/kotlin/generated/` — the generated Kotlin binding

After `make clean`, the next `./gradlew buildRustAndroid` (or `make android`) rebuilds everything from scratch.
