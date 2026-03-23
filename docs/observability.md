# Observability

This document describes LingFrame's current observability capabilities.

> ⚠️ **Note**: This document only describes implemented features. Prometheus/Grafana/ELK integration is planned, see [Roadmap](roadmap.md).

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

### 4. Traffic Statistics

**Get Ling Traffic Stats**:
```
GET /lingframe/dashboard/lings/{lingId}/stats
```

Returns: Total requests, version distribution, active requests, window start time.

**Reset Statistics**:
```
POST /lingframe/dashboard/lings/{lingId}/stats/reset
```

### 5. EventBus Mechanism

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

---

## Planned

The following features are planned in [Roadmap](roadmap.md) Phase 4:

| Feature | Status |
|---------|--------|
| Micrometer integration | ⏳ Planned |
| Prometheus collection support | ⏳ Planned |
| Custom Metrics extension | ⏳ Planned |
| Ling-level invocation metrics (count, success rate, latency) | ⏳ Planned |

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
