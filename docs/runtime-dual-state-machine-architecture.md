# Runtime Dual-State Machine Architecture

This document describes the current dual-state runtime model around `LingRuntime` and `LingInstance`.

It is focused on **why this split exists, what each layer owns, and which architectural constraints must remain stable**.

If you want practical code-reading, debugging, and extension guidance, use [Runtime Dual-State Machine Guide](runtime-dual-state-machine-guide.md).

The design is not about adding state machines for their own sake. It exists to solve three practical problems inside a single JVM process:

1. A single instance lifecycle must be predictable and protected from arbitrary external mutation.
2. The macro runtime state of one ling must have a single source of truth.
3. Lifecycle orchestration, state mutation, and pool membership changes must be layered instead of mixed together.

## Short Version

Treat the runtime as two linked layers:

- **Instance Layer**: "what is the factual lifecycle state of one concrete instance?"
- **Runtime Layer**: "what macro state does this ling expose as a whole?"

The layers are linked by events, not by direct cross-object mutation.

## Why Two Layers

If everything is pushed into one state machine, three problems appear quickly:

1. Single-instance facts and runtime-level intent become mixed.
2. Orchestration code starts mutating state directly "for convenience".
3. Blue-green deployment and graceful undeploy become hard to model, because "new version starting", "old version draining", and "runtime still serving" happen at the same time.

After the split:

- `InstanceStatus` models per-instance facts.
- `RuntimeStatus` models macro runtime availability and operational intent.

## Core Roles

| Role | Layer | Responsibility | Owns write authority |
| --- | --- | --- | --- |
| `LingInstance` | Instance | Holds the concrete runtime entity and the per-instance FSM carrier | No public write authority |
| `InstanceCoordinator` | Instance | Only formal writer for instance state, publishes instance events | Yes |
| `InstancePool` | Kernel membership | Manages active instances, default instance, and dying queue | No, membership only |
| `LingRuntime` | Runtime aggregate | Holds config, stats, and instance pool, exposes a read-only runtime view | No |
| `RuntimeCoordinator` | Runtime | Owns `RuntimeStatus`, maintains snapshots, aggregates macro state | Yes |
| `DefaultLingLifecycleEngine` | Orchestration | Translates deploy / undeploy intent into phases and drives deploy / undeploy order | No |
| `LingUnloadCoordinator` | Unload cleanup | Evicts pipeline/runtime resources, delegates guard cleanup, and runs leak detection | No |

## What Each State Machine Owns

### Instance Layer: `InstanceStatus`

```text
CREATED -> LOADING -> STARTING -> READY -> STOPPING -> DEAD
   \          \           \          \          \
    +--------> ERROR -----+----------+----------+
```

This layer answers one question:

> What lifecycle fact is true for this specific instance right now?

### Runtime Layer: `RuntimeStatus`

```text
INACTIVE <-> ACTIVE <-> DEGRADED
    |          |
    +--------> STOPPING -> REMOVED
```

This layer answers a different question:

> What macro state should the whole ling expose right now?

One important implementation reality remains:

- `INACTIVE / ACTIVE / DEGRADED` are factual macro states.
- `STOPPING / REMOVED` also carry operational intent.

That is why `STOPPING` suppresses later aggregation and only allows progress to `REMOVED`.

## Why `LingRuntime` No Longer Owns a Runtime FSM

This was the key convergence point.

If `LingRuntime` keeps its own runtime FSM, two architectural failures become likely:

1. orchestration code writes runtime state directly through the aggregate object
2. `LingRuntime` and `RuntimeCoordinator` drift into dual sources of truth

The current rule is strict:

- runtime FSM exists only in `RuntimeCoordinator`
- `LingRuntime` reads through `currentStatus()`
- external runtime mutation must go through `RuntimeCoordinator`

## Why `LingInstance` Still Keeps an Internal FSM

This is the most common misunderstanding.

Keeping the FSM inside `LingInstance` does **not** mean reopening public state mutation.

It stays there for three reasons:

1. an instance lifecycle still needs an atomic consistency primitive
2. `InstanceCoordinator` needs a CAS-backed carrier to drive transitions
3. the FSM naturally belongs to the instance boundary

But the public rule is equally strict:

- no raw `StateMachine` exposure
- no public `markReady()` / `destroy()` style mutators on `LingInstance`
- only `InstanceCoordinator` may drive transitions through package-private access

So the final structure is:

- the FSM stays inside the object
- write authority stays in the coordinator

## Event-Driven Linkage Instead of Cross-Writing

The linkage path is intentionally one-way:

```text
InstanceCoordinator
  -> drives InstanceStatus
  -> publishes InstanceStateChangedEvent

RuntimeCoordinator
  -> subscribes to instance events
  -> updates snapshots[lingId][version]
  -> reevaluates RuntimeStatus
  -> publishes RuntimeStateChangedEvent

LingRuntime
  -> observes runtime events
  -> tightens LingCore-side runtime behavior when STOPPING / REMOVED
```

This means:

- `InstanceCoordinator` does not write `RuntimeStatus`
- `RuntimeCoordinator` does not mutate `LingInstance`
- `LingRuntime` does not keep a second runtime FSM

## Why Snapshots Matter

`RuntimeCoordinator` aggregates from snapshots:

```text
snapshots[lingId][version] = InstanceStatus
```

Instead of scanning live object graphs directly.

Benefits:

1. aggregation depends on facts, not aggregate object structure
2. instance and runtime layers stay decoupled behind event boundaries
3. concurrent reevaluation remains easier to reason about
4. `STOPPING -> REMOVED` becomes "all factual instances are gone", not "some object said so"

## Orchestration Boundary

### What orchestration does

- `DefaultLingLifecycleEngine` defines phase order
- `DefaultLingLifecycleEngine` drives startup, pool commit, retirement, and undeploy sequencing
- `LingUnloadCoordinator` handles unload-side cleanup and leak detection

### What orchestration must not do

- directly mutate `RuntimeStatus`
- re-expose raw instance state machines
- bypass coordinators and publish "implied" state changes

In one sentence:

> orchestration decides order, coordinators decide state.

## Architectural Reading Of Typical Flows

### First deployment

```text
register runtime
-> prepare instance (CREATED -> LOADING)
-> start instance (LOADING -> STARTING)
-> commit to pool
-> mark ready (STARTING -> READY)
-> runtime snapshot sees READY
-> runtime reevaluates (INACTIVE -> ACTIVE)
```

### Reload / version switch

```text
old default = v1
deploy v2
-> v2 reaches READY
-> v2 becomes default
-> v1 moves to dying queue
-> v1 drains active requests
-> v1 tearDown -> DEAD
```

### Undeploy

```text
runtime shutdown
-> RuntimeStatus enters STOPPING
-> instance pool rejects new members
-> each instance tears down
-> snapshots become empty
-> RuntimeStatus goes STOPPING -> REMOVED
-> runtime is purged
```

## Hard Rules

Treat the following as architectural red lines:

1. Do not add raw state mutation outside coordinators.
2. Do not let `LingRuntime` own a second runtime FSM.
3. Do not turn `InstancePool` into a lifecycle manager.
4. Do not let runtime and instance layers write each other directly.
5. Do not reintroduce compatibility mutators just for convenience.

## Current Reality and Future Evolution

Two realities still matter:

### `RuntimeStatus` still mixes fact and intent

That is a conscious tradeoff, not a hidden bug.

It keeps shutdown stable today, but it also means future evolution may eventually split macro facts from operational commands.

### Lifecycle ordering still matters

Pool commit, default switch, old-version retirement, and teardown are inherently ordered operations.

The goal of the current design is not to remove ordering. The goal is to centralize ordering in orchestration and centralize write authority in coordinators.

Continue with [Runtime Dual-State Machine Guide](runtime-dual-state-machine-guide.md) if you want to follow actual code paths, extension rules, and debugging entry points.
