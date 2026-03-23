# LingFrame

**Runtime governance for long-running JVM systems**

> Public release baseline: `0.3.0 (Nirvana)`

LingFrame is not trying to turn a monolith into a distributed platform overnight. Its current codebase is focused on one thing: making long-running JVM applications more governable without forcing a rewrite.

In `0.3.0`, the center of gravity has clearly shifted from scattered governance features to a converged runtime kernel.

If you want one line that captures the current implementation identity more sharply, use this:

> LingFrame is no longer only proving that lings can be loaded dynamically.  
> It is increasingly answering whether they can be unloaded, cleaned up, and kept under runtime order over long-running system life.

This page is a **code-reading entry**, not the full architecture spec.

---

## What 0.3.0 Means

The current public release is centered on four concrete changes:

- a single governance execution spine built around `InvocationPipelineEngine`
- a formal dual-state runtime model built from `InstanceStatus` and `RuntimeStatus`
- reuse of the same governance kernel across ling calls, web requests, LingCore beans, and dashboard simulations
- better operational explainability through traces, monitoring events, SSE logs, and leak diagnostics

If you are reading the codebase for the first time, this is the lens to keep in mind.

---

## Core Capabilities In The Current Codebase

| Capability | What is implemented in `0.3.0` | Main anchors |
| :-- | :-- | :-- |
| Unified invocation governance | explicit filter-based governance chain with startup order validation | `InvocationPipelineEngine`, `FilterRegistry` |
| Runtime state convergence | instance lifecycle and macro runtime availability are separated and event-linked | `InstanceStatus`, `RuntimeStatus`, `InstanceCoordinator`, `RuntimeCoordinator` |
| Web governance | Spring Boot 2 / 3 request entry points can borrow the kernel in `GOVERN_ONLY` mode | `LingWebGovernanceFilter` |
| Bean governance | LingCore beans can reuse the pipeline through AOP interception | `LingCoreBeanGovernanceInterceptor` |
| Simulation and explainability | dashboard simulation runs through the real governance chain in `SIMULATION` mode | `SimulateService`, `EngineTrace` |
| Event streaming | trace, audit, lifecycle, circuit breaker, and leak events are streamed through SSE | `MonitoringEvents`, `LogStreamService` |
| Long-running cleanup | unload-related resource eviction and leak detection are part of runtime operations | `InvocationPipelineEngine.evictLingResources`, `DefaultLeakDetector` |
| Lifecycle orchestration | deploy, side-by-side reload, drain-before-unload, and final cleanup are coordinated in one runtime path | `DefaultLingLifecycleEngine`, `LingUnloadCoordinator` |
| Shared contract boundary | shared APIs are preloaded and then frozen before lings load | `SharedApiManager` |

---

## The Project Traits Worth Noticing First

If you are reading the codebase for the first time, the most important thing to notice is not how many governance points exist, but these four traits:

- LingFrame is aimed at **long-running runtime order**, not just successful one-time deployment
- it cares about **disciplined hot unload**, not only dynamic loading
- **unload cleanup, resource eviction, and leak diagnostics** are already treated as formal runtime responsibilities
- it stays strict about **process-level contract boundaries** such as `Shared API`, instead of overselling unsafe hot-update promises

---

## How To Read The Project

| Module | What to look for first |
| :-- | :-- |
| `lingframe-api` | contract surface and shared vocabulary |
| `lingframe-core` | the actual governance kernel and runtime convergence points |
| `lingframe-runtime` | how Spring Boot 2 / 3 reuses the kernel |
| `lingframe-dashboard` | how the control surface consumes real kernel evidence |
| `lingframe-infrastructure` | current storage / cache proxy reference paths |
| `lingframe-examples` | the fastest way to connect docs to a runnable setup |

For the full module responsibility statement, use [Architecture Design](architecture.md).

---

## Current Public Boundaries

The current codebase deliberately keeps these boundaries:

- LingFrame is still a **single-process** runtime governance system, not a distributed governance platform.
- `Shared API` remains a **process-level contract**: a brand-new shared JAR can be hot-loaded, but an already loaded shared contract still requires a process restart to change safely.
- The bootstrap order is part of that contract boundary: preload shared APIs first, freeze second, then load lings.
- `0.3.0` publicly ships pipeline convergence, runtime convergence, dashboard simulation, and long-running stability work.
- **Real-traffic replay validation is not part of `0.3.0`**.
- Message/search proxy expansion is still future work rather than a finished public capability.

Continue with [Architecture Design](architecture.md) for the formal public view, or jump straight to [Runtime Dual-State Machine Architecture](runtime-dual-state-machine-architecture.md) if state ownership is the part you want to understand next.
