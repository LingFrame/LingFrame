# Runtime Dual-State Machine Technical Guide

This document is for developers encountering this design for the first time. The focus isn't to preach "how great the concept is," but rather to help you truly read, debug, and extend this architecture.

It answers these core questions: **how to trace the code, where things often go wrong, and how to extend without breaking boundaries**.

If you prefer to understand the design motivations and stability constraints first, please refer to the [Runtime Dual-State Machine Architecture](runtime-dual-state-machine-architecture.md).

## Establish a Minimal Mental Model First

Do not try to memorize every single class right away.

You only need to remember these three sentences first:

1. **Instance state** is written by `InstanceCoordinator`.
2. **Runtime state** is written by `RuntimeCoordinator`.
3. **Lifecycle orchestration** is sequenced by the lifecycle engine, but it does not directly own the source of truth for states.

If you can hold onto these three rules, the code will not seem chaotic when you read it later.

## The Five Most Critical Objects

### `LingInstance`

It represents the true executing entity of a specific version.

You can think of it as:

- A container.
- A set of definition metadata.
- An internal instance state machine.
- An active request counter.

It takes responsibility for "carrying facts," but it is NOT responsible for "exposing state modification externally."

### `InstanceCoordinator`

This is the only formal write-entry for instance states.

Common entries include:

- `prepare()`
- `start()`
- `markReady()`
- `stop()`
- `error()`
- `tearDown()`

If you see someone trying to change the state of `LingInstance` directly from elsewhere, that is usually a boundary violation.

### `InstancePool`

It only manages pool membership relations:

- Which instances are currently alive.
- Which instance is the default instance.
- Which instances have entered the `dyingQueue`.

It is not a state machine itself, nor is it the master lifecycle controller.

### `LingRuntime`

It is the runtime aggregate, not the runtime state machine owner.

It is responsible for:

- Configurations.
- Statistics.
- The instance pool.
- Exposing a read-only state view externally.

It is NOT responsible for:

- Directly writing `RuntimeStatus`.
- Driving the instance state machine.
- Orchestrating the complete deploy/unload flows.

### `RuntimeCoordinator`

This is the sole owner of `RuntimeStatus`.

It listens to instance-layer events, maintains snapshots, and aggregates the runtime state.

If you only pick one class to understand "how the dual-layer state machines link together," read this one first.

## How to Read the Deployment Chain

We recommend tracing in this exact sequence:

1. `DefaultLingLifecycleEngine.deploy()`
2. `ensureRuntimeForDeployment()`
3. `createDeploymentInstance()`
4. `driveInstanceToLoading()`
5. `startPreparedInstance()`
6. `publishReadyInstance()`

While reading, focus on three things:

1. Who is orchestrating the order?
2. Who is writing the instance state?
3. Who formally decides the runtime state?

You will see:

- The engine oversees the stage sequencing.
- `InstanceCoordinator` handles the instance state.
- `RuntimeCoordinator` handles the runtime state.

This is the backbone of the current architecture.

## How to Read the Unload Chain

We recommend tracing in this sequence:

1. `DefaultLingLifecycleEngine.undeploy()`
2. `enterRuntimeStopping()`
3. `drainInstances()`
4. `unloadSingleInstance()`
5. `InstanceCoordinator.tearDown()`
6. `RuntimeCoordinator.onInstanceStateChanged()` / `onInstanceDestroyed()`
7. `tryFinishShutdown()`

While reading, closely observe:

- Why the runtime is pushed to `STOPPING` first.
- Why events are sent even after the instance is destroyed.
- Why the runtime is not simply set to `REMOVED` directly inside `undeploy()`.

The reason is simple:

`REMOVED` must be supported by the fact that "all instances are factual gone," instead of the orchestration layer blindly declaring it.

## Why Enter the Pool Before Marking READY

In the current implementation, `publishReadyInstance()` runs in this order:

1. `instancePool.addInstance(instance, isDefault)`
2. `instanceCoordinator.markReady(instance)`

This order is not accidental.

It ensures that:

- When the `READY` event aggregates the runtime into `ACTIVE`,
- The runtime side's membership view can already see this instance.

Otherwise, you would briefly encounter a very nasty inconsistency:

- The runtime state is already `ACTIVE`...
- ...but the instance is not yet in the pool.

Such transient fragmentation is extremely dangerous within a governance framework.

## Common Misunderstandings

### Misunderstanding 1: Dual-layer state machines are completely isolated independent state machines.

Incorrect.

They are not entirely unrelated. Instead:

- **Sources of truth are independent.**
- **Linkage chains still exist.**

Meaning, "they independently own state" but "they eventually converge through event linkage."

### Misunderstanding 2: `InstancePool` can conveniently handle the full lifecycle.

Incorrect.

`InstancePool` can only adjust pool membership; it cannot evolve into a master orchestrator for deployments and unloads.

The moment it starts overseeing the full lifecycle, state writing, membership relations, and resource reclamations will devolve back into a tangled mess.

### Misunderstanding 3: Direct object-graph scanning by `RuntimeCoordinator` is simpler.

Superficially simple, but worse in the long run.

Because if you scan the object graph directly:

- The runtime layer tightly couples with the instance object structure.
- State consistency bounds become harder to guarantee under concurrency.
- The state linkage boundaries disappear again.

Snapshots add an extra layer, but they preserve the boundary.

## How to Extend Without Breaking the Architecture

### Scenario 1: Adding a new instance state

Ask yourself three questions first:

1. Is this a factual single-instance lifecycle state, or a routing strategy flag?
2. Does it genuinely need to be an FSM state instead of an attached property?
3. By adding it, is the state machine still unidirectional, acyclic, and capable of convergence?

If it is just a strategy toggle like "should this participate in traffic?", it normally should not be crammed into `InstanceStatus`.

### Scenario 2: Adding a new runtime state

Ask yourself two questions first:

1. Is this a macro state that the runtime as a whole exposes externally?
2. Is it a factual state, or an operational command state?

If these two categories clashing naturally limits you, then consider splitting `RuntimeStatus` into two layers. Otherwise, do not abstract just for the sake of abstraction.

### Scenario 3: Adding a new deployment phase

Modify the orchestration layer first:

- `DefaultLingLifecycleEngine`
- If changes involve unload cleanup, look at `LingUnloadCoordinator` too.

Do not start by modifying the coordinators.

Because most phase extensions are "sequence extensions", not "changes in state ownership."

### Scenario 4: Adding new runtime governance linkages

First, clarify which layer it should hook into:

- If instance facts change, hook into the instance event chain.
- If runtime macro attributes change, hook into the runtime event chain.
- If pool membership changes, hook into the Core's membership layer.

Do not stealthily write states across layers.

## What to Check When Debugging

### Debugging Deployment Failures

Review these first:

1. `DefaultLingLifecycleEngine.deploy()`
2. `InstanceCoordinator.prepare()/start()/markReady()`
3. `RuntimeCoordinator.onInstanceStateChanged()`

If an instance is already `READY` but the runtime remains `INACTIVE`, your primary suspects are:

- The event was never emitted.
- The snapshot was not updated.
- The aggregation strategy evaluated the snapshot and failed to return `ACTIVE`.

### Debugging Unloads That Are Stuck

Review these first:

1. `InstancePool.dyingQueue`
2. `LingInstance.getActiveRequestCount()`
3. `InstanceCoordinator.tearDown()`
4. `RuntimeCoordinator.tryFinishShutdown()`

Typical causes usually are:

- The instance is not idle; draining has not completed.
- Teardown never progressed to `DEAD`.
- Snapshots retain version entries, so the runtime cannot reach `REMOVED`.

### Debugging State Rebounds

If the runtime enters `STOPPING` and is then pulled back to `ACTIVE`, it usually means someone bypassed current constraints to directly write states they shouldn't, or broke the suppression logic for `STOPPING` inside `RuntimeCoordinator.reevaluate()`.

## What to Focus on During Code Reviews

When reviewing state-related changes, run through this checklist directly:

1. Did the PR add a state write entry that bypasses the coordinator?
2. Did it make `LingRuntime` hold its own runtime FSM again?
3. Did it expose the raw state machine of `LingInstance` again?
4. Did it give `InstancePool` responsibilities beyond membership relation?
5. Did it make the orchestration layer write to runtime states directly rather than through a coordinator?
6. Is the event linkage chain broken?
7. Did it make this set of state boundaries harder to explain to the next maintainer?

If the answer to any of these is "yes," proceed with extreme caution.

## Recommended Reading Path for Newcomers

If you have never encountered this design before, the safest reading sequence is:

1. This document
2. [Runtime Dual-State Machine Architecture](runtime-dual-state-machine-architecture.md)
3. `InstanceStatus`
4. `RuntimeStatus`
5. `LingInstance`
6. `InstanceCoordinator`
7. `RuntimeCoordinator`
8. `InstancePool`
9. `LingRuntime`
10. `DefaultLingLifecycleEngine`
11. `LingUnloadCoordinator`

## One Last Piece of Advice

When trying to understand this architecture, avoid endlessly asking "why not just put everything inside one object."

The question you really should be asking is:

> "Is this piece of code currently sequencing operations, carrying a fact, maintaining member relations, or deciding a macro state?"

Once that question is clear, the class responsibility boundaries naturally become clear.
