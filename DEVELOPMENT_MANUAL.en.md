# LingFrame Development Manual

> This manual is the current single source of truth for LingFrame development rules.
>
> It is intended for:
> - newcomers entering the repository for the first time
> - maintainers working on architecture, code, tests, and documentation
> - any AI assistant that modifies code, tests, or docs in this repository
>
> If older documents, historical habits, or local implementations conflict with this manual, follow this manual, [MANIFESTO.md](MANIFESTO.md), [WHY.md](WHY.md), and the current code facts.

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

### 6.8 Migration State Machine (`MigrationPhase`)

The routing layer and functional management layer (migration state machine) are completely split, establishing a clear two-layer architecture:

- **Functional management layer**: `MigrationPhase` enum (`CORE_EXCLUSIVE` / `MIGRATING` / `LING_EXCLUSIVE` / `ITERATING`) + `MigrationStateHolder`, expressing "migration phase is the meta-state of the routing layer".
- **Routing layer**: `ProviderWeightRouter` pure-weight binary routing, input ≤2 candidates, select one by weight.

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

Provider identity during iteration:

- **Migration period (CORE ↔ LING)**: LingCore identity is `lingcore-app`, Ling identity is bare `lingId`.
- **Iteration period (v1 ↔ v2)**: when the same Ling deploys two versions, Provider identity is explicitly upgraded to `lingId:version` (e.g., `user-ling:1.0.0` and `user-ling:1.1.0`).
- **After iteration completes and phase transition is confirmed**: the retained version's Provider identity converges back to bare `lingId`.

Persistence and restart consistency:

- `MigrationPhase` state and candidate metadata (`lingId`, `phase`, `oldCandidate`, `newCandidate`) are uniformly persisted to `GovernanceStorage` (`config_type = 'migration'`).
- `GovernanceConfigRestorer` during startup recovery first restores the state machine phase, then restores `ProviderWeightRouter`'s weight overrides, ensuring complete state consistency across restarts.

### 6.9 Non-Bean DataSource Proxy Boundary

- LingFrame's SQL governance depends on proxying `DataSource`.
- If managed by the Spring container as a Bean, `LingFrameBeanPostProcessor` auto-intercepts and wraps it.
- **Red line**: if business code or third-party libs directly create a data source via `DriverManager`, static blocks, or self-`new HikariDataSource()`, it escapes the governance network.
- **Requirement**: developers must explicitly call `LingConnectionProxyFactory.wrap(...)` to manually wrap such wild data sources, otherwise their DB access bypasses all isolation and auth rules.

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

1. [WHY.md](WHY.md)
2. [MANIFESTO.md](MANIFESTO.md)
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
