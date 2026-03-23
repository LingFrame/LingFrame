# Runtime Dual-State Machine Guide

This guide is for someone who has never seen this architecture before and needs a practical way to understand, debug, and extend it.

It is focused on **how to follow the code, where to debug, and how to change the model without breaking boundaries**.

If you want the architectural rationale first, read [Runtime Dual-State Machine Architecture](runtime-dual-state-machine-architecture.md).

## Start With Three Sentences

Do not try to memorize every class first.

Just remember:

1. `InstanceCoordinator` writes instance state.
2. `RuntimeCoordinator` writes runtime state.
3. the lifecycle engine orchestrates order, but it does not own state truth.

If you keep these three sentences in mind, the code becomes much easier to read.

## The Five Objects That Matter Most

### `LingInstance`

Represents one concrete versioned runtime entity.

Think of it as:

- a container
- definition data
- an internal instance FSM
- an active-request counter

It carries facts, but it does not expose public state mutation.

### `InstanceCoordinator`

The only formal writer of instance state.

Typical entry points:

- `prepare()`
- `start()`
- `markReady()`
- `stop()`
- `error()`
- `tearDown()`

If some other class starts mutating `LingInstance` state directly, that is usually an architectural boundary violation.

### `InstancePool`

Only handles membership:

- which instances are active
- which instance is default
- which instances are in the dying queue

It is not the lifecycle owner and not the runtime state machine.

### `LingRuntime`

The aggregate runtime view, not the owner of runtime state.

It holds:

- config
- stats
- the instance pool
- a read-only status view

It does not own:

- runtime state mutation
- instance lifecycle mutation
- full deploy / undeploy orchestration

### `RuntimeCoordinator`

The owner of `RuntimeStatus`.

It listens to instance events, updates snapshots, and aggregates macro runtime state.

If you only read one class to understand the linkage between the two layers, read this one.

## How To Read the Deploy Flow

Follow this path:

1. `DefaultLingLifecycleEngine.deploy()`
2. `ensureRuntimeForDeployment()`
3. `createDeploymentInstance()`
4. `driveInstanceToLoading()`
5. `startPreparedInstance()`
6. `publishReadyInstance()`

While reading, ask three questions:

1. who defines the order?
2. who writes instance state?
3. who decides runtime state?

The answers should always be:

- engine defines order
- `InstanceCoordinator` writes instance state
- `RuntimeCoordinator` decides runtime state

## How To Read the Undeploy Flow

Follow this path:

1. `DefaultLingLifecycleEngine.undeploy()`
2. `enterRuntimeStopping()`
3. `drainInstances()`
4. `unloadSingleInstance()`
5. `InstanceCoordinator.tearDown()`
6. `RuntimeCoordinator.onInstanceStateChanged()` / `onInstanceDestroyed()`
7. `tryFinishShutdown()`

Pay attention to:

- why runtime enters `STOPPING` first
- why teardown still publishes events
- why undeploy does not directly force `REMOVED`

Because `REMOVED` must be supported by cleared instance facts, not by orchestration declaration alone.

## Why Pool Commit Happens Before READY

Current order in `publishReadyInstance()`:

1. `instancePool.addInstance(instance, isDefault)`
2. `instanceCoordinator.markReady(instance)`

This is intentional.

It guarantees that if the READY event makes the runtime aggregate to `ACTIVE`, the runtime-side membership view already contains the instance.

Without that order, you can briefly get:

- runtime says `ACTIVE`
- the pool still cannot see the instance

That kind of split-brain moment is dangerous in a governance framework.

## Common Misunderstandings

### "Two-layer FSM means two totally independent FSMs"

Not correct.

They are independent in **state ownership**, but linked through **event-driven convergence**.

### "`InstancePool` can probably manage full lifecycle too"

No.

Once `InstancePool` starts owning full lifecycle, membership, state mutation, and resource cleanup get mixed again.

### "`RuntimeCoordinator` could just scan live objects directly"

That seems simpler short-term, but it creates tighter coupling, weaker boundaries, and worse reasoning under concurrency.

Snapshots are an explicit boundary.

## Safe Extension Rules

### Adding an instance state

Ask:

1. is this a lifecycle fact or just a routing policy?
2. does it really need to be an FSM state?
3. can the transition graph remain one-way and convergent?

If it is really "should this receive traffic?", it often belongs to policy rather than `InstanceStatus`.

### Adding a runtime state

Ask:

1. is this truly a macro runtime state?
2. is it a fact state or an operational intent state?

If those two concepts are starting to collide badly, then further separation may be justified. Otherwise, avoid abstracting too early.

### Adding a deploy phase

Change orchestration first:

- `DefaultLingLifecycleEngine`
- `LingUnloadCoordinator` when the change affects unload cleanup

Do not start by changing coordinators unless state ownership itself is changing.

### Adding runtime governance linkage

Place it on the right layer:

- instance fact change -> instance event chain
- runtime macro change -> runtime event chain
- membership change -> kernel membership layer

Do not cross-write state across layers.

## How To Debug

### Deployment looks wrong

Check:

1. `DefaultLingLifecycleEngine.deploy()`
2. `InstanceCoordinator.prepare()/start()/markReady()`
3. `RuntimeCoordinator.onInstanceStateChanged()`

If the instance is already `READY` but runtime is still `INACTIVE`, the first suspects are:

- event was not published
- snapshot was not updated
- evaluation policy did not aggregate the snapshot to `ACTIVE`

### Undeploy is stuck

Check:

1. `InstancePool.dyingQueue`
2. `LingInstance.getActiveRequestCount()`
3. `InstanceCoordinator.tearDown()`
4. `RuntimeCoordinator.tryFinishShutdown()`

Typical causes:

- the instance is not idle yet
- teardown never reached `DEAD`
- snapshot entries still remain, so runtime cannot move to `REMOVED`

### Runtime jumps back from `STOPPING`

That usually means someone bypassed the boundary and wrote state directly, or the suppression logic in `RuntimeCoordinator.reevaluate()` was broken.

## What To Review In Code Changes

Whenever a patch touches lifecycle or state, check:

1. Did it introduce a new state mutation path outside a coordinator?
2. Did it let `LingRuntime` own runtime FSM again?
3. Did it expose raw state machine access from `LingInstance`?
4. Did it make `InstancePool` responsible for more than membership?
5. Did it let orchestration write runtime state directly?
6. Did it break the event linkage chain?
7. Did it make the state boundary harder to explain to the next reader?

If any answer is yes, the patch needs scrutiny.

## Suggested Reading Order For Newcomers

1. this guide
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
