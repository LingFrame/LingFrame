# Observability

This document describes the observability capabilities already implemented in LingFrame.

---

## Currently Implemented

### 1. Dashboard SSE Event Stream

LingFrame provides a real-time event stream through Dashboard SSE (Server-Sent Events).

**Endpoint**: `GET /lingframe/dashboard/stream`

**Currently supported event types**:

| Event Type | Description |
|------------|-------------|
| `trace` | Invocation trace events |
| `audit` | Audit events |
| `lifecycle` | Lifecycle events |
| `circuit-breaker` | Circuit-breaker state changes |
| `leak-detection` | Leak-detection events |

**Usage Example**:

```javascript
const eventSource = new EventSource('/lingframe/dashboard/stream');

eventSource.onmessage = (event) => {
    const data = JSON.parse(event.data);
    console.log('Event:', data);
};
```

### 2. JVM and System Metrics

You can fetch JVM metrics snapshots through the Dashboard API:

**Endpoint**: `GET /lingframe/dashboard/lings/metrics`

**Current response content**:

| Category | Metrics |
|----------|---------|
| CPU | System CPU usage, process CPU load |
| Memory | Total memory, heap, non-heap, metaspace |
| JVM | GC count/duration, class loading, threads |
| System | System load |

### 3. Ling Health Snapshots

**Single-ling health snapshot**:

```
GET /lingframe/dashboard/lings/{lingId}/health
```

**All-ling health snapshot**:

```
GET /lingframe/dashboard/lings/health/all
```

The response now contains:

- ling-level `summary`
- version-level `versions`

Available fields include:

- `qps`
- `errorRate`
- `avgLatencyMs`
- `p99LatencyMs`
- `activeRequests`
- `healthStatus`

Dashboard already consumes and displays these values for both overview and version comparison.

### 4. Governance Signal Snapshots

**All governance signals**:

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

Both ling-level `summary` and version-level `versions` are supported.

### 5. Traffic Statistics

**Get ling traffic stats**:

```
GET /lingframe/dashboard/lings/{lingId}/stats
```

Returns:

- total requests
- version distribution
- active requests
- statistics-window start

**Reset statistics**:

```
POST /lingframe/dashboard/lings/{lingId}/stats/reset
```

### 6. Micrometer Bridge

`lingframe-dashboard` ships with an optional Micrometer bridge.

When the host application provides a `MeterRegistry`, LingFrame registers the following gauges:

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

- LingFrame now provides the bridge, but does not force a specific monitoring backend
- If the host also adds `micrometer-registry-prometheus` and exposes actuator endpoints, Prometheus can scrape these metrics directly

### 7. EventBus Mechanism

LingFrame has a built-in EventBus with two subscription modes:

**Ling-level subscription** (auto-cleaned on ling unload):

```java
eventBus.subscribe(lingId, MyEvent.class, event -> {
    // handle event
});
```

**Global subscription** (for framework-level components):

```java
eventBus.subscribeGlobal(MyEvent.class, event -> {
    // handle event
});
```

---

## Prometheus Integration In Hosts

Minimum host requirements:

1. add `spring-boot-starter-actuator`
2. add `micrometer-registry-prometheus`
3. expose `/actuator/prometheus`

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

The sample app `lingframe-example-lingcore-app` already contains this setup and can be used as a working reference.

---

## Logging Configuration

### Recommended Log Levels

```yaml
logging:
  level:
    root: INFO
    com.lingframe: INFO
    # enable for debugging
    com.lingframe.core.fsm: DEBUG
    com.lingframe.core.pipeline: DEBUG
    com.lingframe.core.classloader: DEBUG
```

### Audit Logging

Methods annotated with `@Auditable` will write audit entries:

```java
@Auditable(action = "createOrder", resource = "order")
public OrderInfo createOrder(CreateOrderRequest request) {
    // ...
}
```

---

## Working With Dashboard

Dashboard is the main entry point for current observability capabilities. See [Dashboard Documentation](dashboard.md).
