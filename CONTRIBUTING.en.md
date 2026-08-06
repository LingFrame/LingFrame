# Contributing Guidelines

Thank you for your interest in LingFrame.

This document serves as the outward-facing entry point for contributions.  
The truly authoritative development rules are strictly bound to [DEVELOPMENT_MANUAL.md](docs/en/development-manual.md).

If this is your first time participating in this project, we strongly advise against jumping straight into modifying code. Adopting the project's stance, familiarizing yourself with the public architecture, and reading the development manual first will save you a formidable amount of detours.

---

## Read These Before You Start

1. [WHY.md](docs/en/why.md)
2. [MANIFESTO.md](docs/en/manifesto.md)
3. [README.md](README.md)
4. [docs/en/faq.md](docs/en/faq.md) (Glossary section)
5. [docs/en/architecture.md](docs/en/architecture.md)
6. [DEVELOPMENT_MANUAL.md](docs/en/development-manual.md)

If old comments, old documentation, habitual localized patterns, or incompletely scrubbed legacy implementations conflict with the development manual, the development manual takes unquestioned precedence.

---

## Suitable Directions for First-Time Contributors

If this is your first foray into the codebase, give priority consideration to these types of tasks:

- Documentation amendments matching factual implementations.
- Readability improvements across example projects.
- Injecting previously absent tests around already-delivered behaviors.
- Small bug fixes that do not interact with macro-architectural boundaries.

It is highly discouraged to tackle these on your very first contribution:

- Rewriting state machine semantics out-of-the-blue.
- Toggling Shared API contracts.
- Modifying classloader hierarchies.
- Refactoring lifecycle orchestrations.
- Sweeping global terminology replacements without meticulously syncing accompanying tests and documents.

---

## Minimum Requirements Before Submitting

- The codebase builds properly.
- Relevant tests pass flawlessly.
- Modifications have carefully refrained from wrecking architectural boundaries.
- If changes graze state machines, lifecycles, governance semantics, or Shared API thresholds, testing layers MUST be updated concurrently.
- If changes sway terminology, boundaries, or runtime behaviors, documentation MUST be synchronized properly.

---

## Non-Negotiable Rules

- Code comments MUST be in Chinese.
- Execution logs MUST be in English.
- In English contexts, use the names `LingCore` and `Ling`.
- In Chinese contexts, use the names "灵核" and "灵元".
- Do not delete high-value architectural reasoning, pitfall accounts, or risk-flagging comments merely for the visual sake of "cleaner code".
- Test display names should be designated in Chinese.

---

## The Hard Constraints of the Shared API

The `Shared API` must be respected fundamentally as the **process-level common contract boundary**, not simply treated as just another shared Maven dependency.

- Contract designs adhere to the consumer-driven paradigm.
- Upgradable pathways must overwhelmingly prioritize backward-compatible, incremental evolutions.
- Entirely novel Shared API JARs are permitted entry prior to boundary `freeze`.
- Shared API JARs that are already loaded are absolutely forbidden from being hot-updated or hot-unloaded.
- Breaking modifications upon existing contracts intrinsically mandate process restarts to execute safely.

This remains one of the single most pivotal contribution constraints across the entire project structure.

---

## What Counts as Macro-Architectural Overhauls?

The following sets of modifications all count as architectural-grade overhauls. If pull requests for these solely alter code without replenishing test suites and rewriting documentation proofs, they cannot typically be marked complete:

- State machine semantic drift.
- Orchestration cycle phase shifts.
- Re-aligning write authority and ownership boundaries.
- Classloader isolation revisions.
- Shared API contract ruptures.
- Semantic changes around `timeout`, `permission`, `unload`, or `audit` assertions.

If you interact with these zones, your PR must explicitly spell out the motivation and blast radius.

---

## A Pragmatic Contribution Flow

1. Sync up with the absolute latest upstream code.
2. Read the relevant document paths before laying a finger on files.
3. Fix the active crisis utilizing minimal-impact alterations.
4. Extensively verify the build and surrounding ecosystem tests.
5. Double-check whether any structural documentation inherently mandates rewriting.
6. Submit a PR explicitly spelling out:
   - What changed.
   - Why it changed exactly that way.
   - How it can be verified.
   - Note down whether it bruised architecture, tests, or documentation arrays.

---

## A Final Caution to First-Timers

> Inside LingFrame: when in doubt, prioritize protecting the boundaries.

Countless actually hazardous PRs rarely enter masquerading as "massive sweeping rewrites." Instead, they creep in starting as tiny, "convenience-minded tweaks" that slowly whittle down Shared API disciplines, erode lifecycle ownership restrictions, and disintegrate governance consistencies.
