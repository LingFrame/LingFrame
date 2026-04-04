# Roadmap

This document describes the evolution roadmap of LingFrame.

> 💡 For currently implemented features, please refer to [Architecture Design](architecture.md)

## Positioning

> **JVM Runtime Governance Kernel**

Core Capabilities:

- **Observability**
- **Controllability**
- **Auditability**

---

## Phase 1: Three-Tier Architecture ✅ Completed

**Goal**: Verify feasibility of in-JVM governance

- ✅ Unit Lifecycle Management
- ✅ Child-First ClassLoader Isolation
- ✅ Spring Parent-Child Context Isolation
- ✅ Three-Tier ClassLoader Architecture (SharedApiClassLoader)
- ✅ Service Routing (@LingService + @LingReference)
- ✅ Basic Permission Governance
- ✅ Infrastructure Proxy (Storage / Cache)

---

## Phase 2: Visual Governance ✅ Basic Completion

**Goal**: Visual Operation Entry

- ✅ Dashboard Unit Management
- ✅ Unit Status Control (Start/Stop/Hot Swap)
- ✅ Dynamic Permission Adjustment
- ✅ Canary Release Configuration
- ✅ Simulation APIs (resource / IPC / stress routing)
- ✅ SSE event stream (`/lingframe/dashboard/stream`)
- ✅ JVM metrics and ling health snapshots
- ⏳ Dashboard UI Polish

---

## Phase 3: Complete Governance Capabilities ✅ Completed

**Goal**: Comprehensive Runtime Governance

### Implemented
- ✅ Permission Control (@RequiresPermission)
- ✅ Security Audit (@Auditable)
- ✅ Full Tracing (LingCallContext)
- ✅ Canary Release (CanaryRouter)
- ✅ Circuit Breaker (SlidingWindowCircuitBreaker)
- ✅ Rate Limiting (TokenBucketRateLimiter)
- ✅ Timeout Control & Fallback (SmartServiceProxy integrated)
- ✅ Retry (GovernanceKernel integrated)
- ✅ Complex Routing (LabelMatchRouter with Weight-based and Tag-based routing)
- ✅ Unified invocation governance spine (`InvocationPipelineEngine` + `FilterRegistry`)
- ✅ Three execution modes: `NORMAL`, `SIMULATION`, and `GOVERN_ONLY`
- ✅ Shared governance kernel for web requests, LingCore beans, and dashboard simulation
- ✅ Dual runtime state model (`InstanceStatus` / `RuntimeStatus`)
- ✅ Converged state ownership through `InstanceCoordinator` / `RuntimeCoordinator`
- ✅ Lifecycle orchestration through `DefaultLingLifecycleEngine`
- ✅ Unload cleanup, resource eviction, and leak detection as formal runtime responsibilities
- ✅ Shared API bootstrap ordering and boundary freezing (`SharedApiManager`)

---

## Phase 4: Observability 🔄 In Progress

**Goal**: Comprehensive Monitoring Capabilities

### Already Available
- ✅ Dashboard SSE monitoring stream
- ✅ trace / audit / lifecycle / circuit-breaker / leak-detection event output
- ✅ JVM / system metrics collection (CPU, process CPU load, total memory, heap, non-heap, metaspace, class loading, threads, GC, system load)
- ✅ per-ling and all-ling health snapshots

### System Metrics
- ✅ CPU / process CPU load
- ✅ total memory / heap / non-heap / metaspace
- ✅ JVM metrics (GC, class loading, threads)
- ✅ system load

### Unit Metrics
- Call Count, Success Rate, Latency per Unit
- Unit Resource Usage
- Exception Statistics

### Technical Solution
- Integrate Micrometer
- Support Prometheus Collection
- Custom Metrics Extension

---

## Phase 5: Ecosystem Perfection 🔄 In Progress

**Goal**: Complete Infrastructure Proxy Ecosystem and Exoskeleton Extensions

### Implemented
- ✅ Ecosystem extension SPIs are in place (`LingInvocationFilter`, `ServiceExporter`, `LingContextCustomizer`, `LingDeployService`)
- ✅ `LingInvocationFilter` is already wired into the unified governance pipeline and can extend the filter chain through runtime assembly
- ✅ `LingDeployService` already has a default implementation (`DefaultLingDeployService`, currently supporting local files and `http/https` downloads)
- ✅ Developer Experience (Auto-activate units after installation in devMode)

### Current Stage Notes
- `ServiceExporter` and `LingContextCustomizer` are already public extension points, but they are still more like outer integration skeletons than a fully populated ecosystem
- The current goal is to make extension boundaries real first, not to claim that the whole ecosystem is already finished

### To Be Implemented
- ⏳ Message Proxy (Kafka / RabbitMQ)
- ⏳ Search Proxy (Elasticsearch)
- ⏳ More Infrastructure Proxies
- ⏳ Complete Examples and Tutorials
