# Contributing Guide

Thanks for considering a contribution to LingFrame.

This file is the public contribution entry point.  
The authoritative development rules live in [DEVELOPMENT_MANUAL.zh-CN.md](DEVELOPMENT_MANUAL.zh-CN.md).

If you are new here, do not start by changing code blindly. Read the project stance first, then the public architecture, then the development manual.

---

## Read These First

1. [WHY.md](WHY.md)
2. [MANIFESTO.md](MANIFESTO.md)
3. [README.md](README.md)
4. [docs/glossary.md](docs/glossary.md)
5. [docs/architecture.md](docs/architecture.md)
6. [DEVELOPMENT_MANUAL.zh-CN.md](DEVELOPMENT_MANUAL.zh-CN.md)

If old comments, stale docs, local habits, or partial implementations conflict with the development manual, follow the manual first.

---

## Good First Contribution Areas

If this is your first contribution, prefer one of these:

- documentation fixes aligned with the actual implementation
- clarity improvements for examples and newcomer-facing docs
- tests around already shipped behavior
- small bug fixes that do not alter architecture boundaries

Avoid using a first contribution for:

- state machine semantic rewrites
- shared API contract changes
- classloader boundary changes
- lifecycle orchestration rewrites
- large terminology rewrites without verifying code and docs together

---

## Minimum Requirements Before Submission

- the project builds successfully
- relevant tests pass
- architecture boundaries remain intact
- state machine, lifecycle, governance semantics, and shared API changes update tests together
- terminology, boundary, and behavior changes update documentation together

---

## Non-Negotiable Rules

- comments must be in Chinese
- logs must be in English
- in English project language, use `LingCore` and `Ling`
- in Chinese project language, use `灵核` and `灵元`
- do not delete high-value rationale comments, pitfall notes, or risk warnings just to make code look cleaner
- test display names should be Chinese

---

## Shared API Hard Constraint

Treat `Shared API` as a process-level public contract boundary, not as a normal shared dependency.

- design it using consumer-driven contracts
- prefer additive, backward-compatible evolution
- a brand-new shared API JAR may be hot-loaded before the boundary is frozen
- an already loaded shared API JAR must not be hot-updated or hot-unloaded
- any existing contract change requires a process restart

This is one of the most important contribution constraints in the project.

---

## Architecture-Facing Changes

The following changes are architecture-facing and are not complete unless code, tests, and docs move together:

- state machine semantic changes
- lifecycle orchestration changes
- write-boundary or ownership changes
- classloader boundary changes
- shared API contract changes
- `timeout`, `permission`, `unload`, or `audit` semantic changes

If you touch one of these, explain the change clearly in the PR.

---

## Practical Contribution Flow

1. Sync the latest code.
2. Read the relevant docs before changing behavior.
3. Make the smallest change that solves the problem.
4. Verify the build and relevant tests.
5. Re-check whether docs also need updates.
6. Submit a PR that explains:
   - what changed
   - why it changed
   - how it was verified
   - whether architecture, tests, or docs were affected

---

## Final Advice For New Contributors

> When in doubt, preserve boundaries.

Most harmful changes in LingFrame do not start as dramatic rewrites. They usually begin as a small convenience that quietly weakens lifecycle ownership, shared API discipline, or governance consistency.
