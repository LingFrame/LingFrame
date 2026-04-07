# Changelog

All notable changes to this project will be documented in this file.

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
