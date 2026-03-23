# Changelog

All notable changes to this project will be documented in this file.

## [V0.3.0] - 2026-03-23

### 🚀 Added

- Unified governance pipeline around `InvocationPipelineEngine` and `FilterRegistry`, with explicit phases and execution modes: `NORMAL`, `SIMULATION`, and `GOVERN_ONLY`
- Reuse of the same kernel path for ling-to-ling invocation, Spring Boot 2/3 web governance, LingCore bean interception, and dashboard simulation
- Dashboard simulation through the real governance chain, plus SSE event streaming for trace, audit, circuit-breaker, lifecycle, and leak-detection events

### 🛠 Changed

- Runtime state ownership now converges around `InstanceStatus`, `RuntimeStatus`, `InstanceCoordinator`, and `RuntimeCoordinator`
- Lifecycle orchestration is more explicit: deploy, side-by-side reload, drain-before-unload, and cleanup now form a clearer runtime path
- `SharedApiManager` enforces the shared API bootstrap order: preload, register packages, freeze the boundary, then load lings
- Unload cleanup now includes pipeline resource eviction and leak diagnostics as part of runtime responsibility

### ⚠️ Notes

- Public release scope: pipeline convergence, runtime convergence, dashboard governance/control surface, lifecycle orchestration, shared API boundary freezing, and long-running stability work

## [V0.2.0] - 2026-02-23

### 🚀 New Features

- **Resilience Governance**: Full implementation of Circuit Breaking (Sliding Window), Rate Limiting (Token Bucket), Retry, and Fallback mechanisms in `GovernanceKernel`.
- **Ecosystem Compatibility**: Added support for JDK 8 and Spring Boot 2.7.x, alongside the primary JDK 17 / Spring Boot 3.x support.
- **Developer Productivity**:
    - New `dev-mode` for loose runtime permissions.
    - Automatic activation of Lings upon installation in development mode.
    - Integrated SpringDoc (Swagger) support with API grouping (Core, Lings, Host).

### 🛠 Refactoring & Improvements

- **Global Terminology Refactor**: Renamed all "Plugin" related terms to "Ling" and "Host" to "LingCore" for conceptual consistency.
- **Improved Isolation**: Enhanced `SmartServiceProxy` and `InvocationExecutor` to bolster unit boundary auditing.
- **Infrastructure SPI**: Optimized `StorageService` and `CacheService` proxies for better stability.

### 🐛 Bug Fixes

- **Memory Leak Mitigation**: Systematically addressed potential ClassLoader memory leaks during Ling hot-swapping by clearing known Spring caches and Jakarta EL/Objenesis static references.
- **Path Matching**: Fixed various path matching issues in Swagger and Web interface mappings.

## [V0.1.0-Preview] - 2026-02-01

> **Maiden Phase (Preview)**: This release validates the feasibility of in-process JVM runtime governance.
> Focus: Boundaries, Isolation, and Control.

### 🚀 New Features

#### Core Architecture (JVM Runtime Governance)
- **Three-Tier ClassLoader Architecture**: Implemented `HostClassLoader` -> `SharedApiClassLoader` -> `LingClassLoader` hierarchy to ensure strict isolation while allowing controlled sharing.
- **Child-First Class Loading**: Lings load their own dependencies first to prevent "Dependency Hell" with the LingCore application.
- **Spring Context Isolation**: Each ling runs in its own Spring `ApplicationContext`, ensuring bean isolation and distinct lifecycles.

#### ling System
- **Lifecycle Management**: Full support for `LOAD`, `START`, `STOP`, `UNLOAD`, and hot-reload capabilities via `LingManager`.
- **Manifest Configuration**: Defined `ling.yml` standard for declaring metadata, dependencies, and required capabilities.
- **Service Export/Import**:
  - `@LingService`: Export beans as cross-boundary services.
  - `@LingReference`: Inject services from other Lings or the LingCore.

#### Governance & Security
- **Permission Control**:
  - Implemented `GovernancePolicy` for defining Access Control Lists (ACLs).
  - Added `@RequiresPermission` for fine-grained, method-level authorization.
- **Audit & Trace**:
  - `@Auditable` annotation for recording sensitive operations.
  - `TraceContext` for propagating request metadata across ling boundaries.
- **Traffic Routing**:
  - `LabelMatchRouter` implementation for canary releases and tag-based traffic routing.

#### Dashboard & Operations
- **Visual Management**: Web-based Dashboard (preview) for monitoring ling status and managing configurations.
- **Dynamic Control**:
  - Start/Stop Lings via UI/API.
  - Hot-reload Lings without restarting the JVM.
  - Adjust permission policies at runtime.

#### Infrastructure SPI
- **Proxy Abstractions**:
  - `StorageService` proxy for file operations.
  - `CacheService` proxy for caching (Local/Remote).

### ⚠️ Technical Boundaries & Limitations
- **Single Process Only**: Designed for monolithic modification, not a distributed microservice framework.
- **Compatibility**: Built for JDK 17 (LTS) and Spring Boot 3.x.
- **Pending Features** (Phase 3): Circuit Breaking, Rate Limiting, and Fallback mechanisms are defined but not yet fully operational.

### 🛠 Infrastructure
- Established standard Maven multi-unit project structure (`core`, `api`, `dashboard`, `runtime`, `infrastructure`).
- Integrated `maven-compiler-Ling` and `flatten-maven-Ling` for build standardization.
