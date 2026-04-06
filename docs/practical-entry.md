# Practical Entry

If you are not here to study framework history, but want to judge whether "LingFrame is suitable for my current system," this is the best place to start.

This page does not discuss ideal blueprints. It only answers:

- What problems are most worth solving with LingFrame first?
- How should the first round of integration proceed?
- To what degree has the current codebase achieved this?

## Solve These Problems With LingFrame First

The most suitable scenarios for introducing LingFrame early on are not "I want to build an entire ecosystem platform," but rather real, grounded problems:

- The system has been running for a long time, and no one dares to change it carelessly.
- You need to gradually reconstruct boundaries without incurring downtime.
- You want to centralize governance capabilities into one unified main chain, rather than scattering them across layers.
- You want multi-version co-existence, canary releases, permissions, audits, and unloads to become explainable.

If your desires are merely:

- Playing around with a simple plugin system.
- Quickly assembling a frontend extension marketplace.
- Instantly rewriting a monolith into microservices.

Then LingFrame is not currently the most appropriate tool for your job.

## Recommended Integration Path

### Phase 1: Choose a Non-Core Ling First

For your first step, do not immediately dismantle your most critical business logic.

The safer approach right now is:

- Select a business capability with relatively clear boundaries.
- Turn it into a ling first.
- Step through the full chain: install, invoke, governance, unload, and observability.

### Phase 2: Move Contracts First, Business Second

`Shared API` is still a hard boundary today.

Therefore, the recommended order is:

1. Define the Shared API contracts first.
2. Implement the business ling around those contracts.
3. Keep implementation classes out of the Shared API.

### Phase 3: Observe Governance Behavior in Dev Mode First

LingFrame currently provides:

- Dashboard control surface.
- Metrics and health snapshots.
- SSE event streams.
- Simulation testing.

So the first round of integration shouldn't just confirm "can it run?", but should observe:

- Has the lifecycle converged?
- Are permissions and governance signals visible?
- Does the unload action walk the standardized chain?

### Phase 4: Introduce Canary After the Ling is Stable

Canary release capabilities are already usable today, but are better suited to be introduced after the ling itself is stable.

Recommended sequence:

1. Run the single-version flow first.
2. Verify hot-reload / multi-version co-existence next.
3. Introduce canary last.

## Three Technical Facts You Should Know Before Integration

### 1. LingFrame Already Has a Unified Governance Main Chain

Right now, we don't have separate governance chains for separate entry points. We prefer reuse:

- `InvocationPipelineEngine`
- `FilterRegistry`

This means during the first round of integration, you shouldn't assemble your own bypassed governance chain on the side.

### 2. LingFrame Already Has a Dual-Layer Runtime State Model

State has converged down to:

- `InstanceStatus`
- `RuntimeStatus`

So during the first integration attempt, do not scatter runtime state back onto aggregate objects or business objects.

### 3. LingFrame is Actively Advancing Standardized Hot Unloads as a Formal Capability

The current codebase does more than just load lings. It officially includes:

- Draining.
- Teardown.
- Resource eviction.
- Leak detection.

So when designing the first integration, you must consider "how will it be unloaded later" from the very beginning.

## A Safe First-Round Go-Live Checklist

If you want your first release to be solid, at least confirm:

1. Are the Shared API contracts stable?
2. Does the ling only depend on `lingframe-api`?
3. Do invocations pass through the unified governance chain?
4. Can you see states, metrics, and timelines in the Dashboard?
5. Can the completely unload chain run down to precheck and teardown successfully?

## Common Pitfalls for Newcomers

### Treating Shared API As a Shared Implementation Layer

This is the most common way boundaries regress.

The Shared API today should strictly hold:

- Interfaces.
- DTOs.
- Necessary annotations.

It should not be stuffed with implementation logic.

### Verifying Loading But Skipping Unloading

If you only prove "it can be loaded in" but never verify "how it gets unloaded," the value for long-running systems is extremely limited.

### Bypassing the Unified Governance Main Chain

If Web, Bean, and Ling-to-Ling invocations each run their own custom governance logic, they are guaranteed to fragment again down the line.

## The Most Realistic Usage Strategy at The Current Stage

The most realistic approach today is not "LingFrame-ify the entire system at once," but rather:

> Choose a controllable boundary first, establish runtime order, the control surface, and the unload chain, then expand the scope gradually.
