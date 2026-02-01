# Changelog

All notable changes to this project will be documented in this file.

## [V0.1.0-Preview] - 2026-02-01

> **Maiden Phase (Preview)**: This release validates the feasibility of in-process JVM runtime governance.
> Focus: Boundaries, Isolation, and Control.

### 🚀 New Features

#### Core Architecture (JVM Runtime Governance)
- **Three-Tier ClassLoader Architecture**: Implemented `HostClassLoader` -> `SharedApiClassLoader` -> `PluginClassLoader` hierarchy to ensure strict isolation while allowing controlled sharing.
- **Child-First Class Loading**: Plugins load their own dependencies first to prevent "Dependency Hell" with the host application.
- **Spring Context Isolation**: Each plugin runs in its own Spring `ApplicationContext`, ensuring bean isolation and distinct lifecycles.

#### Plugin System
- **Lifecycle Management**: Full support for `LOAD`, `START`, `STOP`, `UNLOAD`, and hot-reload capabilities via `PluginManager`.
- **Manifest Configuration**: Defined `plugin.yml` standard for declaring metadata, dependencies, and required capabilities.
- **Service Export/Import**:
  - `@LingService`: Export beans as cross-boundary services.
  - `@LingReference`: Inject services from other plugins or the host.

#### Governance & Security
- **Permission Control**:
  - Implemented `GovernancePolicy` for defining Access Control Lists (ACLs).
  - Added `@RequiresPermission` for fine-grained, method-level authorization.
- **Audit & Trace**:
  - `@Auditable` annotation for recording sensitive operations.
  - `TraceContext` for propagating request metadata across plugin boundaries.
- **Traffic Routing**:
  - `LabelMatchRouter` implementation for canary releases and tag-based traffic routing.

#### Dashboard & Operations
- **Visual Management**: Web-based Dashboard (preview) for monitoring plugin status and managing configurations.
- **Dynamic Control**:
  - Start/Stop plugins via UI/API.
  - Hot-reload plugins without restarting the JVM.
  - Adjust permission policies at runtime.

#### Infrastructure SPI
- **Proxy Abstractions**:
  - `StorageService` proxy for file operations.
  - `CacheService` proxy for caching (Local/Remote).

### ⚠️ Technical Boundaries & Limitations
- **Single Process Only**: Designed for monolithic modification, not a distributed microservice framework.
- **Compatibility**: Built for JDK 21 (LTS) and Spring Boot 3.x.
- **Pending Features** (Phase 3): Circuit Breaking, Rate Limiting, and Fallback mechanisms are defined but not yet fully operational.

### 🛠 Infrastructure
- Established standard Maven multi-module project structure (`core`, `api`, `dashboard`, `runtime`, `infrastructure`).
- Integrated `maven-compiler-plugin` and `flatten-maven-plugin` for build standardization.
