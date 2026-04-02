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
3. Execute milestones in waves based on dependencies:

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

## Instructions
1. Read the files listed in the milestone
2. Implement exactly what the milestone specifies
3. Run the verify command(s) and ensure they pass
4. Do not modify files outside the milestone scope
```

**If milestones in the wave have the same tag → execute sequentially** (same agent can't run twice
in parallel on same files).

4. After each agent completes, run its verify command to confirm success
5. If a milestone fails verification, stop and report to the user — do not proceed to dependent
   milestones
6. After all milestones complete, invoke the `review-changes` skill

## Tracking

Use TaskCreate to track each milestone. Update status as agents complete:

- `pending` → `in_progress` → `completed` or `failed`

## Output

Report completion status for each milestone. If all passed, suggest committing with conventional
commit format.
