---
name: execute-milestones
description: Read a milestones file and dispatch tagged milestones to the correct developer agents in dependency order.
---

# Execute Milestones

## Input

Argument: milestones file slug (e.g., `phase1-encoder`). Reads `.ai/specs/<slug>-milestones.md`.

## Instructions

1. Read `.ai/specs/$ARGUMENTS-milestones.md`
2. Parse all milestones, their tags, and dependency graph
3. Initialize the scratchpad at `.ai/scratchpad/$ARGUMENTS-progress.md` (see Scratchpad section)
4. Execute milestones in waves based on dependencies:

### Wave Execution

For each wave (set of milestones with all dependencies satisfied):

**If milestones in the wave have different tags → dispatch in parallel:**

- `[rust]` milestones → launch `rust-developer` agent
- `[android]` milestones → launch `android-developer` agent
- `[ios]` milestones → launch `ios-developer` agent
- `[common]` milestones → launch `android-developer` agent (commonMain is Kotlin)

**Agent prompt template:**

```
You are implementing milestone M<N> from the project plan.

Context:
- Project: Rusty-QR — KMM Compose Multiplatform app with Rust core library
- Read CLAUDE.md for project conventions
- Read your milestone below carefully

## Milestone
<paste the full milestone content including Do, Files, API Contract, Verify sections>

## Prior milestones completed
<list completed milestones and their API contracts if this milestone depends on them>

## Scratchpad context
<paste relevant sections from .ai/scratchpad/$ARGUMENTS-progress.md — actual outputs, type
signatures, and file paths produced by prior milestones>

## Instructions
1. Read the files listed in the milestone
2. Implement exactly what the milestone specifies
3. Run the verify command(s) and ensure they pass
4. Report back: files created/modified, actual API signatures produced, and verify output
5. Do not modify files outside the milestone scope
```

**If milestones in the wave have the same tag → execute sequentially** (same agent can't run twice
in parallel on same files).

### Error Recovery

When an agent reports a verification failure:

1. **Diagnose**: Read the agent's error output and the failing verify command output
2. **Retry once**: Relaunch the same agent with the original milestone prompt plus:

```
## Previous attempt failed

Error output:
<paste the verification failure output>

Fix the issue and re-run verification. Do not start from scratch — build on
the work from the previous attempt which is already on disk.
```

3. **If retry fails**: Mark the milestone as `failed` in the scratchpad, stop the current wave, and
   report to the user with:
    - Which milestone failed and the error output
    - Which milestones completed successfully
    - Which milestones were not attempted
    - Suggested next step (manual fix, skip, or redesign)

Do NOT proceed to dependent milestones when a dependency has failed.

### Post-Agent Steps

After each agent completes successfully:

1. Run its verify command independently (don't trust the agent's self-report)
2. Update the scratchpad with the milestone's actual outputs (see Scratchpad section)
3. Mark the milestone task as `completed`

After all milestones complete, invoke the `review-changes` skill.

## Scratchpad

The scratchpad at `.ai/scratchpad/$ARGUMENTS-progress.md` bridges context between waves. It is the
**single source of truth** for what prior milestones actually produced.

### Initialize (before Wave 1)

```markdown
# Execution Progress: <feature name>

Source: `.ai/specs/<slug>-milestones.md`
Started: <timestamp>

## Status

| Milestone | Tag | Status | Attempt |
|-----------|-----|--------|---------|
| M1: <title> | [rust] | pending | 0 |
| M2: <title> | [android] | pending | 0 |
...

## Completed Milestone Outputs

<!-- Updated after each milestone completes -->
```

### Update (after each milestone)

Append to the "Completed Milestone Outputs" section:

```markdown
### M<N>: <title> — COMPLETED

**Files created/modified:**
- `path/to/file.rs` (created)
- `path/to/other.rs` (modified)

**Actual API produced:**
- `pub fn generate_png(content: &str, size: u32) -> Result<Vec<u8>, QrError>`
- `pub struct QrConfig { pub size: u32, pub error_correction: String }`

**Verify output:**
<paste the passing verify command output>
```

This ensures Wave 2+ agents receive **actual** type signatures and file paths, not just the planned
ones from the milestone spec.

### Resume Support

If the user says "continue" or "resume", read the scratchpad to determine:

- Which milestones are `completed` (skip them)
- Which milestone is `failed` (retry or ask user)
- Which milestones are `pending` (execute next wave from these)

## Tracking

Use TaskCreate to track each milestone. Update status as agents complete:

- `pending` → `in_progress` → `completed` or `failed`

## Memory Updates (Orchestrator Responsibility)

After all milestones complete (or after a final failure), the **orchestrator** (you — the parent
agent running this skill) must update the project's auto-memory with execution results. Subagents
do NOT write to memory — you do.

Write or update a memory file at the project's memory directory with:

- Feature/phase executed and final status (all passed / partially failed)
- Key decisions made during execution that differ from the original plan
- Recurring issues encountered (e.g., "FFI type mismatch required manual fix in M3")
- Codebase conventions discovered that should inform future work

**Example memory update** (write to the appropriate memory file):

```markdown
---
name: phase1-execution-results
description: Results from executing phase 1 encoder milestones — key outcomes and issues encountered
type: project
---

Phase 1 (Rust encoder) executed 2026-04-03. All 3 milestones passed.

**Why:** First Rust core implementation — establishes patterns for phases 2-8.
**How to apply:** The actual API signatures produced are in `.ai/scratchpad/phase1-encoder-progress.md`.
QrError uses named fields; generate_png returns Result<Vec<u8>, QrError>. Use these as the
source of truth for Phase 2+ milestone design, not the original spec.
```

Also update `MEMORY.md` index with a pointer to the new memory file.

## Output

Report completion status for each milestone. If all passed, suggest committing with conventional
commit format.
