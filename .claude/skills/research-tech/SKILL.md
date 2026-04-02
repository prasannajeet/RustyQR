---
name: research-tech
description: Structured technology research for architectural decisions. Used by mobile-architect before making recommendations.
user-invocable: false
---

# Technology Research

Perform structured research on a technology question to produce a decision-ready recommendation.

## Input

The invoking agent provides a technology question (e.g., "best approach for XCFramework + KMM
coexistence", "UniFFI async function support for mobile").

## Process

### 1. Frame the Question

- What specific technology decision needs to be made?
- What constraints exist from the project? (Read `CLAUDE.md`,
  `docs/rusty-qr-implementation-plan.md`)
- What versions are locked in? (Kotlin 2.3.20, Compose MP 1.11.0-beta01, UniFFI 0.28+, Swift 5.9+)

### 2. Research

Use web search to find:

- **Official documentation** for the specific versions in use
- **Known issues** and breaking changes in recent releases
- **Real-world examples** of the specific technology combination (not toy demos)
- **Performance characteristics** (binary size, latency, memory) where relevant
- **Migration guides** if switching approaches later

Search queries should be specific:

- GOOD: "UniFFI 0.28 proc-macro Vec u8 Kotlin ByteArray mapping"
- BAD: "Rust FFI best practices"

### 3. Validate Against Project

- Does the finding work with our locked versions?
- Does it conflict with existing architectural decisions in `docs/adr/`?
- Does it fit our build pipeline (Cargo workspace → platform scripts → Gradle/Xcode)?

### 4. Output Format

```markdown
## Research: [Question]

### Finding
[Direct answer — what to do, not what you could do]

### Evidence
- [Source 1 with URL]: [What it confirms]
- [Source 2 with URL]: [What it confirms]
- [Version-specific note if applicable]

### Constraints Checked
- [x] Compatible with Kotlin 2.3.20 / Compose MP 1.11.0-beta01
- [x] Compatible with UniFFI 0.28+
- [x] No conflict with existing ADRs
- [ ] [Any constraint that FAILED — flag clearly]

### Risks
- [Anything that might break or change]

### Confidence: HIGH / MEDIUM / LOW
[Explain what would raise or lower confidence]
```

## Key Technology References

These are the project's locked technology stack — always verify compatibility against these:

| Technology            | Version       | Notes                               |
|-----------------------|---------------|-------------------------------------|
| Kotlin                | 2.3.20        | KMM + Compose                       |
| Compose Multiplatform | 1.11.0-beta01 | Shared UI                           |
| Android compileSdk    | 36            | minSdk 29                           |
| JVM target            | 11            |                                     |
| UniFFI                | 0.28+         | Proc-macro approach, not UDL        |
| Rust                  | >= 1.70       |                                     |
| Swift                 | 5.9+ / 6.0    | Strict concurrency                  |
| JNA                   | 5.14.0        | UniFFI Kotlin bindings require this |
| NDK                   | 30.0.14904198 | Cross-compilation                   |

## UniFFI Quick Reference (verified from docs)

**Supported types crossing FFI:**

- Primitives: `i8`-`i64`, `u8`-`u64`, `f32`, `f64`, `bool`, `String`
- Collections: `Vec<T>`, `HashMap<K, V>`, `Option<T>`
- Byte data: `Vec<u8>` → Kotlin `List<UByte>`, Swift `Data`
- Custom: `#[derive(uniffi::Record)]` structs, `#[derive(uniffi::Enum)]`, `#[derive(uniffi::Error)]`
- Objects: `#[derive(uniffi::Object)]` with `Arc<Self>` returns
- Results: `Result<T, E>` where E derives `uniffi::Error`

**NOT supported:**

- Generic types (no `MyType<T>`)
- Lifetime parameters (no `&'a str` in exports)
- Trait objects (no `Box<dyn Trait>` across FFI)
- Tuple enum variants (must use named fields)
- Type aliases wrapping references

**Proc-macro setup (no UDL):**

```rust
// In lib.rs — required when using only proc-macros
uniffi::setup_scaffolding!();
```
