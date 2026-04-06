# Practical Entry

If you are not here to study framework history, but to decide whether LingFrame fits your current system, start here.

This page does not discuss idealized vision. It answers:

- which problems are worth solving with LingFrame first
- what a safe first adoption path looks like
- how far the current codebase has already converged

## Use LingFrame For These Problems First

The most suitable current use cases are not “I want to build a whole platform”, but practical problems like:

- the system has been running for years and nobody dares to touch it
- you need to gradually rebuild boundaries without full downtime
- you want governance capabilities on one unified spine instead of scattering them across layers
- you want multi-version coexistence, canary, permission, audit, and unload to become explainable

If your goal is only:

- to build a very simple plugin system
- to assemble a front-end extension market quickly
- to convert a monolith into distributed architecture immediately

then LingFrame in its current form is probably not the right first tool.

## Recommended Adoption Path

### Phase 1: Start with one non-core ling

Do not split the most critical business first.

A safer first move is:

- choose one capability with relatively clean boundaries
- make it a ling first
- validate install, invocation, governance, unload, and observability around that boundary

### Phase 2: Move the contract first, then the business

Shared API is still a strong boundary.

So the safer order is:

1. define the Shared API contract first
2. implement business lings around that contract
3. do not put implementation classes into Shared API

### Phase 3: Observe governance behavior in dev mode first

LingFrame already provides:

- dashboard control surface
- metrics and health snapshots
- SSE event streaming
- simulation testing

So the first adoption round should not only ask “does it run?” It should also ask:

- does lifecycle converge?
- are governance and permission signals visible?
- does unload at least enter the disciplined path?

### Phase 4: Introduce canary only after the ling is stable

Canary capability is already available, but it is best introduced after the ling itself is stable.

Recommended order:

1. make a single-version path stable
2. validate reload / multi-version coexistence
3. then introduce canary

## Three Technical Facts You Need Before Adopting

### 1. LingFrame already has a unified governance spine

The current design is not “every entry builds its own governance stack”.

The main anchors are:

- `InvocationPipelineEngine`
- `FilterRegistry`

So during the first adoption round, do not build a parallel governance chain on the side.

### 2. LingFrame already has a dual runtime-state model

State has already converged into:

- `InstanceStatus`
- `RuntimeStatus`

So during the first adoption round, do not scatter runtime state back into aggregate objects or business code.

### 3. LingFrame already treats disciplined hot unload as a formal capability

The current codebase does not only support loading lings. It already includes:

- drain
- teardown
- resource eviction
- leak detection

So first-round design must think about “how this will unload later”, not only “how to load it now”.

## A Safe First-Round Checklist

If you want the first adoption round to stay stable, at minimum verify:

1. is the Shared API contract stable?
2. does the ling depend only on `lingframe-api`?
3. does invocation go through the unified governance spine?
4. can Dashboard see state, metrics, and timeline?
5. can the unload path at least reach precheck and teardown?

## The Most Common Beginner Mistakes

### Treating Shared API as a shared implementation layer

That is the most common boundary regression.

Shared API should only contain:

- interfaces
- DTOs
- essential annotations

It should not carry shared business implementation.

### Validating load but not unload

If you only prove “it can be installed” but never check “how it will be removed”, then the value for long-running systems stays limited.

### Bypassing the unified governance spine

If web, beans, and ling invocation all end up using separate governance logic, the system will fragment again later.

## The Most Realistic Way To Use LingFrame Today

The practical current approach is not “lingify the whole system in one go”, but:

> choose one controlled boundary first, build runtime order, control surface, and unload path there, then expand gradually.
