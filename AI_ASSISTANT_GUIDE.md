# AI Assistant Guide

> The authoritative development rules for LingFrame are defined in [DEVELOPMENT_MANUAL.zh-CN.md](DEVELOPMENT_MANUAL.zh-CN.md).
>
> This file is not a second full manual. It only keeps the extra execution rules AI assistants must follow.

## Read Before You Change Anything

Before editing, at minimum read:

1. [DEVELOPMENT_MANUAL.zh-CN.md](DEVELOPMENT_MANUAL.zh-CN.md)
2. the relevant code
3. the relevant tests
4. the relevant architecture docs

If the task touches AI agent architecture, AI capability boundaries, a single AI entry point, or route derivation, still use the current codebase, tests, and development manual as the source of truth. Do not reference unpublished internal planning material in public docs.

## AI-Specific Rules

- identify the boundary before changing code
- identify the single source of truth and the only writer before designing a change
- comments must be in Chinese, logs must be in English
- in Chinese contexts, prefer the project name `灵珑`; if both names are needed, use `灵珑（LingFrame）`
- use the project terminology `灵核` and `灵元` in Chinese contexts; do not revert to `host` / `plugin`
- do not bypass `InstanceCoordinator` or `RuntimeCoordinator` for state mutation
- do not delete high-value rationale comments, pitfall notes, or risk warnings just for style cleanup
- test display names must be Chinese; prefer `@Nested + @DisplayName`
- treat `Shared API` as a process-level public contract: a brand-new JAR may be hot-loaded, but an already loaded shared JAR must not be hot-updated or hot-unloaded; contract replacement requires a process restart
- architecture-facing, lifecycle-facing, state-machine-facing, governance-semantic, and shared API changes must update tests and documentation together

## Minimum Actions Before Editing

- identify which layer the change belongs to: instance, runtime, membership, orchestration, unload, adapter, or documentation
- identify who writes, who reads, and who orchestrates
- identify whether the change affects tests, logs, docs, or terminology

If you cannot answer who owns the write boundary, you should not start editing.

## Minimum Delivery After Editing

- boundary-safe code
- tests for critical semantics
- updated docs when required
- consistent terminology
- no expansion of implicit state or stringly-typed magic keys

If old docs or legacy implementation conflict with this file, follow this file and the development manual.
