# Roadmap

This document outlines the evolutionary roadmap for LingFrame.

> 💡 For currently implemented codebase capabilities, please refer to [Architecture](architecture.md).

## Positioning

> **JVM-level Runtime Governance Kernel**

Core Capabilities:

- **Observability** (Making behavior visible)
- **Controllability** (Making behavior steerable)
- **Auditability** (Making behavior trackable)

---

## Phase 1: 3-Layer Architecture ✅ Completed

**Goal**: Validate the feasibility of intra-JVM governance.

- ✅ Ling lifecycle management
- ✅ Child-First classloader isolation
- ✅ Spring parent-child context isolation
- ✅ 3-Layer classloader boundaries (`SharedApiClassLoader`)
- ✅ Service routing (`@LingService` + `@LingReference`)
- ✅ Foundational permission governance
- ✅ Infrastructure proxy chains (Storage / Cache)

---

## Phase 2: Visualized Governance ✅ Progressing

**Goal**: Visual operations control surface.

- ✅ Dashboard Ling management
- ✅ Ling state commands (start/stop/reload)
- ✅ Dynamic permission adjustment
- ✅ Canary routing configuration
- ✅ Simulation testing endpoints (Resource / IPC / Stress routing)
- ✅ SSE event streams (`/lingframe/dashboard/stream`)
- ✅ JVM metrics & Ling health snapshots
- ⏳ Dashboard UI polish

---

## Phase 3: Comprehensive Governance Capabilities ✅ Completed

**Goal**: Implement comprehensive runtime governance logic.

### Achieved
- ✅ Permission control (`@RequiresPermission`)
- ✅ Security Auditing (`@Auditable`)
- ✅ Full-chain tracing (`LingCallContext`)
- ✅ Canary routing (`CanaryRouter`)
- ✅ Circuit breakers (`SlidingWindowCircuitBreaker`)
- ✅ Rate limiting (`TokenBucketRateLimiter`)
- ✅ Timeout constraints & Fallbacks (Integrated within `SmartServiceProxy`)
- ✅ Retry mechanisms (Based on GovernanceKernel retry counts)
- ✅ Complex routing distribution (Label matching & weights via `LabelMatchRouter`)
- ✅ Unified overarching invocation **governance** chain (`InvocationPipelineEngine` + `FilterRegistry`)
- ✅ Tri-mode execution: `NORMAL` / `SIMULATION` / `GOVERN_ONLY`
- ✅ Shared **governance** kernel across entries (not always the same terminal executor):
  - Ling IPC / service calls → `NORMAL` full pipeline including terminal invoke
  - Spring Web / LingCore Bean AOP → `GOVERN_ONLY` then LingCore-side framework business execution
  - Dashboard simulate → `SIMULATION`
- ✅ Dual-layer runtime state model (`InstanceStatus` / `RuntimeStatus`)
- ✅ Converged state write authority down to `InstanceCoordinator` / `RuntimeCoordinator`
- ✅ Centralized lifecycle orchestration to `DefaultLingLifecycleEngine`
- ✅ Added formally disciplined active resource eviction, teardowns, and leak checks
- ✅ Explicit Shared API boot phases and boundary freezes (`SharedApiManager`)

---

## Phase 4: Observability 🔄 Work in Progress

**Goal**: Robust monitoring solutions.

### Achieved
- ✅ Dashboard SSE event streams
- ✅ Standardized payload dumps for trace/audit/lifecycle/circuit/leak-detection events
- ✅ Out-of-the-box JVM & System metrics (CPU, Memory, Metaspace, GC, Threads, Load)
- ✅ Single Ling & All Ling health snapshots

### System Metrics
- ✅ CPU / Process CPU load
- ✅ Total Memory / Heap / Non-heap / Metaspace
- ✅ JVM internals (GC runs, Threads, Class counts)
- ✅ System Load

### Ling Metrics
- Invocation counts, success rates, latency distribution.
- Ling resource consumption.
- Exception distribution statistics.

### Implementation Stack
- Micrometer integration pattern.
- Supporting Prometheus scraping pipelines.
- Custom metrics extension paths.

---

## Phase 5: Ecosystem Expansions 🔄 Work in Progress

**Goal**: Mature infrastructure proxy ecosystem and exoskeleton integrations.

### Achieved
- ✅ Ecosystem SPI extension points finalized (`LingInvocationFilter`, `ServiceExporter`, `LingContextCustomizer`, `LingDeployService`)
- ✅ `LingInvocationFilter` integrated directly into the unified governance Pipeline, allowing dynamic post-boot Filter chain extensions
- ✅ Default implementations supplied for `LingDeployService` indicating local/http/https package resolutions
- ✅ Dev experience boosters (auto-activation post-install under `devMode`)

### Current State Assessment
- `ServiceExporter` and `LingContextCustomizer` are structurally exposed but act heavily as exoskeleton sockets. External ecosystem adaptors will eventually need to be supplemented.
- The overarching goal of the ecosystem extension phase was "setting up the boundary first", not announcing all implementations finished.

### Upcoming
- ⏳ Messaging proxy (Kafka / RabbitMQ)
- ⏳ Search proxy (Elasticsearch)
- ⏳ More infrastructure proxy adapters
- ⏳ Exhaustive sets of examples & tutorials

