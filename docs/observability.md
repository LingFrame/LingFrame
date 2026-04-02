# Observability

This document describes LingFrame's current observability capabilities.

---

## Currently Implemented

### 1. Dashboard SSE Event Stream

LingFrame provides real-time event stream via Dashboard SSE (Server-Sent Events).

**Endpoint**: `GET /lingframe/dashboard/stream`

**Supported Event Types**:

| Event Type | Description |
|------------|-------------|
| `trace` | Invocation trace events |
| `audit` | Audit events |
| `lifecycle` | Lifecycle events |
| `circuit-breaker` | Circuit breaker state changes |
| `leak-detection` | Leak detection events |

**Usage Example**:

```javascript
const eventSource = new EventSource('/lingframe/dashboard/stream');

eventSource.onmessage = (event) => {
    const data = JSON.parse(event.data);
    console.log('Event:', data);
};
```

### 2. JVM and System Metrics

Get JVM metrics snapshot via Dashboard API:

**Endpoint**: `GET /lingframe/dashboard/lings/metrics`

**Returned Content**:

| Category | Metrics |
|----------|---------|
| CPU | System CPU usage, Process CPU load |
| Memory | Total memory, Heap, Non-heap, Metaspace |
| JVM | GC count/duration, Class loading, Threads |
| System | System load |

### 3. Ling Health Snapshots

**Single Ling Health Snapshot**:
```
GET /lingframe/dashboard/lings/{lingId}/health
```

**All Lings Health Snapshot**:
```
GET /lingframe/dashboard/lings/health/all
```

The response now includes:

- ling-level `summary`
- version-level `versions`

Available fields include:

- `qps`
- `errorRate`
- `avgLatencyMs`
- `p99LatencyMs`
- `activeRequests`
- `healthStatus`

Dashboard already consumes and displays both ling summary and per-version comparison.

### 4. Governance Signal Snapshots

**All Governance Signals**:
```
GET /lingframe/dashboard/lings/governance/all
```

Currently exposed:

- `rateLimitedRequests`
- `timeoutRequests`
- `circuitOpenedCount`
- `circuitOpenRejections`
- `bulkheadRejectedRequests`
- `recoveryCount`

Both ling-level `summary` and version-level `versions` are available.

### 5. Traffic Statistics

**Get Ling Traffic Stats**:
```
GET /lingframe/dashboard/lings/{lingId}/stats
```

Returns: Total requests, version distribution, active requests, window start time.

**Reset Statistics**:
```
POST /lingframe/dashboard/lings/{lingId}/stats/reset
```

### 6. Micrometer Bridge

`lingframe-dashboard` now provides an optional Micrometer bridge.

When the host application provides a `MeterRegistry`, LingFrame registers gauges for:

- `lingframe.ling.health.qps`
- `lingframe.ling.health.error_rate`
- `lingframe.ling.health.p99_latency_ms`
- `lingframe.ling.health.active_requests`
- `lingframe.ling.version.health.qps`
- `lingframe.ling.version.health.error_rate`
- `lingframe.ling.governance.rate_limited_total`
- `lingframe.ling.governance.timeout_total`
- `lingframe.ling.governance.circuit_opened_total`
- `lingframe.ling.governance.circuit_rejected_total`

Notes:

- LingFrame now ships the bridge, but does not force a specific monitoring backend
- If the host also adds `micrometer-registry-prometheus` and exposes actuator endpoints, Prometheus can scrape the metrics directly

### 7. EventBus Mechanism

LingFrame has a built-in EventBus supporting two subscription modes:

**Ling-level Subscription** (auto-cleaned on ling unload):
```java
eventBus.subscribe(lingId, MyEvent.class, event -> {
    // Handle event
});
```

**Global Subscription** (for framework-level components):
```java
eventBus.subscribeGlobal(MyEvent.class, event -> {
    // Handle event
});
```

## Prometheus Integration in Hosts

Minimum host requirements:

1. Add `spring-boot-starter-actuator`
2. Add `micrometer-registry-prometheus`
3. Expose `/actuator/prometheus`

Example configuration:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    prometheus:
      enabled: true
```

The sample app `lingframe-example-lingcore-app` has been updated with this setup and can be used as a working reference.

---

## Logging Configuration

### Recommended Log Levels

```yaml
logging:
  level:
    root: INFO
    com.lingframe: INFO
    # Enable for debugging
    com.lingframe.core.fsm: DEBUG
    com.lingframe.core.pipeline: DEBUG
    com.lingframe.core.classloader: DEBUG
```

### Audit Logging

Methods annotated with `@Auditable` will log audit entries:

```java
@Auditable(action = "createOrder", resource = "order")
public OrderInfo createOrder(CreateOrderRequest request) {
    // ...
}
```

---

## Working with Dashboard

Dashboard is the main entry point for observability capabilities. See [Dashboard Documentation](dashboard.md).
