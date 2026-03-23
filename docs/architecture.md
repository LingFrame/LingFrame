# Architecture Design

This document describes the **public `0.3.0` architecture that is actually implemented in the current codebase**.

It intentionally avoids older architecture narratives that no longer match the runtime.

If you want one sentence for the center of gravity of this document, use this:

> LingFrame architecture is no longer only answering how lings are loaded.  
> It is also formalizing how they are governed, converged, unloaded, and cleaned up in a long-running process.

---

## Design Principles

- **One governance spine**: governance should run through a single pipeline instead of being reimplemented per entry point.
- **Explicit state ownership**: instance lifecycle state and macro runtime state must not be written by the same object graph.
- **Mode-aware execution**: the kernel must support real execution, simulation, and governance-only entry borrowing without maintaining multiple rule systems.
- **Event-first explainability**: the dashboard and future control surfaces should consume real kernel events, not shadow models.
- **Long-running responsibility**: unload, cleanup, and leak diagnostics are part of runtime governance, not post-hoc utilities.
- **Hot unload must stay ordered**: drain, resource eviction, cleanup, and state convergence belong to the formal runtime path.

---

## Module Layout

| Module | Role in `0.3.0` |
| :-- | :-- |
| `lingframe-api` | contracts, annotations, exceptions, security abstractions |
| `lingframe-core` | pipeline engine, routing, runtime state, lifecycle coordination, event bus, governance logic |
| `lingframe-runtime` | Spring Boot integration, web governance filters, bean interception, starter assembly |
| `lingframe-dashboard` | governance center, simulation APIs, canary operations, SSE log streaming |
| `lingframe-infrastructure` | infrastructure proxy paths, with storage and cache as the current implemented reference paths |
| `lingframe-examples` | example LingCore app and demo lings |

---

## Invocation Pipeline

`InvocationPipelineEngine` is now the canonical governance execution path.

`FilterRegistry` assembles builtin and SPI filters, validates phase contracts at startup, and exposes the ordered chain to the engine.

### Builtin Phases

| Filter | Responsibility |
| :-- | :-- |
| `TrafficMetricsFilter` | record request facts and early metrics/traces |
| `MacroStateGuardFilter` | reject requests when runtime macro state makes them unsafe |
| `CanaryRoutingFilter` | choose the target instance, including canary routing |
| `ResilienceGovernanceFilter` | apply resilience decisions such as circuit breaking and rate limiting |
| `ContextIsolationFilter` | resolve target class, method, and classloader isolation context |
| `GovernanceDecisionFilter` | materialize governance decisions such as timeout and rule source |
| `PermissionGovernanceFilter` | enforce final permission checks |
| `ThreadIsolationGovernanceFilter` | apply execution isolation and thread handoff |
| `TerminalInvokerFilter` | perform terminal invocation, simulation result generation, or skip terminal execution based on mode |

The important shift in `0.3.0` is not just that these filters exist. It is that they now form the formal runtime path used by more than one entry surface.

---

## Execution Modes

The pipeline is now explicitly mode-aware through `InvocationExecutionMode`.

| Mode | Meaning | Typical use |
| :-- | :-- | :-- |
| `NORMAL` | run governance and real terminal invocation | ling-to-ling and standard runtime calls |
| `SIMULATION` | run the full governance chain without real side effects | dashboard simulation and explanation |
| `GOVERN_ONLY` | run governance but do not perform terminal invocation in the pipeline | Spring Web requests and LingCore bean interception that still execute through their original framework path |

This is the key mechanism that allows more entry points to reuse one kernel instead of branching into separate governance implementations.

---

## Lifecycle Orchestration

`DefaultLingLifecycleEngine` is the top-level lifecycle orchestrator in the shipped `0.3.0` runtime.

It translates deploy, reload, and unload intent into ordered runtime actions while leaving state writes to `InstanceCoordinator` and `RuntimeCoordinator`.

### Deploy

- validate ling definition and security constraints
- create classloader and container
- register runtime before the first instance facts become visible
- drive the instance through `LOADING -> STARTING -> READY`
- publish the ready instance into the pool before emitting the `READY` fact upward

### Reload

- deploy a side-by-side replacement instance
- preserve default or canary role and labels from the replaced instance
- cut over to the new instance
- unload the old instance after the replacement is ready

### Undeploy

- mark instances as `STOPPING`
- drain in-flight requests until idle or timeout
- evict services, pipeline-held resources, caches, and classloader-owned state
- perform leak detection as part of unload completion

This is part of why `0.3.0` is a convergence release rather than just a feature add.

What matters here is not merely that unload exists, but that unload is treated as an orchestrated runtime responsibility with cleanup and diagnostics.

---

## Shared API Boundary

`SharedApiManager` makes the `Shared API` boundary explicit at bootstrap time.

- preload configured shared JARs or classes directories
- register shared package prefixes
- freeze the shared boundary
- only then allow lings to load against that frozen contract view

This is an intentional process-level rule. A brand-new shared contract can be introduced before freeze, but an already loaded shared contract must not be hot-updated or hot-unloaded inside the same process.

---

## Runtime Ownership Model

`0.3.0` formalizes runtime state into two layers.

### Instance Layer

- state type: `InstanceStatus`
- owner: `InstanceCoordinator`
- purpose: model a single `LingInstance` lifecycle from `CREATED` to `DEAD`

Typical states include:

- `CREATED`
- `LOADING`
- `STARTING`
- `READY`
- `STOPPING`
- `DEAD`
- `ERROR`

### Runtime Layer

- state type: `RuntimeStatus`
- owner: `RuntimeCoordinator`
- purpose: model macro availability from the LingCore side

Typical states include:

- `INACTIVE`
- `ACTIVE`
- `DEGRADED`
- `STOPPING`
- `REMOVED`

### Linkage Model

The linkage is event-driven rather than object-mutating-object:

- instance-layer changes publish facts
- `RuntimeCoordinator` subscribes to those facts
- runtime macro state is reevaluated from snapshots

That separation is the main architectural convergence point in `0.3.0`.

For the full state-ownership explanation, continue with [Runtime Dual-State Machine Architecture](runtime-dual-state-machine-architecture.md).

---

## Governance Entry Points

The same kernel is now reused by multiple entry paths.

| Entry point | Adapter | How it uses the kernel |
| :-- | :-- | :-- |
| Ling service invocation | core invocation path | uses `NORMAL` execution through the pipeline |
| Spring Boot 2 / 3 Web requests | `LingWebGovernanceFilter` | uses `GOVERN_ONLY` to borrow governance while keeping the framework's own terminal dispatch |
| LingCore bean methods | `LingCoreBeanGovernanceInterceptor` | uses `GOVERN_ONLY` around AOP-intercepted bean execution |
| Dashboard simulations | `SimulateService` | uses `SIMULATION` to run the real governance chain without real side effects |

This is what makes `0.3.0` meaningfully different from the earlier “feature collection” stage.

---

## Observability And Cleanup

`0.3.0` also tightens the relationship between governance and operations.

- `EngineTrace` captures explainable decision traces for simulation and kernel reasoning.
- `MonitoringEvents` defines a shared event vocabulary for trace, audit, alert, circuit-breaker, and leak-detection events.
- `LogStreamService` streams those events to the dashboard through SSE.
- `InvocationPipelineEngine.evictLingResources` and method cache eviction support unload cleanup.
- `DefaultLeakDetector` reports bounded dev-mode diagnostics and passive prod-mode diagnostics.

The important architectural shift is that the dashboard is increasingly consuming **real kernel evidence** instead of a parallel interpretation layer.

That is also part of what separates LingFrame from a simpler "dynamic loading plus admin UI" approach: the control surface is attached to the same runtime spine rather than to a sidecar explanation layer.

---

## Current Boundaries

The public `0.3.0` architecture still has clear boundaries:

- it is a **single-process** governance kernel
- `Shared API` remains a **process-level contract boundary**
- shared contract changes still require a process restart once the boundary has been frozen
- storage and cache proxy paths are the clearest infrastructure references today; broader proxy ecosystems are still incremental

Those boundaries are intentional and should stay visible in public-facing documentation.
