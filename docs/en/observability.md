# Observability

This document describes the observability capabilities currently landed in LingFrame.

---

## Currently Implemented

### 1. Dashboard SSE Event Streams

LingFrame provides real-time event streams based on Server-Sent Events (SSE) via the Dashboard.

**Endpoint**: `GET /lingframe/dashboard/stream`

**Currently Supported Event Types**:

| Event Type | Description |
|----------|------|
| `trace` | Invocation trace event |
| `audit` | Audit event |
| `lifecycle` | Lifecycle event |
| `circuit-breaker` | Circuit breaker state change |
| `leak-detection` | Leak detection event |

**Usage Example**:

```javascript
const eventSource = new EventSource('/lingframe/dashboard/stream');

eventSource.onmessage = (event) => {
    const data = JSON.parse(event.data);
    console.log('Event:', data);
};
```

### 2. JVM & System Metrics

JVM metric snapshots can be fetched via the Dashboard API:

**Endpoint**: `GET /lingframe/dashboard/lings/metrics`

**Current Output Fields**:

| Metric Category | Specific Metrics |
|----------|----------|
| CPU | System CPU usage, Process CPU load |
| Memory | Total memory, Heap, Non-heap, Metaspace |
| JVM | GC count/time, Class loading, Threads |
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

The response includes:

- A ling-level `summary`
- Version-level `versions` list

Covered fields include:

- `qps`
- `errorRate`
- `avgLatencyMs`
- `p99LatencyMs`
- `activeRequests`
- `healthStatus`

The Dashboard UI natively consumes and displays this data for overviews and version comparisons.

### 4. Governance Signal Snapshots

**All Governance Signals**:

```
GET /lingframe/dashboard/lings/governance/all
```

Currently observable points:

- `rateLimitedRequests`
- `timeoutRequests`
- `circuitOpenedCount`
- `circuitOpenRejections`
- `bulkheadRejectedRequests`
- `recoveryCount`

These similarly support a ling-level `summary` and version-level `versions` list.

### 5. Traffic Statistics

**Get Traffic Statistics for a Ling**:

```
GET /lingframe/dashboard/lings/{lingId}/stats
```

Returns:

- Total request count
- Version routing counts
- Active requests
- The start timestamp of the statistical window

**Reset Statistics**:

```
POST /lingframe/dashboard/lings/{lingId}/stats/reset
```

### 6. Micrometer Metrics Bridging

`lingframe-dashboard` has an optional built-in Micrometer bridge.

When the LingCore application provides a `MeterRegistry`, the following gauges will automatically be registered:

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

Clarifications:

- LingFrame has prepared the metric bridging but does not force LingCore to adopt a specific monitoring backend.
- If LingCore imports `micrometer-registry-prometheus` and exposes the actuator endpoint, these metrics can be scraped by Prometheus.

### 7. EventBus Mechanism

LingFrame features a built-in EventBus supporting two subscription modes:

**Ling-Level Subscription** (Cleaned up automatically when the ling is unloaded):

```java
eventBus.subscribe(lingId, MyEvent.class, event -> {
    // Process event
});
```

**Global Subscription** (Used by framework-level components):

```java
eventBus.subscribeGlobal(MyEvent.class, event -> {
    // Process event
});
```

---

## Integrating LingCore with Prometheus

Minimum requirements for integration:

1. LingCore imports `spring-boot-starter-actuator`
2. LingCore imports `micrometer-registry-prometheus`
3. LingCore exposes `/actuator/prometheus`

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

The example application `lingframe-example-lingcore-app` has already adopted this configuration and can be used directly as a scraping reference.

---

## Log Configuration

### Recommended Log Levels

```yaml
logging:
  level:
    root: INFO
    com.lingframe: INFO
    # Turn on during debugging:
    com.lingframe.core.fsm: DEBUG
    com.lingframe.core.pipeline: DEBUG
    com.lingframe.core.classloader: DEBUG
```

### Audit Logs

Methods annotated with `@Auditable` automatically generate audit logs:

```java
@Auditable(action = "createOrder", resource = "order")
public OrderInfo createOrder(CreateOrderRequest request) {
    // ...
}
```

---

## Using Together with the Dashboard

The Dashboard is currently the main interface for these observability capabilities. See the [Dashboard Documentation](dashboard.md) for more details.
