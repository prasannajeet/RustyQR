---
name: adr
description: Create an Architecture Decision Record in docs/adr/. Takes a decision title as argument.
---

# Architecture Decision Record

## Input

Argument: decision title (e.g., "Use UniFFI proc-macros instead of UDL").

## Instructions

1. Determine the next ADR number:
   ```bash
   ls docs/adr/ 2>/dev/null | grep -c 'ADR-' | xargs -I{} expr {} + 1
   ```
   If `docs/adr/` doesn't exist, create it. Start at ADR-001.

2. Gather context:
    - Read `docs/rusty-qr-implementation-plan.md` for relevant phase context
    - Read any related existing ADRs in `docs/adr/`
    - If the decision involves a technology choice, invoke the `research-tech` skill first

3. Write the ADR to `docs/adr/ADR-<NNN>-<slug>.md` using this format:

```markdown
# ADR-<NNN>: <Decision Title>

## Status

Accepted

## Date

<today's date: YYYY-MM-DD>

## Context

[2-4 sentences: What problem or question prompted this decision? What forces are at play?
Include relevant constraints from the project — versions, platform requirements, existing patterns.]

## Decision

[1-2 sentences: State the decision clearly. "We will..." format.
Be specific — name versions, approaches, file paths where applicable.]

## Alternatives Considered

### <Alternative 1>
- **Rejected because**: [specific reason — not "it's worse", but why it doesn't fit]

### <Alternative 2>
- **Rejected because**: [specific reason]

## Consequences

### Positive
- [Concrete benefit with specifics]
- [Concrete benefit with specifics]

### Negative
- [Concrete cost or trade-off]
- [What we're giving up]

### Risks
- [What could go wrong and when we'd know]

## Reversibility

[One sentence: How hard is it to reverse this decision? What would trigger a reversal?]
```

4. Update `docs/adr/README.md` (create if needed) with an index entry:
   ```
   | ADR-<NNN> | <Title> | Accepted | <Date> |
   ```

## Guidelines

- **One decision per ADR** — don't bundle "use UniFFI AND use named fields" into one ADR
- **Max 1 page** — if it's longer, you're explaining too much "what" and not enough "why"
- **No implementation details** — ADRs record decisions, not how to implement them
- **Timeless language** — avoid "currently", "today", "right now"; use dates instead
- **Name specific versions** — "UniFFI 0.28" not "latest UniFFI"
- **Record what was rejected** — this is the most valuable part for future readers
