# Practical Entry

This page is for developers who have already seen the quick start and now want the practical picture:

- what LingFrame is good at right now
- how to introduce it without overcommitting
- what a safe first rollout looks like

It is intentionally about **adoption strategy**, not a full architecture explanation.

---

## Use LingFrame For These Problems First

`0.3.0` is most useful when you already have a long-running JVM system and want better control without splitting it apart.

If you care not only about "can it be loaded dynamically?" but also "can it be drained, cleaned up, reclaimed, and removed without making the long-running runtime dirtier?", LingFrame is a much better fit.

Strong fits:

- isolate unstable or experimental business slices as lings
- load and unload lings without full redeploy
- do canary routing inside one process
- put governance in front of cross-ling calls
- observe lifecycle, trace, and leak signals from one runtime surface
- establish disciplined hot-unload capability instead of treating dynamic loading as the finish line

Less suitable as a first goal:

- turning your monolith into a distributed platform
- real-traffic replay validation
- a fully expanded message/search proxy ecosystem

Those are outside the shipped `0.3.0` boundary.

---

## Recommended Adoption Path

### Phase 1. Start with one non-core ling

Pick one business slice that is:

- valuable enough to justify isolation
- small enough to roll back quickly
- not the deepest center of the system

### Phase 2. Move only the contract first

Define the `Shared API` first:

- interface
- request and response DTOs
- no business implementation
- no Spring components

### Phase 3. Run in dev mode and watch governance

Use this stage to verify:

- service registration
- cross-ling invocation
- permission declarations
- cache or storage governance behavior

### Phase 4. Introduce canary only after the ling is stable

Canary is most useful after the ling already loads, runs, and can be rolled back cleanly.

---

## What You Need To Know Technically Before Adopting

For a first rollout, you only need to keep three technical facts in mind:

- major governance paths now converge on one kernel instead of staying scattered
- runtime state is split between instance facts and macro runtime availability
- Dashboard is already a backend governance surface, not just a UI shell

If you add one more practical judgment, make it this:

> The first thing worth validating in LingFrame is not just whether a ling can be loaded,  
> but whether it can be drained, unloaded, and cleaned up without leaving the runtime in a worse state.

This is enough to make rollout decisions.

---

## A Safe First Rollout Checklist

- preload the shared contracts first
- keep the first ling small and reversible
- use `@LingReference` for the first integration path
- declare permissions instead of relying on accidental access
- verify unload and reload behavior in a non-production environment
- treat dashboard APIs as a governance surface, not a toy admin page

---

## Common Beginner Mistakes

- treating Shared API like a random shared utils jar
- moving too much business code at once
- thinking canary alone equals governance
- assuming Shared API can be hot-updated freely

Existing shared contracts still require a process restart to change safely.

If you need implementation details next, go to [LingFrame](technical-entry.md) or [Architecture Design](architecture.md).
