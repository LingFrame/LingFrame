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
- ⏳ Dashboard UI Polish

---

## Phase 3: Complete Governance Capabilities ✅ Completed

**Goal**: Comprehensive Runtime Governance

### Implemented
- ✅ Permission Control (@RequiresPermission)
- ✅ Security Audit (@Auditable)
- ✅ Full Tracing (TraceContext)
- ✅ Canary Release (CanaryRouter)
- ✅ Circuit Breaker (SlidingWindowCircuitBreaker)
- ✅ Rate Limiting (TokenBucketRateLimiter)
- ✅ Timeout Control & Fallback (SmartServiceProxy integrated)
- ✅ Retry (GovernanceKernel integrated)
- ✅ Complex Routing (LabelMatchRouter with Weight-based and Tag-based routing)

---

## Phase 4: Observability ⏳ Planned

**Goal**: Comprehensive Monitoring Capabilities

### System Metrics
- CPU / Memory Usage
- JVM Metrics (GC, Heap, Thread)
- System Load

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
- ✅ Ecosystem SPIs (LingInvocationFilter, ServiceExporter, LingContextCustomizer, LingDeployService)
- ✅ Developer Experience (Auto-activate units after installation in devMode)

### To Be Implemented
- ⏳ Message Proxy (Kafka / RabbitMQ)
- ⏳ Search Proxy (Elasticsearch)
- ⏳ More Infrastructure Proxies
- ⏳ Complete Examples and Tutorials
