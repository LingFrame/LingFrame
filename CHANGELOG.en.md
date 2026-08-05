# Changelog

All notable changes to this project will be documented in this file.

## [V0.4.0] - 2026-08-05

Version: `lingframe-dependencies` → `revision=0.4.0`.  
Primary path: Spring Boot 2.7 + JDK 8; Spring Boot 3.x + JDK 17 is the dual-stack support line.

### Goal

On top of the 0.3 governance kernel, deliver the **control plane, elevated routing, unload/isolation, infra proxies, examples, and engineering**, with critical correctness closed.

### 🚀 Dashboard control plane

- Overview page (stats cards, recent events, Ling list)
- Lifecycle management center (deploy / start-stop / reload / unload)
- Service Playground: real invoke by default, optional simulation; case save/replay and proportional routing
- Governance center: resource permissions, invocation policies, presets, rule overview matrix
- Canary decision assist (e.g. error-rate fluctuation hints)
- Contract / provider weight and migration-progress controls; stress test runs the real Pipeline with full-contract L0 routing (v1/v2 reflect real weight split including LingCore baseline)
- Package management; SQLite persistence; access-token auth; read-only mode
- Monitoring: JVM / per-collector GC, Ling resource drill-down, leak-detection records, thread-pool SPI stats, metric trends
- Log console (pause/resume), themes & i18n, CORS / rate limiting

### 🚀 Core runtime & governance chain

- **Architecture reorg**: core `router` → `routing` (routing promoted to a dedicated package with full Provider/Contract/Instance/Migration routers); new `runtime` package (`RuntimeMode` abstraction, fixed vs switchable); api adds `constant` (`LingCoreConstants` sentinel) and `resilience` (`FallbackCause` / `FallbackProvider` SPI) packages.
- Pipeline elevation: `ContractProviderRoutingFilter` (L0 provider weights) + `InvocationPolicyPrefillFilter` + existing resilience / permission / thread-isolation chain
- **N-way weight routing**: multiple providers coexist under one contract; `ProviderWeightRouter` splits traffic by weight (binary is just the N=2 special case; N≥3 means multi-version coexistence / multi-tenancy); warns only when candidate count changes, never blocks business
- **Versioned provider registration**: `registerProvider(contractId, lingId, version, weight)` always carries a version—Ling is `lingId:version` (truth source `DefaultLingContext.getVersion()`, derived from the bound instance), LingCore is bare `lingcore-app`; retirement uses `evictProvider(lingId, version)` for precise cleanup while other versions keep serving; with no LingCore baseline the first provider is promoted to baseline 100 (no silent all-zero routing)
- Multi-version canary and service-routing end-to-end; runtime override via `ProviderWeightRouter`
- Implicit interface registration switch (`implicitRegistration`)
- Microkernel SPI decoupling; ecosystem parent-delegate packages split out of core and injected by runtime
- Unload cleanup via `LingUnloadHook` (thread / JDBC / logging / RMI / ShutdownHook / Debugger, etc.); reclaim always runs full unload (tearDown + unload hooks + close ClassLoader + leak detection); `InstancePool.cleanupIdleInstances` / `forceCleanupAll` require a destroyer (no tearDown-only half-reclaim leaking LingClassLoader); replacing the default instance retires the old version with precise per-version provider cleanup
- Dual-FSM write authority centralized in `InstanceCoordinator` / `RuntimeCoordinator`; `LingRuntime` is a read-only aggregate
- Traffic vs `RuntimeStatus`: cut traffic via 2D routing/weights; status only reflects instance aggregate facts
- Runtime snapshots keyed by `instanceId`; `RuntimeCoordinator.unregister` on deploy failure / full undeploy
- Forced parent-delegate exclusivity; configurable `force-drain-on-timeout`
- Distinct bulkhead error `BULKHEAD_FULL` (`LING-2003`); load-time dangerous-API FORBIDDEN
- Assembly tree: `LifecycleEngineConfig` / `FilterRegistryConfig` builders; no global static `LingFrameConfig` singleton; fail-fast `init`
- `AsyncLingEvent` marker; partitioned `InvocationContext`; exceptions unified as `LingInvocationException`
- Configurable resilience parameters; `GovernancePolicy.copy` / deep-copy guards
- **SIMULATION dry-run semantics**: stress test runs full-contract L0 routing (`forContractSimulation`, no targetLingId lock) across all candidates including LingCore; SIMULATION contract-level dry-run never resolves real method handles; SIMULATION traffic bypasses rate-limit / circuit-breaker budgets, not polluting real resilience state
- Public terminology: LingCore / Ling

### 🚀 Infrastructure

- Cache governance proxies and namespace isolation (Caffeine / Spring Cache / Redis wrap paths)
- Storage proxy hardening; connection-level destructive calls (e.g. Connection.abort) blocked on the proxy
- **MyBatis-Plus proxy**: new lingframe-infra-mybatis-plus, interceptor-layer governance for pagination and audit
- Boundary documented: storage governance is mainly the Spring DataSource Bean proxy path; DriverManager / non-Bean pools are outside that chain

### 🚀 Config & benchmark modules

- **lingframe-config**: extracted config aggregation layer, unifying LingFrameConfig / LingFrameInfo / LingRuntimeConfig carriers, decoupling core from runtime config
- **lingframe-benchmark (-Pbenchmark)**: JMH suite covering Pipeline / FSM / ClassLoader / end-to-end lifecycle

### 🚀 Examples & benchmarks

- **Getting started**: `lingframe-example-lingcore-app` + user / order (+ canary) + Shared API
- **Legacy gradual migration**: `lingframe-example-ling-mall` → `lingframe-example-saas-mall` (oauth / refund / seckill, …)
- Order canary and related samples; `application-prod.yaml.example` beside local defaults
- `lingframe-benchmark`: JMH suite for pipeline / FSM / classloader / end-to-end lifecycle (with JVM hooks)

### 🛠 Runtime adapters & engineering

- Auto-configuration exclude / service-registration exclude packages
- ClassLoader unload and Spring ecosystem cleanup improvements:
  - Web metadata as runtime source of truth: pre-extract permission / audit / OpenAPI (including class-level `@Tag`) at register time; ling HTTP path trusts `WebInterfaceMetadata` instead of re-merging annotations on ling `Method`s per request
  - `LingWebMetadataExtractor` for scan; post-register bounded annotation-cache purge via `LingScanCachePurger`
  - `BridgeMethodResolver.cache` (MethodClassKey + ConcurrentReferenceHashMap Soft) and related static maps deep-cleared; HttpClient SelectorManager TCCL cleared first
  - Unload regression: development path + production path (with Web dispatch) dual-stack ClassLoader collectability gates
- **Dual-stack layout closed (no reflective Servlet probing)**:
  - Runtime: `lingframe-spring-boot-starter` (shared) + `lingframe-spring-boot2-starter` / `lingframe-spring-boot3-starter` (typed javax / jakarta: repeatable-read, web governance filters, gateway mapping, AutoConfig)
  - Dashboard: **single GAV** + matrix source sets (`src/java-javax` / `src/java-jakarta` + matching tests; `build-helper` adds them per profile)
  - Shared lifecycle hooks use `InitializingBean` / `DisposableBean` instead of `javax.annotation` binding one stack
- SB3: `LingGatewayHandlerMapping` parity with SB2; CI smoke + example IT
- Multi-module unit and contract tests expanded

### 📦 Docs & specs

- Doc system reorg: root-level `DEVELOPMENT_MANUAL.md` / `QUICK_START.md` / `MANIFESTO.md` / `WHY.md` etc. migrated to `docs/zh-CN/` and `docs/en/` (separate zh/en dirs); root keeps only `README` / `CHANGELOG` / `CONTRIBUTING` / `AGENTS`
- Public `production-hardening`, Shared API security boundary, roadmap V0.4.0 section
- Examples map: `lingframe-examples/README.md`
- Shared-Spring isolation boundary and unload SLA documented in release notes / production-hardening / architecture summary

### 🛠 CI & engineering

- New `.github/workflows/ci.yml` dual-stack CI (SB2+JDK8 / SB3+JDK17); issue & PR templates
- New `scripts/test-dual-stack.ps1` local dual-stack regression script
- Quality gates: `checkstyle` / `spotbugs` / `jacoco` on `verify`; `-Pintegration-check` enables integration-check profile

### ⚠️ Notes

- Storage governance is mainly the Spring `DataSource` Bean proxy path; `DriverManager` / non-Bean pools are outside that chain
- Shared API JARs already in the shared boundary are not hot-updatable/hot-unloadable; breaking changes require a process restart
- By default the process **shares** `org.springframework.*` (runtime parent-delegate); process-level static-cache writes are a model cost of shared Spring
- Unload SLA: after a proper undeploy, `LingClassLoader` is GC-collectable (provable); not “Spring static maps never hold ling type keys at runtime”

## [V0.3.0] - 2026-03-23

### 🚀 Added

- Converged unified governance Pipeline around `InvocationPipelineEngine` and `FilterRegistry`, explicitly supporting `NORMAL`, `SIMULATION`, and `GOVERN_ONLY` execution modes.
- Allowed Ling-to-Ling invocations, Spring Boot 2/3 Web requests, LingCore Bean interceptions, and Dashboard simulations to share the same unified kernel path.
- Enabled the Dashboard to execute simulations via the actual governance chain, and output real `trace`, `audit`, `circuit-breaker`, `lifecycle`, and `leak-detection` events via SSE.

### 🛠 Changed

- Converged runtime state write authority securely into `InstanceStatus`, `RuntimeStatus`, `InstanceCoordinator`, and `RuntimeCoordinator`.
- Lifecycle orchestration became more explicit: Deployments, hot-reloads, unload after draining, and cleanup form a clearer decoupled runtime path.
- Restructured `SharedApiManager` to enforce a strict shared API boot order: preloading -> register packages -> freeze boundary -> load lings.
- Unload cleanup was officially incorporated into Pipeline resource eviction and memory leak diagnostics.

### ⚠️ Notes

- The delivery baseline for `0.3.0` involves Phase 3 capabilities: Pipeline convergence, runtime state convergence, Dashboard governance/control surface, lifecycle orchestration, Shared API boundary freeze, and disciplines related to long-running stability.

## [V0.2.0] - 2026-02-23

### 🚀 Features

- **Resilience Governance**: Fully implemented sliding window circuit breaking, token bucket rate limiting, retry, and fallback mechanisms inside the `GovernanceKernel`.
- **Ecosystem Compatibility**: On top of JDK 17 / Spring Boot 3.x, added massive backward-compatibility support for JDK 8 and Spring Boot 2.7.x.
- **Developer Experience**:
    - Introduced a `dev-mode` facilitating a more forgiving runtime permission model during debugging.
    - Achieved auto-activation post-installation during dev-mode, bypassing manual status toggling.
    - Integrated SpringDoc (Swagger) support with split group capabilities (Core, Lings, Apps).

### 🛠 Refactoring

- **Global Terminology Synchronization**: Renamed all instances of "Plugin" into "Ling", and all instances of "Host" into "LingCore" to enforce profound concept unity.
- **Enhanced Isolation**: Refined `SmartServiceProxy` and `InvocationExecutor` to bolster Ling boundary auditing capacities.
- **Infrastructure SPIs**: Stabilized proxies backing `StorageService` and `CacheService`.

### 🐛 Bug Fixes

- **Memory Leak Mitigation**: Systematically sanitized Spring cache retention and Jakarta EL/Objenesis static cache references, substantially relieving ClassLoader memory leaks during hot reloads.
- **Path Matching Compatibility**: Overcame numerous Swagger mapping path mismatches resulting from Spring Boot URL generation rules.

## [V0.1.0-Preview] - 2026-02-01

> **Preview Version**: This iteration validated the core feasibility of governed runtimes strictly within a single JVM process.
> Focus: Boundaries, Isolation, and Control.

### 🚀 Features

#### Core Architecture (JVM Runtime Governance)
- **3-Layer ClassLoader Architecture**: Formed the `HostClassLoader` -> `SharedApiClassLoader` -> `LingClassLoader` hierarchy, assuring robust isolation aligned with controlled sharing constraints.
- **Child-First Loader Semantics**: Empowered Lings to prioritize their own internal dependencies first, preventing fatal dependency-hells against LingCore payloads.
- **Spring Context Isolation**: Every Ling operates within isolated disparate Spring `ApplicationContext` closures to secure Bean segregation and lifecycle autonomy.

#### Ling System
- **Lifecycle Management**: Fostered complete coverage for `LOAD`, `START`, `STOP`, `UNLOAD`, and hot-reloading through the `LingManager`.
- **Manifest Properties**: Stabilized the `ling.yml` descriptor contract to dictate metadata, required capabilities, and dependency tree links.
- **Service Exports & Imports**:
  - `@LingService`: Exposes a Bean outwardly as a boundary-traversing governance-aware service.
  - `@LingReference`: Injects proxy handles bound to services originating from diverse Lings or the LingCore.

#### Governance & Security
- **Permission Modeling**:
  - Furnished `GovernancePolicy` to orchestrate Access Control Lists (ACL).
  - Deployed `@RequiresPermission` validating granular method-level execution access.
- **Auditing and Tracing**:
  - Implemented the `@Auditable` annotation for recording sensitive executions.
  - Constructed `TraceContext` to securely ferry request metadata across isolation borders.
- **Traffic Routing**:
  - `LabelMatchRouter` operationalized, powering Label-driven matching architectures and canary releases.

#### Dashboard & Operations
- **Visual Administrative Control Surface**: Deployed a rudimentary Web Dashboard to observe states and tweak profiles.
- **Dynamic Capabilities**:
  - Starting and Stopping Lings via HTTP boundaries.
  - Hot Reloading Lings devoid of total JVM reboot mandates.
  - Splicing Permissions actively during runtime.

#### Infrastructure SPI
- **Proxy Scaffolding**:
  - Bootstrapped `StorageService` logic for file IO.
  - Configured `CacheService` for localized and off-machine caches.

### ⚠️ Technical Boundaries & Constraints
- **Single Process Exclusivity**: Designed purposefully as a monolith modifier, not as an inter-machine RPC microservices framework.
- **Framework Grounding**: Base compiled against JDK 17 (LTS) & Spring Boot 3.x.
- **To-Be-Implemented Features**: (Referred to Phase 3) Circuit breaking, Rate Limiting, and Fallbacks mappings established but pending comprehensive execution deployments.

### 🛠 Infrastructure Tooling
- Instated standard Maven multi-Ling compositional profiles (`core`, `api`, `dashboard`, `runtime`, `infrastructure`).
- Fused `maven-compiler-Ling` elements with `flatten-maven-Ling` strategies to harmonize build environments.
