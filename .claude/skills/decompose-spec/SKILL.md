---
name: decompose-spec
description: Decompose a spec into platform-tagged milestones for agent dispatch. Takes a spec slug as argument.
---

# Decompose Spec into Tagged Milestones

## Input

Argument: spec slug (e.g., `phase1-encoder`). Reads `.ai/specs/<slug>.md`.

## Instructions

1. Read the spec at `.ai/specs/$ARGUMENTS.md`
2. Read the implementation plan: `docs/rusty-qr-implementation-plan.md`
3. Read the PRD: `docs/rusty_qr_prd.docx.md`
4. Launch the `mobile-architect` agent with this prompt:

```
You are decomposing a spec into tagged milestones for automatic agent dispatch.

Read these files:
- .ai/specs/<slug>.md (the spec to decompose)
- docs/rusty-qr-implementation-plan.md (overall plan for context)
- docs/rusty_qr_prd.docx.md (requirements)

Produce a milestone plan following your Format B (Spec Decomposition) output format.
Write the result to: .ai/specs/<slug>-milestones.md

Rules:
- Every milestone gets exactly one tag: [rust], [android], [ios], or [common]
- Tags map to agents: [rust]→rust-developer, [android]→android-developer, [ios]→ios-developer, [common]→android-developer (common code lives in commonMain)
- Order by dependency. Flag which milestones can parallelize.
- Include API contracts on milestones that produce interfaces consumed downstream.
- Each milestone must be self-contained for an agent to execute independently.
- Verify steps must be CLI commands, not "open the app and check".
```

5. After the architect produces the milestones file, read it and confirm:
    - Every milestone has exactly one tag
    - Dependencies are acyclic
    - API contracts exist where milestones cross technology boundaries
    - Verify steps are automated commands

## Output

Milestones file saved to `.ai/specs/<slug>-milestones.md`. Report the milestone count and dependency
graph to the user.
