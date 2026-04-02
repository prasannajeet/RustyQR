---
name: review-changes
description: Invoke code-reviewer agent on recent changes to validate SOLID, DRY, CLEAN, and MVI compliance.
---

# Review Changes

## Input

Optional argument: specific files or git ref range (e.g., `HEAD~3..HEAD`). Defaults to all
uncommitted changes.

## Instructions

1. Determine what to review:
    - If argument is a git range: `git diff <range> --name-only`
    - If argument is file paths: use those directly
    - If no argument: `git diff --name-only` + `git diff --cached --name-only` for all pending
      changes

2. Categorize changed files by type:
    - `.rs` files → Rust changes
    - `.kt` / `.kts` files → Kotlin/Android changes
    - `.swift` files → iOS changes
    - Build files (`.gradle.kts`, `Cargo.toml`, `Makefile`) → infrastructure changes

3. Launch the `code-reviewer` agent with:

```
Review the following changed files for SOLID, DRY, CLEAN code principles and MVI compliance.

Project: Rusty-QR — KMM Compose Multiplatform app with Rust core library.
Read CLAUDE.md for project conventions.

Changed files:
<list of files with their type category>

For each file:
1. Read the file
2. Apply your review checklist
3. Output issues in your structured format: [SEVERITY] file:line — PRINCIPLE violated

Focus areas by file type:
- Rust (.rs): UniFFI compatibility, named error fields, no panics, thin FFI delegation, feature-gated deps
- Kotlin (.kt): MVI compliance (immutable state, sealed intents, no logic in composables), IO dispatcher for native calls, expect/actual naming conventions, UByte→ByteArray conversion in platform layer only
- Swift (.swift): MVI compliance (immutable state struct, enum intents, Task.detached for FFI), proper error handling (no force unwraps), Data type for byte buffers
- Build files: dependency version correctness, no unnecessary deps

End with summary: total issues by severity, approve / request changes.
```

4. Present the review results to the user

## Output

Structured review with BLOCK/WARN/NOTE severity issues and overall verdict.
