# AI Assistant Development Guide

> When modifying LingFrame code, tests, and documentation, AI must uniformly treat [DEVELOPMENT_MANUAL.zh-CN.md](DEVELOPMENT_MANUAL.zh-CN.md) as the absolute source of truth for standards.
>
> This document is not a duplicate of the manual, but rather retains specific execution rules that AI assistants must additionally heed.

## 1. Follow the Development Manual First

Before an AI assistant begins any modification, it should at least read:

1. [DEVELOPMENT_MANUAL.zh-CN.md](DEVELOPMENT_MANUAL.zh-CN.md)
2. The code of the involved modules
3. The corresponding tests
4. The corresponding architecture documents

If the context involves AI Agent architecture design, AI capability boundaries, unique intelligence entries, or route inferences, the current codebase, tests, and development manual act as the definitive reality. Do NOT quote unreleased internal planning materials in public documentation.

## 2. Rules AI Must Specifically Pay Attention To

- Identify boundaries first before touching code.
- Confirm the single source of truth and write authority first before designing modifications.
- Code comments MUST be in Chinese, logs MUST be in English.
- In Chinese contexts, prioritize using "灵珑" for the project name; if an English name is needed, write "灵珑（LingFrame）".
- Unify terminology uses to strictly be "灵核" and "灵元". Do not use "宿主 (Host)" or "插件 (Plugin)".
- Do not bypass `InstanceCoordinator` / `RuntimeCoordinator` to directly modify status.
- Do not delete high-value design explanations, pitfall notes, or risk warnings in comments just for the sake of "unified style."
- Test display names must uniformly be in Chinese, prioritizing `@Nested + @DisplayName`.
- Treat `Shared API` as the process-level common contract: New JARs can be hot-loaded, but already loaded JARs are not allowed to be hot-updated or hot-unloaded. Replacements of contracts must restart the process.
- Any changes involving architecture boundaries, state machines, lifecycles, governance semantics, or Shared API contracts must simultaneously update tests and documentation.

## 3. Minimum Actions Before AI Modifies

- Ascertain which layer this modification belongs to: instance layer, runtime layer, membership layer, orchestration layer, unload layer, adapter layer, or documentation layer.
- Affirm who holds the write authority, who is read-only, and who orchestrates.
- Confirm whether this modification will impact tests, logs, documentation, and terminology.

If you cannot answer "who holds write authority", you should not begin modifying.

## 4. Minimum Deliverables After AI Modifies

- The code holds the line on its boundaries.
- The tests cover key semantics.
- Documentation is synchronized.
- Terminology is unified.
- There is no spread or introduction of new implicit state and string magic-keys.

If older documents or historical implementations conflict with this document, this document and the development manual shall prevail.
