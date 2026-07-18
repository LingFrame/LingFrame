# Runtime Dual-State Machine Architecture

This document describes the current dual-state runtime model around `LingRuntime` / `LingInstance` in LingFrame.

It is focused on answering: **why this split exists, what each layer actually owns, and which architectural constraints must remain stable**.

If you want a more practical guide on "how to read the code, how to debug, how to extend", please read the [Runtime Dual-State Machine Guide](runtime-dual-state-machine-guide.md) instead.

It is not a design created "for the sake of state machines." It exists to solve the three most easily out-of-control problems in a single JVM, single-process, multi-version concurrent scenario:

1. A single instance's lifecycle must be predictable and cannot be arbitrarily rewritten by the outside.
2. The runtime state after multi-instance aggregation must have a single source of truth, avoiding objects writing states to each other.
3. Lifecycle orchestration, state mutation, and pool membership changes must be layered; otherwise, the codebase becomes increasingly tangled.

## One-Sentence Summary

Treat this model as two layers:

- **Instance Layer**: Describes "what factual state a specific version instance is currently in."
- **Runtime Layer**: Describes "what macro state this ling as a whole exposes to the outside."

The two layers do not directly write to each other's states; they are linked only via events.

## Why It Must Be Two Layers

If there is only one layer of state machine, three architectural problems arise:

1. Single instance facts and runtime macro intents become mixed, making state semantics increasingly muddy.
2. Lifecycle orchestration code, for the sake of "convenience," will directly alter object states, eventually allowing anyone to write state.
3. During blue-green deployments, hot reloads, or graceful unloads, composite scenarios emerge (e.g., "new version starting, old version draining, but overall runtime still available"), which a single-layer model cannot express.

After the dual-layer split, semantics can be cleanly separated:

- **InstanceStatus** focuses on the true lifecycle of a single instance.
- **RuntimeStatus** focuses on the overall runtime macro health and operational intent.

## Core Roles

| Role | Layer | Responsibility | Owns State Write Authority |
| --- | --- | --- | --- |
| `LingInstance` | Instance Layer | Holds the single-instance running entity and the instance-level FSM carrier | No, not exposed externally |
| `InstanceCoordinator` | Instance Layer | The **only** formal write entry for instance state, publishes instance state events | Yes |
| `InstancePool` | Core Membership | Manages active instances, the default instance, and the dying queue | No, only manages membership |
| `LingRuntime` | Runtime Aggregate | Holds configs, stats, and the instance pool; exposes a read-only runtime view | No |
| `RuntimeCoordinator` | Runtime Layer | Holds the `RuntimeStatus` FSM, aggregates instance snapshots, publishes runtime events | Yes |
| `DefaultLingLifecycleEngine` | Orchestration Layer | Translates deploy/unload intents into phased actions, drives deployment/unload sequence | No, orchestrates but ignores direct state modification |
| `LingUnloadCoordinator` | Unload Cleanup | Reclaims pipeline resources, invokes unload hooks, and runs leak detection | No |

## What Each State Machine Owns

### Instance Layer: `InstanceStatus`

The instance layer expresses the true lifecycle facts of a single version instance.

```text
CREATED -> LOADING -> STARTING -> READY -> STOPPING -> DEAD
   \          \           \          \          \
    +--------> ERROR -----+----------+----------+
```

Semantic priorities:

- `CREATED`: Object constructed, loading has not begun.
- `LOADING`: Bytecode verification, metadata preparation, pre-deployment phase.
- `STARTING`: Container is starting.
- `READY`: Instance can accept traffic.
- `STOPPING`: Instance has stopped accepting new traffic and is gracefully draining.
- `DEAD`: Instance is completely destroyed.
- `ERROR`: Error state, allowed to converge to `STOPPING` or `DEAD`.

The instance layer answers only one question:

> What lifecycle phase is this specific instance factually in right now?

### Runtime Layer: `RuntimeStatus`

The runtime layer expresses the macro state the entire ling presents externally.

```text
INACTIVE <-> ACTIVE <-> DEGRADED
    |          |
    +--------> STOPPING -> REMOVED
```

Semantic priorities:

- `INACTIVE`: Registered, but no serviceable instances exist.
- `ACTIVE`: Overall serviceable.
- `DEGRADED`: Overall serviceable, but degraded.
- `STOPPING`: Operations actively requested to enter the shutdown process.
- `REMOVED`: Completely removed.

One fact must be made clear here:

`RuntimeStatus` currently carries two types of semantics simultaneously:

1. **Factual State**: `INACTIVE / ACTIVE / DEGRADED`
2. **Operational Intent**: `STOPPING / REMOVED`

This is also why once `STOPPING` is entered, subsequent aggregation evaluations are suppressed and it cannot be "pulled back" by the instance layer.

## Why `LingRuntime` No Longer Owns the Runtime FSM

This is the key to the current architectural convergence.

If `LingRuntime` holds the runtime FSM itself, it naturally induces two types of errors:

1. Lifecycle orchestration code will directly mutate state through `LingRuntime`.
2. `RuntimeCoordinator` and `LingRuntime` would each maintain a state, splitting the source of truth.

The current rule is:

- The runtime FSM **only** exists in `RuntimeCoordinator`.
- `LingRuntime` reads it only via `currentStatus()`.
- External changes to `RuntimeStatus` must go through `RuntimeCoordinator`.

This allows the runtime layer to regain a "single source of truth."

## Why `LingInstance` Still Retains the State Machine

This is the most easily misunderstood point.

Retaining the instance-level FSM inside `LingInstance` does **not** mean it re-opens state write permission to the outside.

It is retained for exactly three reasons:

1. The single instance lifecycle inherently needs an atomic consistency carrier.
2. `InstanceCoordinator` needs a CAS-backed mechanism to drive state transitions.
3. The state machine follows the instance object, which best fits object boundaries.

Simultaneously, the external rules are rigid:

- `StateMachine` must not be exposed.
- Public state-altering methods like `markReady()` or `destroy()` must not be exposed.
- Only `InstanceCoordinator` can drive state changes via package-private access.

So the current structure is:

- **The state machine is inside the object.**
- **The write authority is in the hands of the coordinator.**

This is not a contradiction; it is a separation of responsibilities.

## Event Linkage, Not Object Cross-Writing

The critical linkage chain for the dual-layer state machine is as follows:

```text
InstanceCoordinator
  -> drive InstanceStatus
  -> publish InstanceStateChangedEvent

RuntimeCoordinator
  -> subscribe instance events
  -> update snapshots[lingId][version]
  -> reevaluate RuntimeStatus
  -> publish RuntimeStateChangedEvent

LingRuntime
  -> subscribe runtime events
  -> tighten LingCore-side runtime behavior when STOPPING / REMOVED
```

It must be emphasized here:

- `InstanceCoordinator` does not directly write to `RuntimeStatus`.
- `RuntimeCoordinator` does not directly mutate `LingInstance`.
- `LingRuntime` does not reverse-hold the runtime FSM.

They form a one-way linkage chain through events, rather than invading each other.

## Why Snapshots Are Important

`RuntimeCoordinator` does not explicitly traverse the object graph to deduce runtime state; instead, it maintains a snapshot of instance states:

```text
snapshots[lingId][version] = InstanceStatus
```

There are four benefits to doing this:

1. Aggregation computation only relies on factual snapshots, not complex object structures.
2. The instance layer and runtime layer can decouple behind event boundaries.
3. Under concurrency, as long as events eventually arrive, the runtime layer can re-converge.
4. The logic for `STOPPING -> REMOVED` ("wait for all instances to disappear before completing") becomes much clearer.

## Boundaries Between Orchestration Layer and State Layer

### What the Orchestration Layer Does

- `DefaultLingLifecycleEngine` splits the deploy/unload intents into phases.
- `DefaultLingLifecycleEngine` handles the overall sequence of instance startup, pool admission, retirement, and unloading.
- `LingUnloadCoordinator` is responsible for post-unload resource cleanup and leak detection.

### What the Orchestration Layer Must Not Do

- It must not directly modify `LingRuntime` state.
- It must not directly expose the `LingInstance` state machine.
- It must not bypass coordinators to publish state events itself.

Summarized in one sentence:

> The orchestration layer determines the order; coordinators determine the state.

## Architectural View of Typical Linkages

### First Deployment

```text
register runtime
-> prepare instance (CREATED -> LOADING)
-> start instance (LOADING -> STARTING)
-> add to instance pool
-> mark ready (STARTING -> READY)
-> runtime snapshots sees READY
-> runtime reevaluate (INACTIVE -> ACTIVE)
```

Design objective:

- First ensure the runtime aggregator is registered.
- Then publish instance events.
- When the `READY` event appears, the instance pool membership is already visible on the runtime side.

### Hot Reload / Multi-Version Switch

```text
old default = v1
deploy v2
-> v2 reaches READY
-> v2 becomes default
-> v1 moves to dying queue
-> v1 drains active requests
-> v1 tearDown -> DEAD
```

Design focus:

- The new version must start successfully before switching the default route.
- The old version does not disappear immediately; it enters the `dyingQueue`.
- Graceful drainage is guaranteed jointly by instance layer status and reference counting.

### Unload

```text
runtime shutdown
-> RuntimeStatus enters STOPPING
-> instance pool stops accepting new instances
-> each instance tearDown
-> snapshots become empty
-> RuntimeStatus goes STOPPING -> REMOVED
-> purge runtime
```

Design focus:

- `STOPPING` is an intent state at the runtime layer.
- `REMOVED` can only be entered once instance layer facts have been completely cleared.

## Current Hard Constraints

Treat the following rules as architectural red lines:

1. Do not directly manipulate the state machine in business code or normal LingCore integration code.
2. Do not allow `LingRuntime` to hold a second runtime FSM.
3. Do not rewrite `InstancePool` to act as a "lifecycle manager".
4. Do not allow direct state-writing between the instance layer and the runtime layer.
5. Do not re-expose compatibility-related state manipulation APIs for convenience.

## Present Abstract Realities

Although a major convergence has been completed, two realities must be acknowledged:

### First, `RuntimeStatus` Still Mixes Facts With Intents

This is a conscious tradeoff in the current implementation, not a bug.

Benefits:
- Simplifies implementation.
- Keeps the runtime shutdown process highly stable.

Costs:
- `STOPPING` semantics are not exactly in the same category as `ACTIVE / DEGRADED`.
- If governance models become more granular in the future, we may need to split "factual states" and "operational command states".

### Second, the Instance Pool and Lifecycle Orchestration Still Retain Order Coupling

This is an unavoidable reality on the LingCore side, as "joining the pool," "switching default routes," "retiring the old version," and "destroying resources" inherently have an ordering requirement.

The current approach is not to eliminate this ordering, but to centralize ordering within the orchestration layer while centralizing write authority within the coordinators.

## Architectural Benefits

After this round of convergence, the core benefits brought by this model are:

1. **Unique Single Source of Truth for State**: Both the runtime layer and instance layer have only one formal write entry.
2. **Clear Linkage Direction**: Instance facts move upwards, and runtime aggregation draws the conclusion.
3. **Readable Orchestration Code**: Phase ordering and state writing are no longer conflated.
4. **Stable Multi-Version Governance**: Blue-green, hot reloads, and unloads are easier to reason about.
5. **Grasp for Future Evolutions**: If we split "factual state / intent state" in the future, the existing boundary can support it.

If you are going to continue reading, modifying, or troubleshooting the codebase, head straight to the [Runtime Dual-State Machine Guide](runtime-dual-state-machine-guide.md).
