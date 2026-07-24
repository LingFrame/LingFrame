# Architecture Design

> LingFrame's current architecture is not only answering "how a ling is loaded",  
> but also formally answering "how a ling is governed, converged, and cleanly retired".

---

## Design Principles

- **Single governance spine**: governance capabilities run on one Pipeline, not reimplemented per entry point. Business **terminal execution** under `GOVERN_ONLY` may still live in the LingCore-side Web/AOP framework path
- **Explicit write authority**: instance lifecycle state and macro runtime state must not be tangled in the same set of objects
- **Explicit execution modes**: real / simulation / govern-only all expressed through a unified mode
- **Events before interpretation**: Dashboard and later control planes should consume real kernel events, not maintain a shadow model
- **Long-running responsibilities upfront**: unload, cleanup, leak diagnostics are runtime governance itself, not attached tools
- **Ordered hot unload**: drain, resource eviction, cleanup, and state convergence are treated as formal runtime paths

---

## Module Layout

| Module | Current role |
| :-- | :-- |
| `lingframe-api` | contracts, annotations, exceptions, security abstractions |
| `lingframe-core` | pipeline, routing, runtime state, lifecycle orchestration, event bus, governance logic |
| `lingframe-runtime` | Spring Boot integration: common `spring-boot-starter` + stack-specific `spring-boot2/3-starter` (typed Web Filter / Mapping); Bean interception |
| `lingframe-dashboard` | governance control plane, simulation API, canary operations, SSE event stream; **single GAV**, Servlet differences in `java-javax` / `java-jakarta` matrix source sets |
| `lingframe-infrastructure` | infrastructure proxy paths, currently storage and cache as implemented reference paths |
| `lingframe-examples` | sample LingCore apps and demo lings |

---

## Core Architecture Designs

The current implementation is organized around eight designs. Each answers a concrete question long-running JVM systems face.

### 1. Dual-Layer State Machine: Instance Layer + Runtime Layer

This is the center of gravity of LingFrame architecture.

**The problem it solves**: when multiple versions of a ling coexist in a single JVM, who represents the ling's outward availability? If the same object both owns "one version's lifecycle" and "the ling's macro state," the two concerns tangle and unload safety degrades.

**The design**:

- **Instance Layer** (`InstanceStatus`): the real lifecycle stage of a single ling version instance.
  - States: `CREATED → LOADING → STARTING → READY → STOPPING → DEAD` (plus `ERROR`)
  - Owner: `InstanceCoordinator` is the sole write entry; `LingInstance` holds the internal state machine but does not expose public mutation API.

- **Runtime Layer** (`RuntimeStatus`): the macro availability the LingCore side sees for a ling as a whole.
  - States: `INACTIVE / ACTIVE / DEGRADED / STOPPING / REMOVED`
  - Owner: `RuntimeCoordinator` is the sole write entry; `LingRuntime` is a read-only aggregate and must not hold a second runtime FSM.

**Linkage model**: the two layers **do not mutate each other directly**. Instance-layer changes publish facts (events); `RuntimeCoordinator` subscribes to those facts and reevaluates macro state from snapshots. Runtime macro state is an **aggregation of instance facts**, not a separate truth.

**Hard constraints**:

- Business code must not touch state machines directly.
- `LingRuntime` must never hold a second runtime FSM.
- `InstancePool` must not evolve into a "lifecycle orchestrator."
- The orchestration layer must not skip coordinators to write state directly.

### 2. Write-Authority Convergence: Single Source of Truth + Single Write Entry

**The problem it solves**: in a long-running process, the most common cause of governance drift is scattered write authority — multiple objects each maintaining a piece of state, each believing their view is canonical.

**The design**: before any change, you must first answer three questions — *who has write authority, who is read-only, who orchestrates*. Seven roles formalize this:

| Role | Purpose | Core constraint |
| :-- | :-- | :-- |
| `LingInstance` | single ling instance carrier | does not expose state-machine write authority |
| `InstanceCoordinator` | sole instance-state write entry | only it advances `InstanceStatus` |
| `InstancePool` | manages active members, default instance, dying queue | only manages membership, not full lifecycle |
| `LingRuntime` | ling runtime aggregate | exposes only read-only views, holds no runtime FSM write authority |
| `RuntimeCoordinator` | sole runtime-state write entry | only it advances `RuntimeStatus` |
| `DefaultLingLifecycleEngine` | organizes deploy, reload, unload order | cannot bypass coordinators to write state directly |
| `LingUnloadCoordinator` | unload cleanup, resource reclaim, leak detection | cannot replace lifecycle orchestration |

The single-source-of-truth table:

| Concept | Sole source | Sole write entry | Other roles |
| :-- | :-- | :-- | :-- |
| Instance state | `LingInstance` internal FSM | `InstanceCoordinator` | others only read or respond to events |
| Runtime state | `RuntimeCoordinator` internal FSM / snapshot | `RuntimeCoordinator` | `LingRuntime` is read-only |
| Instance membership | `InstancePool` | `InstancePool` driven by orchestration | no second membership source |
| Lifecycle stage order | `DefaultLingLifecycleEngine` | orchestration logic itself | stage logic must not scatter across objects |
| Unload cleanup | `LingUnloadCoordinator` | cleanup coordinator | arbitrary business code must not take over |

### 3. Single Governance Spine: Invocation Pipeline

**The problem it solves**: if Web, Bean, and Ling-to-Ling invocations each run their own governance logic, governance is guaranteed to fragment again.

**The design**: `InvocationPipelineEngine` is the canonical governance execution path. All entry surfaces reuse the same pipeline instead of reimplementing governance per entry point.

`FilterRegistry` assembles builtin and SPI filters, validates phase contracts at startup, and exposes the ordered chain to the engine.

**Builtin filter order**:

```
ContractProviderRoutingFilter   → L0 provider routing (contract FQSID, before metrics phase)
TrafficMetricsFilter            → record request facts and early metrics / trace info
MacroStateGuardFilter           → reject early when macro runtime state is unsafe
CanaryRoutingFilter             → select target instance and handle canary routing
InvocationPolicyPrefillFilter  → prefill effective policy intent into ctx.governance() before resilience
ResilienceGovernanceFilter      → execute circuit breaking, rate limit, and other resilience decisions
ContextIsolationFilter          → resolve target class, method, and ClassLoader isolation context
GovernanceDecisionFilter        → converge timeout, rule source, and other governance decisions
PermissionGovernanceFilter     → execute final permission check
ThreadIsolationGovernanceFilter → execute thread isolation and switching
TerminalInvokerFilter           → execute real terminal call, generate simulation result, or skip terminal under specific modes
```

**Three execution modes** (the key mechanism that lets multiple entry surfaces reuse one kernel):

| Mode | Meaning | Typical use |
| :-- | :-- | :-- |
| `NORMAL` | execute governance and enter real terminal call | standard ling-to-ling invocation |
| `SIMULATION` | run full governance chain but produce no real side effects | Dashboard simulation and explanation |
| `GOVERN_ONLY` | execute governance but no terminal call inside the Pipeline | Spring Web request and LingCore Bean interception; real business still runs via the original framework path |

**InvocationContext partitioning**: to prevent the traditional `Map<String, Object>` magic-key sprawl, `InvocationContext` is explicitly partitioned into four protocol zones:

- `routingState`: which instance the request should go to (target version, labels)
- `resolutionState`: short-lived strong references like ClassLoader and Method (must be physically cleared on reclaim to prevent cross-call residue)
- `governanceState`: immutable ops intent like permission, audit, rate limit, timeout
- `executionState`: whether the current invocation triggers real side effects or trace recording

**Hard constraints**:

- SPI/dynamic filters must not occupy builtin reserved orders; pick non-reserved slots between core phases
- Pipeline data flow must be traceable; no expanding string magic keys to carry core semantics

### 4. Shared API: Process-Level Public Contract

**The problem it solves**: lings need to share contracts (interfaces, DTOs) with the LingCore and with each other. Without a formal process-level boundary, "shared contract" quietly degrades into "shared implementation," and hot unload becomes unsafe.

**The design**: `SharedApiManager` makes the `Shared API` boundary explicit in the startup flow:

1. preload configured shared JARs or classes directories
2. register shared package prefixes
3. **freeze** the shared boundary
4. only then allow lings to load against the frozen contract view

**Core rules**:

- A brand-new shared JAR **can** be hot-loaded into the shared boundary
- An already-loaded shared contract **cannot** be hot-updated or hot-unloaded in the same process
- Any replacement, override, removal, rollback, rename, or signature-changing Shared API change must go through **restarting the process** to take effect safely

**What belongs in Shared API**:

- interfaces
- DTOs
- necessary annotations

**What does NOT belong**:

- business implementation logic
- shared behavior or shared services

**Design stance**: Shared API is **consumer-driven contract**. Only backward-compatible incremental evolution is allowed. Prefer adding interfaces, default methods, new DTOs, new version namespaces — do not directly mutate old contract semantics.

### 5. Class-Loader Isolation and Honest Boundaries

**The problem it solves**: under single JVM + shared Spring, "absolute isolation" is physically impossible — process-level static caches (`AnnotatedElementUtils`, `BridgeMethodResolver.cache`, etc.) hold ling Class references. Promising "complete isolation" as architecture would create false safety and unsafe unload expectations.

**The design**: `LingClassLoader` uses **Child-First** loading, with whitelist forcing parent delegation for `java.*`, `com.lingframe.api.*`, `org.slf4j.*`, and other system/API packages. Three-layer class loading boundary:

```
SharedApiClassLoader (shared boundary)
        ↓ parent
LingClassLoader (ling business classes, Child-First)
        ↓ parent
Parent ClassLoader (eco packages, injected by runtime; core does not bind Spring)
```

**What the architecture CAN promise (SLA)**:

1. **Loading isolation**: ling business classes are loaded by `LingClassLoader` (Child-First + whitelist parent delegation)
2. **Contract boundary**: Shared API frozen; lings depend only on `lingframe-api`, not `lingframe-core`
3. **Control plane boundary**: routing / governance尽量 hold only strings and weak references, avoid registering ling `Method` into LingCore `HandlerMapping`
4. **Unload contract**: two-phase cleanup + evidence-driven checklist + ClassLoader GC-able as the gate (not "runtime cache key归零")
5. **Observable**: leak detection, dump, reference-chain analysis closed loop

**What the architecture explicitly CANNOT promise (under shared Spring)**:

- Any process-level `static Map` / SoftReference / JDK metadata keyed by ling Class — not achievable
- "LingCore framework never holds any reference (including Soft, including Spring private cache) to ling Class during runtime" — not achievable
- "Cache keys归零 after unload" — not achievable; what is done is "ClassLoader GC-able is provable"

**Honest statement**: LingFrame's isolation is **type isolation + orchestration isolation + post-unload provable GC**, **NOT "runtime orthogonal to LingCore Spring static universe"**. The latter is not a lack of implementation detail; it is the physical consequence of JVM sharing semantics under single JVM + shared Spring + zero Agent.

### 6. Lifecycle Orchestration and Unload as Formal Runtime Path

**The problem it solves**: dynamic loading is easy; disciplined hot unload in a long-running process is hard. If unload, cleanup, and leak diagnostics are treated as事后 tools rather than formal runtime responsibilities, long-term stability degrades.

**The design**: `DefaultLingLifecycleEngine` is the top-level lifecycle orchestrator. It translates deploy, reload, and unload intent into ordered runtime actions, but leaves state writing to `InstanceCoordinator` and `RuntimeCoordinator`.

**Deploy**:

- validate ling definition and security constraints
- create ClassLoader and container
- register runtime aggregate before the first instance fact appears (`RuntimeCoordinator.register` timing: must `register(lingId)` before instance state events)
- drive instance into `LOADING → STARTING → READY`
- put instance into pool before publishing `READY` fact upward

**Reload**:

- first deploy a replacement instance on the side
- preserve the original instance's default/canary roles and labels
- traffic shift to the new instance
- unload the old instance only after the new instance is ready

**Unload**:

- first mark instance as `STOPPING`
- wait for in-flight requests to drain, until idle or timeout
- evict services, Pipeline-held resources, caches, and ClassLoader-associated state
- include leak diagnostics in the unload completion flow

**Unload as orchestrated runtime responsibility**:

`LingUnloadCoordinator` coordinates cleanup: two-phase cleanup, resource reclaim, evidence-driven checklist. `DefaultLeakDetector` provides tiered leak diagnostics:

- `DEV_AGGRESSIVE`: aggressive dev diagnostics
- `DEV_BOUNDED`:降级 bounded diagnostics
- `PROD_PASSIVE`: passive production observation

All via bounded concurrency limits to avoid triggering GC storms during inspection.

**What is truly special**: not that the system "supports unload" per se, but that unload is treated as a runtime responsibility requiring formal orchestration, cleanup, and diagnostics — a formal runtime path, not an事后 tool.

### 7. Dual-Stack Adaptation: Typed Differences, No Reflective Probing

**The problem it solves**: Spring Boot 2 (`javax.servlet`) and Spring Boot 3 (`jakarta.servlet`) Servlet namespaces are incompatible. Naive approaches either break one side, or leak `javax` / `jakarta` reflective probing into common code.

**The design**: LingFrame uses **two different engineering strategies** for the two layers facing Servlet differences:

**Runtime layer** — common starter + stack-specific starter:

| Module | Role |
| :-- | :-- |
| `lingframe-spring-boot-starter` | common adaptation:装配, properties, resource cleanup, Web-agnostic support; depended on by both sides |
| `lingframe-spring-boot2-starter` | SB2 / `javax.servlet`: typed Web governance Filter, repeatable-read Filter, gateway Mapping, AutoConfig |
| `lingframe-spring-boot3-starter` | SB3 / `jakarta.servlet`: typed implementation equivalent to boot2 |

Servlet type differences **must** land in boot2/boot3 starter source, as type-safe implementations. Common starter must not reflectively probe `javax` / `jakarta`. When Filter / Request types are needed, factory interfaces (like `LingRepeatableReadFilterFactory`) provide implementations via stack-specific modules and AutoConfig `@Import`.

**Dashboard layer** — single GAV + matrix source sets:

`lingframe-dashboard` stays a **single artifact coordinate**. Servlet differences are expressed via `build-helper-maven-plugin` matrix source sets:

| Directory | Content |
| :-- | :-- |
| `src/main`, `src/test` | business, config, Servlet-type-agnostic code and tests |
| `src/java-javax`, `src/test-javax` | SB2: `javax.servlet` security Filter / Interceptor etc. |
| `src/java-jakarta`, `src/test-jakarta` | SB3: `jakarta.servlet` equivalent implementations |

Matrix source sets are appended by profile (default javax; `-Pspring-boot3`切 jakarta).

**Hard constraints**:

- `lingframe-core` / `lingframe-api` must not bind to either Servlet namespace
- Common code in `src/main` must not reflectively probe Servlet API
- Lifecycle init prefer Spring generic interfaces (`InitializingBean` / `DisposableBean`), avoid `javax.annotation.PostConstruct` / `PreDestroy` hardbinding to one side
- **Prohibited** to fork dashboard into boot2/boot3 dual-coordinate modules — runtime already uses dual starters to express differences

**Verification matrix**:

```bash
# Main path
mvn -B clean verify -Pspring-boot2,integration-check

# Support line (requires JDK 17)
mvn -B clean verify -Pspring-boot3
```

When switching back from SB3 to SB2, always带 `clean`: SB3-produced class will直接 fail on JDK 8.

### 8. Control Plane Consumes Real Kernel Events

**The problem it solves**: if the Dashboard maintains a parallel interpretation layer脱节 from the runtime kernel, the control plane and runtime各自 drift — the Dashboard tells one story, the kernel lives another.

**The design**: the Dashboard increasingly consumes **real kernel events** directly, not maintaining a parallel interpretation layer. The control plane贴 on the same runtime spine.

**Mechanism**:

- `MonitoringEvents` defines unified event semantics for trace, audit, alert, circuit-breaker, leak-detection, etc.
- `EngineTrace` preserves explainable decision traces for simulation and kernel inference
- `LogStreamService` pushes these event streams to the Dashboard via SSE
- `InvocationPipelineEngine.evictLingResources` and method cache eviction support unload cleanup

**Entry surfaces reusing the same kernel**:

| Entry | Adapter | How it uses the kernel |
| :-- | :-- | :-- |
| Ling service invocation | Core standard invocation path | via `NORMAL` mode execute full Pipeline |
| Spring Boot 2 / 3 Web request | typed `LingWebGovernanceFilter` (`spring-boot2-starter` / `spring-boot3-starter`) | via `GOVERN_ONLY` borrow governance, terminal dispatch still done by Web framework |
| LingCore Bean method | `LingCoreBeanGovernanceInterceptor` | reuse governance capability via `GOVERN_ONLY` in AOP interception |
| Dashboard simulation | `SimulateService` | via `SIMULATION` run full governance chain but produce no real side effects |
| Dashboard service playground | `ServicePlaygroundService` | default `NORMAL` real call to verify interfaces; request can显式 `SIMULATION` |

**Important distinction**: multiple entry surfaces share the **governance** Pipeline, not necessarily the business **terminal** path. Web / AOP after `GOVERN_ONLY` still execute business via the LingCore-side framework path.

This is also the essential difference between the current implementation and the earlier scattered-capability拼装 state: the control plane贴 on the same runtime spine, not hanging a旁路 interpretation layer.

---

## Observability and Cleanup

The current implementation further拉近 the relationship between governance and ops.

- `EngineTrace` preserves explainable decision traces
- `MonitoringEvents` defines unified event semantics for trace, audit, alert, circuit-breaker, leak-detection
- `LogStreamService` pushes these event streams to the Dashboard via SSE
- `InvocationPipelineEngine.evictLingResources` and method cache eviction support unload cleanup
- `DefaultLeakDetector` supports tiered leak diagnostics策略 (including `DEV_AGGRESSIVE` aggressive dev diagnostics, `DEV_BOUNDED` degraded bounded diagnostics, `PROD_PASSIVE` passive production observation — three modes), all via bounded concurrency limits to avoid triggering GC storms during inspection

The important architectural change: the Dashboard开始 consume **real kernel evidence**, not maintain a separate interpretation view.

This is also where LingFrame differs from the普通 "dynamic loading + admin backend" combination:  
the control plane consumes real events on the same runtime spine, not a旁路 shadow interpretation.

---

## Current Boundaries

The current publicly exposed architecture still has clear boundaries:

- it is still a **single-process** governance kernel
- `Shared API` is still a **process-level contract boundary**
- once the shared boundary has freeze, shared contract changes still require restarting the process
- infrastructure proxies currently most clear on storage and cache paths; more proxy ecosystems are still后续 evolution
- **real traffic回放 verification is NOT part of the current public capability set**
- message / search proxy extensions are still后续 evolution, not completed public capabilities

These boundaries are kept deliberately and should remain visible in outward documentation.

---

## How to Read This Project

| Module | What to look at first |
| :-- | :-- |
| `lingframe-api` | contract surface and shared vocabulary |
| `lingframe-core` | the real governance kernel and runtime convergence point |
| `lingframe-runtime` | common `spring-boot-starter` + stack-specific boot2/boot3 starter (typed javax / jakarta; no reflective Servlet probing) |
| `lingframe-dashboard` | single GAV control plane; Servlet-related security types in `src/java-javax` / `src/java-jakarta` |
| `lingframe-infrastructure` | current storage / cache proxy reference paths |
| `lingframe-examples` | the shortest path to connect docs to a runnable setup |

For state-ownership details, see §1 above.
