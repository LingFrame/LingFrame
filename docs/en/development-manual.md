# LingFrame Development Manual

> This manual is the current single source of truth for LingFrame development rules.
>
> It is intended for:
> - newcomers entering the repository for the first time
> - maintainers working on architecture, code, tests, and documentation
> - any AI assistant that modifies code, tests, or docs in this repository
>
> If older documents, historical habits, or local implementations conflict with this manual, follow this manual, [manifesto.md](manifesto.md), [why.md](why.md), and the current code facts.

---

## 1. Core Principles

If you only remember a few things, remember these six first:

1. **The first principle is to serve people, and to serve them over the long term.**
2. **LingFrame is first a JVM runtime governance framework for long-running single-process systems, not a feature bundle.**
3. **Converge boundaries, isolation, permission, reclamation, and observability before discussing feature expansion.**
4. **Complexity will not disappear, but it must stay in places that are controllable, explainable, and verifiable.**
5. **Every design must answer first: who has write authority, who is read-only, and who is responsible for orchestration.**
6. **If newcomers cannot understand the docs, if semantics cannot be verified, or if governance cannot be traced, the work is not complete.**

When you are unsure about a change, return to these six points first.

---

## 2. The LingFrame Style

LingFrame style is not just a writing tone. It is a stable set of engineering judgments.

### 2.1 What LingFrame Prefers

- serve people instead of sacrificing understanding cost, maintenance experience, and collaboration confidence for technical perfection
- prioritize boundaries instead of adding features first and constraints later
- keep a single source of truth instead of letting multiple objects maintain parallel state
- prioritize runtime governance instead of pushing problems downstream to deployment shape
- keep the skeleton stable while allowing implementation replacement
- keep long-running behavior observable instead of only being "theoretically maintainable"
- stay pragmatic, low-intrusion, reversible, and verifiable instead of romanticizing rewrites

### 2.2 Signals That Do Not Fit LingFrame

If an approach is technically possible, but it:

- expands implicit state and stringly typed magic keys
- keeps pushing complexity into Spring / JVM deep-water patches
- depends on "let it pass by default" instead of clear boundaries
- makes timeout, unload, permission, or similar semantics impossible to prove
- sacrifices understanding and maintenance experience for architectural neatness
- scatters write authority across multiple objects and layers
- keeps a boundary that is already known to be wrong only for compatibility

then it usually does not fit the LingFrame style.

---

## 3. Terminology And Expression

Terminology must stay consistent. Do not let different docs, code, logs, and tests use different words for the same concept.

| Term | Meaning | Avoid |
| --- | --- | --- |
| LingFrame | the whole project / the whole framework | Ling plugin system, plugin platform |
| LingCore | the core process / core application side that carries governance capabilities | Host |
| Ling | a governed, isolated, loadable / unloadable runtime unit | Plugin |
| Runtime | the runtime aggregate view of a ling inside LingCore | host state object |
| Instance | one concrete running entity of one ling version | version object, slot object |
| Shared API | the shared interface and DTO contract layer between lings | shared implementation, shared business logic |
| Governance Kernel | the governance capability set provided by `lingframe-core` | business layer, application layer |

### 3.1 Hard Rules For Terminology

- In Chinese contexts, the project name should be written as "灵珑"; if English needs to be added, use "灵珑（LingFrame）" or "灵珑 · LingFrame"
- In English contexts, the project name should be `LingFrame`
- Do not write "LingFrame（灵珑）" or "LingFrame's ..." in Chinese contexts
- In docs, comments, and review notes, use `LingCore` and `Ling` consistently in English contexts
- Do not reintroduce "Host" where `LingCore` is the established term
- Do not downgrade `Ling` back to `Plugin`
- If old fields or old docs still contain historical wording, new content must not continue that wording

---

## 4. The Minimum Mental Model For Newcomers

When you first enter the codebase, do not try to memorize every class at once. Start with this minimum model.

### 4.1 The Two-Layer Runtime State Model

- The **instance layer** answers: what lifecycle phase is this concrete instance actually in right now
- The **runtime layer** answers: what macro status does this ling present externally as a whole
- The two layers are related, but they do not directly modify each other
- Their linkage should depend on events and snapshots, not on objects holding each other and mutating each other freely

### 4.2 Five Key Roles

| Role | Responsibility | Core Constraint |
| --- | --- | --- |
| `LingInstance` | carrier of a single ling instance | must not expose public write access to the state machine |
| `InstanceCoordinator` | the only write entry for instance state | only this role may advance instance state |
| `InstancePool` | manages active members, the default instance, and the dying queue | manages membership only, not the full lifecycle |
| `LingRuntime` | runtime aggregate for a ling | exposes read-only views externally and must not own runtime state machine write authority |
| `RuntimeCoordinator` | the only write entry for runtime state | only this role may advance `RuntimeStatus` |

### 4.3 Two Orchestration Roles

| Role | Responsibility | Must Not Do |
| --- | --- | --- |
| `DefaultLingLifecycleEngine` | organizes deploy, switch, and unload order | must not bypass coordinators to mutate state directly |
| `LingUnloadCoordinator` | handles unload cleanup, resource reclamation, and leak detection | must not replace lifecycle orchestration |

If you understand these seven roles, you can usually follow most architectural judgments in the repository.

---

## 5. Repository Structure And Responsibility Boundaries

| Module | Role | Allowed To Do | Must Not Do |
| --- | --- | --- | --- |
| `lingframe-api` | contract layer | define required ling interfaces, annotations, and base contracts | hold business implementation or heavy dependencies |
| `lingframe-core` | governance kernel | lifecycle, state machines, permission, audit, routing, isolation, governance | depend on any specific runtime ecosystem |
| `lingframe-runtime/*` | runtime adaptation layer | connect the governance kernel to Spring Boot and other runtime ecosystems | push adaptation details back into `lingframe-core` |
| `lingframe-infrastructure/*` | infrastructure proxy layer | govern DB / cache / messaging and similar capabilities through proxies | let business lings bypass governance and hit low-level infrastructure directly |
| `lingframe-dashboard` | visualization and governance entry | show state, update governance strategy, trigger governance actions | write internal core state with elevated privileges |
| `lingframe-examples` | examples and validation | demonstrate typical usage patterns | become the source of truth for core design facts |

### 5.1 Dependency Rules

- business lings depend on `lingframe-api`, not on `lingframe-core`
- `lingframe-core` should stay as pure Java core as much as possible and must not assume Spring as its design base
- runtime adaptation belongs in `lingframe-runtime`; do not push adaptation details back into `lingframe-core`
- infrastructure capabilities should join governance through proxies instead of letting business lings connect to resources directly

---

## 6. Hard Architectural Constraints

This chapter is the red line. Violations here are usually not style differences. They are architectural regressions.

### 6.1 Write Authority Must Be Clear

Every design must answer first: who has write authority, who is read-only, and who orchestrates.

| Concept | Single Source Of Truth | Single Write Entry | Other Roles |
| --- | --- | --- | --- |
| instance state | internal state machine inside `LingInstance` | `InstanceCoordinator` | everyone else may only read or react to events |
| runtime state | internal FSM / snapshot inside `RuntimeCoordinator` | `RuntimeCoordinator` | `LingRuntime` is read-only |
| instance membership | `InstancePool` | `InstancePool`, driven by orchestration | do not maintain a second membership truth elsewhere |
| lifecycle phase order | `DefaultLingLifecycleEngine` | the orchestration logic itself | do not scatter sequence logic across objects |
| unload cleanup | `LingUnloadCoordinator` | the cleanup coordinator | must not be taken over directly by arbitrary business code |

### 6.2 State Machine Rules

- do not operate state machines directly from business code
- do not let `LingRuntime` own a second runtime FSM again
- do not let `LingInstance` expose public state mutation APIs
- do not let `InstancePool` evolve into the overall lifecycle controller
- do not let orchestration bypass coordinators to mutate state
- do not reopen state mutation entry points that have already been intentionally converged away

### 6.3 Event Linkage Rules

- state linkage should prefer events over objects directly changing each other's state
- instance-layer events should aggregate upward into the runtime layer; the runtime layer must not tamper with instance facts in reverse
- runtime aggregation should prefer **snapshots** instead of directly scanning complex object graphs as the only truth
- before adding a linkage path, explain whether it belongs to the instance layer, runtime layer, membership layer, or unload layer

### 6.4 Shared API / ClassLoader Rules

- put only interfaces, DTOs, and necessary annotations in Shared API, not business implementation
- the goal of Shared API is contract consistency, not shared logic
- when adding shared packages, shared JARs, or class-loading boundary rules, always consider unload behavior and type consistency together
- do not put implementation classes into Shared API for convenience
- semantics such as class-loader freeze and shared-boundary freeze must be supported by APIs, logs, and tests, not only by verbal agreement

### 6.4.1 Hard Constraints For Shared API Contract Evolution

In LingFrame, `Shared API` is not an ordinary dependency. Once it enters the shared class-loading boundary, it becomes a **process-level public contract**.

- Shared API design follows **consumer-driven contracts**
- Shared API only allows **backward-compatible incremental evolution**
- a **brand-new Shared API JAR** may be hot-loaded into the shared boundary
- a **Shared API JAR that has already entered the shared boundary** must not be hot-updated or hot-unloaded
- any Shared API change involving replacement, override, deletion, rollback, rename, or signature change must take effect through a **process restart**

### 6.4.2 What Counts As A Breaking Shared API Change

The following are always treated as **breaking changes**:

- deleting, renaming, or moving existing interfaces, classes, methods, fields, or enum entries
- changing method signatures, return types, parameter types, generic bounds, or exception contracts
- changing DTO field names, field types, serialized structure, required-field meaning, or default-value meaning
- changing annotation semantics in a way that changes existing consumer behavior
- changing enum value meaning, order, or names in a way that changes existing logic or serialized output
- any change that requires existing consumers to recompile, readapt, or reinterpret behavior before they can continue to run safely

### 6.4.3 Shared API Design Recommendations

- prefer adding new interfaces, default methods, new DTOs, or new versioned namespaces instead of directly changing old contract semantics
- the shared layer should express "what can be said", not "how it is done"
- DTOs should remain simple, stable, and serializable instead of holding business logic
- if a breaking change is truly required, introduce a new contract version explicitly and switch through a process restart

### 6.4.4 Shared API Change Delivery Requirements

Any Shared API change must include at least:

- compatibility notes
- scope-of-impact notes
- upgrade instructions
- tests or validation notes

### 6.5 Governance Semantics Must Be Provable

The following semantics must not rely on "everyone just understands them":

- timeout
- permission
- audit
- unload
- resource cleanup
- routing fallback
- the meaning of statuses such as `degraded`, `stopping`, and `removed`

Requirements:

- each semantic must belong to a clearly defined object or state
- each semantic must be explainable through logs, events, tests, or documentation
- each semantic must include failure paths, not only success paths
- each semantic must be verifiable; "it should work in theory" is not acceptable

### 6.6 Reflection And Low-Level Patch Rules

LingFrame does not ban reflection or JVM deep-water work. It bans **uncontrolled use** of them.

If reflection or JVM patching is necessary:

- first prove that the complexity is necessary, not lazy complexity
- contain the complexity inside a boundary instead of letting it spread everywhere
- document the risks, preconditions, failure consequences, and exit strategy
- provide matching tests, logs, and observability notes
- do not let an unmaintainable patch become a general project pattern

### 6.7 Governance Pipeline And SPI Filter Rules

The governance Pipeline is a core defense line strictly validated and protected by `FilterRegistry` at startup:
- **Builtin reserved slots**: specific orders in the `[100, 1000]` range are occupied by builtin foundation, routing, permission, and isolation filters.
- **Sandbox constraint**: externally injected `LingInvocationFilter` via SPI or dynamic registration must avoid these builtin reserved orders (recommended: use `order < 100` for preprocessing, or gaps between specific reserved ranges).
- **Fail-Fast**: if an SPI filter illegally occupies a builtin slot, the kernel throws immediately at startup and refuses to process live traffic with a "distorted governance chain".

**Routing Layer Identity-Free Principle**: the routing layer (`ContractProviderRoutingFilter` / `ProviderWeightRouter`) only honors `weight` and method qualification, never the provider identity (LingCore/Ling). Identity sediment at registration time as `weight`: LingCore defaults `weight=100`, Ling defaults `weight=0`. Method qualification decided by `LingServiceRegistry.hasMethod(lingId:contractId, methodName, paramTypes)`—providers that did not declare the called method are naturally excluded, traffic falls back to providers that declared the method, method-level fallback is a byproduct of routing not a newly introduced capability.

**Routing Layer N-way Weight Split ("No Stacking" upgraded from norm to system capability)**: the same contract may have multiple providers coexist at any moment, dispatched by `ProviderWeightRouter` in proportion to weights (binary is just the N=2 special case; N≥3 covers multi-version coexistence / multi-tenancy):

- **Registration layer allows multiple providers**: `DefaultLingServiceRegistry.registerProvider` allows any N providers to register, with Dashboard controlling weight overrides.
- **Routing layer N-way weight split**: `ProviderWeightRouter.selectProvider` natively supports proportional random allocation across any N candidates by weight. When candidate count > 2, it only warns once "on count change" (to avoid hot-path log flooding) and **does not throw to force-interrupt business**—acknowledging that multi-version coexistence (stable + canary + urgent patch) is a real production need.

The N-way weight split is not "theoretically should be so" but a system capability backed by API, logs, and tests. The `MigrationPhase` state machine expresses the macro phases of functional (contract) traffic governance—CORE_EXCLUSIVE / MIGRATING / LING_EXCLUSIVE / ITERATING; the binary phase (N=2) is a special case, and N≥3 is the multi-version coexistence / multi-tenancy scenario natively supported by the router.

**Transaction Propagation Phase (`TransactionPropagationFilter`, order=250, between `POLICY_PREFILL`(240) and `RESILIENCE`(300))**: after routing is resolved and before the TCCL switch, the physical connection of the upstream active transaction is pushed into `LingTransactionContext` by dataSourceId, for the downstream Ling to reuse through the managed data source proxy (cross-Ling single-machine ACID; see ADR-0005 for details):

- **SPI-ized (hard constraint)**: core only depends on `core.spi.TransactionBindingHook` (`isTransactionActive` / `getActiveBoundDataSourceIds` / `getBoundConnection`); the Spring implementation (bridging `TransactionSynchronizationManager`) sinks into the runtime starter—core's zero-Spring module boundary must not be broken by the propagation feature.
- **Execution-mode gating**: only the NORMAL mode propagates; SIMULATION / GOVERN_ONLY pass through directly (neither has a real terminal execution, so pushed connections would have no consumer).
- **Master switch**: `lingframe.tx.propagation.enabled` (default `true`)—when off the filter passes through directly and the Ling side does not register the managed transaction manager. This is an emergency degradation path: during the off period cross-Ling atomic rollback is unavailable. It is an "escape hatch", not a routine config; after recovery the propagation chain must be re-verified.
- **Thread boundary (dual-end collaboration)**: main-thread side (this filter pushes / rollBackOnly signal callback / finally pops + `cleanIfEmpty`) and worker-thread side (`ThreadIsolationGovernanceFilter` carries snapshots via `ThreadLocalPropagator`, restore uses **merge semantics**—`carrier.rollbackOnly |= set during worker`) are both indispensable; missing either end causes a strong connection reference leak or a lost rollback signal (silent partial commit).
- **Timeout / abandon execution**: the propagated connection monopolizes the entire cross-Ling call chain; after timeout `cancel(true)` + bounded join (`lingframe.runtime.abandoned-join-timeout`, default 2s) + grace-period timeout then poisoned discard (skip rollback and directly close, uncommitted writes are dropped with the close, governance metric `connectionPoisonedCount` counts)—the grace period is a probabilistic mitigation, not a hard guarantee; **do not claim "the connection is safe after timeout"**.

### 6.8 Migration State Machine (`MigrationPhase`)

The routing layer and functional management layer (migration state machine) are completely split, establishing a clear two-layer architecture:

- **Functional management layer**: `MigrationPhase` enum (`CORE_EXCLUSIVE` / `MIGRATING` / `LING_EXCLUSIVE` / `ITERATING`) + `MigrationStateHolder`, expressing "migration phase is the meta-state of the routing layer".
- **Routing layer**: `ProviderWeightRouter` pure-weight N-way routing, supports any number N of candidates distributed proportionally by weight (binary is just the N=2 special case).

Four-state transition diagram:

```
CORE_EXCLUSIVE ──startMigration──→ MIGRATING
MIGRATING      ──confirmPhase───→ LING_EXCLUSIVE
MIGRATING      ──rollback──────→ CORE_EXCLUSIVE
LING_EXCLUSIVE ──startIteration─→ ITERATING
ITERATING      ──confirmPhase───→ LING_EXCLUSIVE
ITERATING      ──rollback──────→ LING_EXCLUSIVE
```

Ownership and boundaries:

- `MigrationPhase` / `MigrationStateHolder` belong to the `com.lingframe.core.routing` package, same package as the routers.
- **Does not invade runtime FSM** (`RuntimeStatus`): `MigrationPhase` is the meta-state of the routing layer, orthogonal to instance/runtime state machines.
- `MigrationStateHolder` is the sole source of migration phase, `DefaultLingLifecycleEngine` orchestration + `confirmPhaseTransition` explicit confirmation advances the phase.

Explicit confirmation + drain check mechanism:

- Rejects the over-automation of "weight zeroing triggers auto phase transition", preventing irreversible transitions when ops temporarily zero for observation.
- Adopts "weight zeroing as necessary condition + explicit confirmation command (`confirmPhaseTransition`)".
- Before confirming phase transition, validates two hard indicators:
  1. The exiting party's weight must have dropped to 0;
  2. The exiting party's in-flight request count must have drained (`activeRequests == 0`).

Phase transition direction control:

- If the zeroed and confirmed exiting party is `oldCandidateKey` → treated as "migration/iteration complete" (advance to EXCLUSIVE phase, deregister the old candidate slot).
- If the zeroed and confirmed exiting party is `newCandidateKey` → treated as "migration/iteration rollback" (retreat to the previous EXCLUSIVE phase, deregister the new candidate slot).

Provider identity and versioned registration:

- **Registration keys always carry a version**: the write side `registerProvider(contractId, lingId, version, weight)` uniformly carries a version—LingCore identifies as bare `lingcore-app` (no instance context, version is `null`), while a Ling always identifies as `lingId:version` (version truth source `DefaultLingContext.getVersion()`, derived from the bound instance). Migration and iteration periods are identical—no longer distinguished.
- **Multiple versions of the same Ling coexist**: when the same Ling deploys two versions, two provider candidates coexist (e.g., `user-ling:1.0.0` and `user-ling:1.1.0`), and the routing layer splits traffic by weight.
- **Precise version removal on retirement**: when an instance is retired, the provider for that version is evicted precisely by `lingId:version`; other still-serving versions continue. Only a full Ling uninstall performs a full evict (`evictProvider(lingId)` / `evict(lingId)`).
- **No-LingCore baseline fallback**: when a contract has no `weight=100` provider and all provider weights are 0, the first registered provider is promoted to baseline 100, preventing silent "all-zero" idle routing. The `providerKey` used by Dashboard weight overrides is always `lingId:version` (or `lingcore-app` for LingCore).

Persistence and restart consistency:

- `MigrationPhase` state and candidate metadata (`lingId`, `phase`, `oldCandidate`, `newCandidate`) are uniformly persisted to `GovernanceStorage` (`config_type = 'migration'`).
- `GovernanceConfigRestorer` during startup recovery first restores the state machine phase, then restores `ProviderWeightRouter`'s weight overrides, ensuring complete state consistency across restarts.

### 6.9 DataSource Proxy Boundary And Managed DataSource Bus

**SQL governance proxy boundary**:

- LingFrame's SQL governance depends on proxying `DataSource`.
- If managed by the Spring container as a Bean, `DataSourceWrapperProcessor` auto-intercepts and wraps it.
- **Red line**: if business code or third-party libs directly create a data source via `DriverManager`, static blocks, or self-`new HikariDataSource()`, it escapes the governance network.
- **Requirement**: developers must explicitly call `LingConnectionProxyFactory.wrap(...)` to manually wrap such wild data sources, otherwise their DB access bypasses all isolation and auth rules.

**Managed DataSource Bus (`ManagedDataSourceRegistry` / `ManagedDataSourceProvider`, `api.storage` package)**: an independent bus separated in responsibility from `LingServiceRegistry` (FQSID service contract catalog), carrying the "dataSourceId → managed DataSource" infrastructure handover, without polluting the business service catalog:

- **Three modes**: mode 1 LingCore static hosting (dataSourceId is always `default`, the out-of-the-box recommended state, statically configured in LingCore `application.yml` and immutable at runtime); mode 2 Ling private library (Ling self-configures `spring.datasource.url` to build its own connection pool, physical isolation state); mode 3 storage-Ling dynamic external mount (Ling configures `lingframe.ling.datasource-id` to declare its supplier identity, builds its own data source and registers it to the bus under that id, business Lings pull the shared one via `lingframe.ling.datasource-ref`—default `default`). Mode 3 is **add-only**: the infrastructure Ling does not provide hot-unload this release, the unload entry is disabled; business Ling unload (mode 2) is unaffected.
- **Identity gating (hard constraint)**: the managed proxy carries a `dataSourceId`; `getConnection()` only looks up the propagation context connection stack precisely by its own id; the mode-2 private-pool proxy id is null and **never looks up the stack**—under a mixed chain it never misuses a managed connection (the cross-database path is physically cut).
- **Same-instance promotion (assembly contract, P0-level red line)**: the proxy produced by `DataSourceWrapperProcessor` wrapping first exists with a null identity, and must be **promoted on the same instance** via `promoteToManaged(dataSourceId)` when registered to the bus—TSM resources are keyed by instance; "replacing with a new id-carrying proxy instead of same-instance promotion" would cause the LingCore transaction manager and the bus lookup key to mismatch, silently breaking propagation. This contract is guarded by `ManagedAssemblyChainContractTest` (calling the real assembly methods directly, not replicating logic).
- **NonCloseable semantics**: on a propagation hit it returns `NonCloseableLingConnectionProxy`—`close` / `commit` / `setAutoCommit` and root-connection properties (isolation level / read-only / holdability) degrade to no-op, `rollback` only sets the rollbackOnly signal (carried upward via snapshot merge semantics); **audit is not degraded**: the transaction permission gate and the `transaction:*-suppressed` audit event are fully retained, no-op does not exempt the governance gate. The Statement factory passes straight through to the inner (already-governed) proxy, the thin proxy only corrects the `getConnection()` view—**do not** wrap another layer of Statement around the inner proxy, otherwise every SQL execution gets two permission checks and two audits (inflated audit counts).
- **Managed transaction manager (`LingManagedTransactionManager`, dual path)**: root determination truth source = whether the propagation-context connection stack (by dataSourceId) is empty at `getTransaction()` time. Root path borrows a connection → sets isolation level / readOnly → `setAutoCommit(false)` → push, commit / rollback physically execute + pop + close returning to pool; join path does not bind TSM and does not touch the connection, and before a non-root commit checks rollbackOnly (if set, throws `LingTransactionRollbackException`, aligning with Spring `UnexpectedRollbackException` semantics). Propagation boundaries: `REQUIRES_NEW` / `NESTED` are physically unreachable, explicitly degraded to join (REQUIRED) with a warning; `NEVER` / `NOT_SUPPORTED` are explicitly rejected (silent degradation would invert developer intent—writes outside a transaction get pulled into the root transaction); `MANDATORY` rejects when the stack is empty. On a missing connection in the root path commit / rollback, throw `TransactionSystemException` (with dataSourceId and phase info), **no bare NPE**.
- **Startup visibility (raise silent failure to a startup WARN)**: when the LingCore root transaction manager is non-JDBC (e.g. JPA root, cannot extract a connection), or the LingCore and Ling TSM class identities are inconsistent (spring-tx not parent-delegated, two stacks diverge), output a startup WARN—if propagation does not activate it must be visible, "silent degradation" is forbidden.
- **Propagation context (`LingTransactionContext`, `api.storage` package)**: a thread-local store separating resources (connections, stacked by dataSourceId, passed downward) and signals (rollbackOnly, passed upward); cross-thread carry is done by snapshots (`TransactionSnapshot`, including `pushOrder` push sequence), `restoreSnapshot` must use merge semantics. Cleanup guardrails: main-thread side finally pops + `cleanIfEmpty`, worker-thread side `restoreSnapshot`, `closeAllConnections` (poisoned path) clears all three ThreadLocals together—any incomplete cleanup residue pollutes subsequent calls on thread-pool reuse.

### 6.10 Dashboard Control-Plane Authentication Rules

The control plane (Dashboard) is a governance read/operation area; its security defaults must be provable and regression-testable:

- **Fail-closed authentication assembly**: `lingframe.dashboard.access-token.enabled` defaults to `true` (the POJO default enforces authentication), and the Bean assembly condition must stay consistent with it—`@ConditionalOnProperty(..., matchIfMissing = true)`, i.e. when operators only configure `token` and omit `enabled`, the authentication interceptor must still be registered. The inconsistency "POJO default true / Bean condition not registered by default" is forbidden (2026-08-03 review A1, fixed and guarded by a reflection test).
- **Constant-time token comparison**: `isValidToken` must use `MessageDigest.isEqual` constant-time comparison; `List.contains` is forbidden (timing side channel, A3 fixed).
- **Playground permission discipline**: the temporary `grant` used by simulation calls must be paired with a `revoke` in `finally`; permanent accumulation is forbidden (A2); `resolveClass` must not trigger class initialization (`Class.forName(name, false, cl)`).
- **Forwarded-header whitelist**: `X-Forwarded-Prefix / X-Forwarded-Path` are only trusted when within the configured whitelist `lingframe.trusted-forwarded-prefixes`; an empty list means no client forwarded headers are trusted (C10).
- **Scheduled tasks**: cleanup/sampling tasks relying on `@Scheduled` (tickets, rate-limit buckets, metric sampling, backups) must explicitly enable scheduling in the assembly class (B2).

All of the above semantics have ownership (Dashboard security components), failure paths (fail-closed startup failure / rejection), and tests and events backing them, consistent with "governance semantics must be provable".

### 6.11 Four-Layer Cleanup Responsibility Split

After unload, resource cleanup is split into layers by responsibility; **each layer must not overreach, substitute for, or overlap another** (per the boundary-level `AutoCloseable` automatic recycle plan v3.2 landed on 2026-08-20):

| Layer | Owner | Responsible for | Not responsible for |
| --- | --- | --- | --- |
| ① Hygiene layer | `LingUnloadHook` (spi) | Cross-cutting JVM / ecosystem leaks (JDBC drivers, thread references, ShutdownHook, logging frameworks, RMI, etc.) | Business object lifecycle |
| ② Runtime layer | `LingResourceManager` cache cleanup / thread-pool recycle | Process-level generic caches (Introspector) and per-lingId shared thread pools | Closing resources one by one |
| ③ Orphan layer | `LingResourceManager.closeableRegistry` (new in this change) | `AutoCloseable` resources that are orphaned only because "the author never handed them to Spring", closed in **reverse registration order** | Spring-managed Beans (closed by the container itself, avoiding double close) |
| ④ Main entry layer | `Ling.onStop(LingContext)` | Fine-grained, order- / dependency-aware teardown | Physical handle fallback |

**Key boundaries (hard constraints)**:

- **The orphan layer only registers "non-Spring-managed" resources**: under the Spring Ling path, Beans are destroyed by `closedContext.close()`; registering Beans here too would cause double close. The author only needs one line `ctx.registerCloseable(orphan)`—zero LingFrame API imports for Spring-managed resources.
- **Version granularity**: orphan resources are registered under the composite key `(lingId, version)`. `LingUnloadCoordinator` calls `closeResources(lingId, version)` in `onVersionUnload`, ensuring that during multi-version rolling updates the old version's orphans are released immediately with the version unload without accumulation; `onLingUnload` calls `closeResources(lingId)` as a fallback to release all residue (including late registrations during close, bounded retention without loss).
- **Install-failure rollback convergence**: `onFailureCleanup(lingId, version, ClassLoader)` appends version-level orphan closing after hook cleanup, preventing orphans registered during `onStart` from leaking into the whole-Ling unload when installation fails.
- **Concurrency strategy**: registry operations are guarded by the class-owned `registryLock`; `close()` runs outside the lock, so a single resource's blocking or throwing `close()` does not spread to other Lings' registration / deregistration.
- **Reverse order is a heuristic approximation, not a dependency topology**: order-dependent teardown must be manually orchestrated in `onStop`; the orphan layer's positioning is "physical handle fallback".
- **Only register orphans, never auto-scan all Beans**: the real value is a unified registration contract plus a fallback close mechanism.

---

## 7. Development Rules

### 7.1 Language And Expression

- comments should be in Chinese
- logs should be in English
- test display names, documentation body text, and design explanations should prefer Chinese
- terminology must follow Chapter 3

### 7.2 Comment Requirements

LingFrame does not pursue surface-level cleanliness. It pursues complete and useful content.

The following comments must be preserved or added when needed:

- design intent
- boundary explanation
- concurrency assumptions
- reasons for state-machine linkage
- pitfall notes
- resource reclamation risks
- necessary but non-obvious ordering constraints

The following must not be done just for style consistency:

- deleting high-value pitfall notes
- deleting important design explanations
- deleting risk warnings
- removing information-rich content only to make the file look cleaner

### 7.3 Types And Structure

- prefer explicit types, enums, state objects, and domain objects
- use `Map<String, Object>`, attachment bags, and string state keys carefully
- if string keys are necessary, explain their source, scope, lifecycle, and constraints
- when adding complex context, prefer extracting named state objects instead of hanging more loose fields onto a large object

### 7.4 API Design

- keep skeleton and constraints stable, while allowing implementation replacement
- public API naming should express semantics, not only technical actions
- when designing, ask whether someone can still understand it six months later
- do not merge two layers of responsibility into one object just to save one class

### 7.5 Exceptions, Logging, And Observability

- error logs must state the object, action, reason, and key context
- do not swallow exceptions
- do not throw only at the deepest layer while leaving upper layers silent
- key governance paths must be understandable from logs
- when adding a key governance semantic, prefer adding events, logs, or assertions instead of only comments

---

## 8. Testing Rules

### 8.1 Basic Rules

- use JUnit 5 by default
- use Mockito when mocking is needed
- prioritize unit tests and contract tests for core architectural behavior
- test display names should be in Chinese, with `@Nested + @DisplayName` preferred

### 8.2 What Must Be Tested First

When a change touches the following, tests should be added first:

- state machine transitions
- lifecycle orchestration order
- multi-version switching
- dying queue / draining / reclamation
- timeout
- permission / audit
- routing
- pipeline order
- class-loader boundaries
- shared API freeze semantics
- concurrency safety
- post-unload resource cleanup
- transaction propagation (cross-thread snapshot carry / rollbackOnly signal merge / assembly-chain contract / ThreadLocal dual-end erase)

### 8.3 Testing Red Lines

- do not test only the happy path
- do not test only "the code runs" without testing "the semantics are correct"
- do not encode architecture sequence dependencies in implementation details without contract tests
- do not change test semantics just because you are refactoring test style
- do not delete informative display names and explanations for the sake of tidiness

### 8.4 Recommended Test Coverage By Change Type

| Change Type | Minimum Test Coverage |
| --- | --- |
| state machine changes | transition tests + invalid transition tests |
| lifecycle orchestration changes | sequence tests + failure rollback / interruption tests |
| filter order changes | pipeline contract tests |
| unload and reclamation changes | resource cleanup tests + long-running degradation tests |
| permission / timeout / audit changes | allow / deny / fallback / audit tests |

---

## 9. Documentation Rules

### 9.1 Changes That Must Update Documentation

The following changes must not be code-only:

- architecture boundary changes
- terminology changes
- state machine changes
- lifecycle order changes
- Shared API rule changes
- testing rule changes
- AI execution rule changes

### 9.2 Documentation Update Principles

- documentation must serve understanding, not concept stacking
- explain "why this way" before "how to do it"
- once a rule becomes a hard constraint, do not leave it only in chat history
- if newcomers cannot understand the documentation, the work is not complete

---

## 10. AI And Delivery Requirements

This chapter is execution-oriented. It does not repeat the core principles. It states what must be done.

### 10.1 What Must Be Done Before Modifying

1. Identify which layer the change belongs to first: instance layer, runtime layer, membership layer, orchestration layer, unload layer, adaptation layer, or documentation layer.
2. Confirm first what the single source of truth is, who has write authority, and who is read-only.
3. Confirm first whether the change affects tests, logs, documentation, and terminology.

If you cannot answer "who has write authority", you should not start modifying code.

### 10.2 What Must Not Be Done

- bypass `InstanceCoordinator` / `RuntimeCoordinator` and mutate state directly
- spread write authority back into aggregates, pools, or business objects
- continue expanding stringly typed magic keys
- keep boundaries that are already known to be wrong for compatibility only
- keep piling complexity into Spring reflection patches or JVM black-box patches
- delete high-value design comments, pitfall notes, or risk warnings
- silently switch `LingCore` and `Ling` back to historical terms

### 10.3 Minimum Delivery Requirements

Whenever code is changed, at minimum check all of the following:

- whether the code still respects boundaries
- whether tests still cover key semantics
- whether documentation is updated
- whether terminology stays consistent
- whether logs are still in English
- whether comments are still in Chinese

---

## 11. Pre-Commit Checklist

### 11.1 Architecture Checklist

- I can clearly state who has write authority, who is read-only, and who orchestrates
- I did not add a second source of truth for state
- I did not let objects mutate each other's state again
- I did not turn a membership manager into the lifecycle controller
- I did not expand the usage of stringly typed magic keys

### 11.2 Code Checklist

- comments are in Chinese
- logs are in English
- terminology follows Chapter 3
- high-value risk comments were not removed
- the new complex logic is still explainable where it lives

### 11.3 Test Checklist

- test display names are in Chinese
- `@Nested` is used where grouping is needed
- key semantics are covered, not only flow completion
- architecture sequence changes have contract-test coverage or equivalent protection

### 11.4 Documentation Checklist

- if the change affects rules, boundaries, terminology, or state machines, I updated the documentation
- a newcomer can still find a document that explains this change

---

## 12. Recommended Reading Order For Newcomers

If this is your first time reading LingFrame, use this order:

1. [why.md](why.md)
2. [manifesto.md](manifesto.md)
3. [README.md](README.md)
4. this manual
5. `LingInstance` / `InstanceCoordinator`
6. `LingRuntime` / `RuntimeCoordinator`
7. `InstancePool`
8. `DefaultLingLifecycleEngine`
9. `LingUnloadCoordinator`
10. related test classes

If you are an AI assistant, read at least the following before changing anything:

1. this manual
2. the code in the affected module
3. the corresponding tests
4. the corresponding architecture documentation

---

## 13. The Final Decision Standard

To judge whether a change is worth making, ask only these three questions in the end:

1. Does it make the boundary clearer?
2. Does it make the semantics more provable?
3. Does it make long-running behavior more explainable?

If none of the three can be answered clearly, the change probably should not enter LingFrame.
