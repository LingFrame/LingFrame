# AI Assistant Guide

> When AI assistants modify LingFrame code, tests, or documentation, [DEVELOPMENT_MANUAL.zh-CN.md](DEVELOPMENT_MANUAL.zh-CN.md) remains the authoritative rule source.
>
> This file is not a duplicate of the manual. It only keeps the extra execution rules AI assistants must pay special attention to.

## 1. Follow The Development Manual First

Before starting any modification, an AI assistant should at minimum read:

1. [DEVELOPMENT_MANUAL.zh-CN.md](DEVELOPMENT_MANUAL.zh-CN.md)
2. the relevant code
3. the relevant tests
4. the relevant architecture docs

If the work touches AI agent system design, AI capability boundaries, a single intelligent entry point, or route derivation, still use the current codebase, tests, and development manual as the source of truth. Do not reference unpublished internal planning material in public documentation.

## 2. Extra Rules AI Must Pay Attention To

- identify the boundary before touching code
- identify the single source of truth and the write authority before designing a change
- comments must be in Chinese and logs must be in English
- in Chinese contexts, prefer the project name `灵珑`; if both names are needed, use `灵珑（LingFrame）`
- use the terminology `灵核` and `灵元`; do not write `host` or `plugin`
- do not bypass `InstanceCoordinator` / `RuntimeCoordinator` to mutate state directly
- do not delete high-value design comments, pitfall notes, or risk warnings just to unify style
- test display names should use Chinese, with `@Nested + @DisplayName` preferred
- treat `Shared API` as a process-level public contract: a brand-new JAR may be hot-loaded, but an already loaded JAR must not be hot-updated or hot-unloaded; contract replacement requires a process restart
- changes involving architecture boundaries, state machines, lifecycle, governance semantics, or Shared API contracts must update tests and documentation together

## 3. Minimum Actions Before AI Modifies Anything

- determine which layer this change belongs to first: instance, runtime, membership, orchestration, unload, adaptation, or documentation
- confirm who has write authority, who is read-only, and who orchestrates
- confirm whether the change affects tests, logs, documentation, or terminology

If the assistant cannot answer "who has write authority", it should not start modifying anything.

## 4. Minimum Delivery After AI Modifies Something

- code that keeps boundaries intact
- tests that cover critical semantics
- synchronized documentation
- consistent terminology
- no new spread of implicit state or stringly typed magic keys

If old documentation or legacy implementation conflicts with this file, follow this file and the development manual.
