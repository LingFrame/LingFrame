# Contributing Guide

Thanks for being willing to contribute to LingFrame.

This file is the public contribution entry point.  
The truly authoritative development rules are still defined in [DEVELOPMENT_MANUAL.zh-CN.md](DEVELOPMENT_MANUAL.zh-CN.md).

If this is your first time contributing to the project, do not start by changing code immediately. Understand the project stance first, then the public architecture, and only then enter the development manual. That will save you many wrong turns.

---

## Read These First

1. [WHY.md](WHY.md)
2. [MANIFESTO.md](MANIFESTO.md)
3. [README.md](README.md)
4. [docs/glossary.md](docs/glossary.md)
5. [docs/architecture.md](docs/architecture.md)
6. [DEVELOPMENT_MANUAL.md](DEVELOPMENT_MANUAL.md)

If old comments, stale docs, local habits, or partially cleaned legacy implementation conflict with the development manual, follow the manual first.

---

## Good First Contribution Areas

If this is your first contribution, prioritize one of the following:

- documentation corrections based on the real implementation
- readability improvements to example projects
- test coverage for already shipped behavior
- small bug fixes that do not touch architecture boundaries

It is not recommended to use a first contribution for:

- state machine semantic rewrites
- Shared API contract changes
- classloader boundary changes
- lifecycle orchestration refactors
- global terminology rewrites without checking code and documentation together

---

## Minimum Requirements Before Submission

- the project builds successfully
- relevant tests pass
- the change does not break architecture boundaries
- if the change touches state machines, lifecycle, governance semantics, or Shared API, tests must be updated together
- if the change touches terminology, boundaries, or behavior, documentation must be updated together

---

## Non-Negotiable Rules

- comments must be in Chinese
- logs must be in English
- use `LingCore` and `Ling` in English contexts
- use `灵核` and `灵元` in Chinese contexts
- do not delete high-value rationale comments, pitfall notes, or risk warnings just to make code look cleaner
- test display names should use Chinese

---

## Shared API Hard Constraint

Treat `Shared API` as a process-level public contract boundary, not as a normal shared dependency.

- contract design should follow consumer-driven contracts
- prefer backward-compatible incremental evolution
- a brand-new shared API JAR may be introduced before the boundary is frozen
- an already loaded shared API JAR must not be hot-updated or hot-unloaded
- changes to existing contracts must take effect through a process restart

This is one of the most important contribution constraints in the project.

---

## Which Changes Are Architecture-Level Changes

The following changes are all architecture-level changes. If you only change code but do not update tests and docs, the work usually cannot be considered complete:

- state machine semantic changes
- lifecycle orchestration changes
- ownership or write-boundary changes
- classloader boundary changes
- Shared API contract changes
- `timeout`, `permission`, `unload`, or `audit` semantic changes

If you touch these, the PR must explain both the reason and the impact clearly.

---

## A Practical Contribution Flow

1. Sync the latest code.
2. Read the relevant docs before starting.
3. Solve the current problem with the smallest possible change.
4. Verify the build and relevant tests.
5. Re-check whether documentation also needs updating.
6. When submitting the PR, clearly explain:
   - what changed
   - why it changed
   - how it was verified
   - whether architecture, tests, or docs were affected

---

## One Reminder For First-Time Contributors

> When in doubt, preserve boundaries.

Many truly dangerous changes in LingFrame do not look like "big changes" at first. They often begin as a small convenience and then gradually weaken shared API discipline, lifecycle ownership, or governance consistency.
