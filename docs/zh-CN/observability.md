# 可观测性

本文档描述灵珑当前已经落地的可观测能力。

---

## 当前已实现

### 1. Dashboard SSE 事件流

灵珑通过 Dashboard 提供基于 SSE（Server-Sent Events）的实时事件流。

**端点**：`GET /lingframe/dashboard/stream`

**当前支持的事件类型**：

| 事件类型 | 说明 |
|----------|------|
| `trace` | 调用追踪事件 |
| `audit` | 审计事件 |
| `lifecycle` | 生命周期事件 |
| `circuit-breaker` | 熔断器状态变化 |
| `leak-detection` | 泄漏检测事件 |

**使用示例**：

```javascript
const eventSource = new EventSource('/lingframe/dashboard/stream');

eventSource.onmessage = (event) => {
    const data = JSON.parse(event.data);
    console.log('Event:', data);
};
```

### 2. JVM 与系统指标

可以通过 Dashboard API 获取 JVM 指标快照：

**端点**：`GET /lingframe/dashboard/lings/metrics`

**当前返回内容**：

| 指标类别 | 具体指标 |
|----------|----------|
| CPU | 系统 CPU 使用率、进程 CPU 负载 |
| 内存 | 总内存、堆、非堆、Metaspace |
| JVM | GC 次数/耗时、类加载、线程 |
| 系统 | 系统负载 |

### 3. 灵元健康快照

**单灵元健康快照**：

```
GET /lingframe/dashboard/lings/{lingId}/health
```

**全量灵元健康快照**：

```
GET /lingframe/dashboard/lings/health/all
```

当前返回同时包含：

- ling 级 `summary`
- version 级 `versions`

已覆盖字段包括：

- `qps`
- `errorRate`
- `avgLatencyMs`
- `p99LatencyMs`
- `activeRequests`
- `healthStatus`

Dashboard 已经直接消费并展示这批数据，用于总览和版本对比。

### 4. 治理信号快照

**全量治理信号**：

```
GET /lingframe/dashboard/lings/governance/all
```

当前可观测：

- `rateLimitedRequests`
- `timeoutRequests`
- `circuitOpenedCount`
- `circuitOpenRejections`
- `bulkheadRejectedRequests`
- `recoveryCount`

同样支持 ling 级 `summary` 与 version 级 `versions`。

### 5. 流量统计

**获取灵元流量统计**：

```
GET /lingframe/dashboard/lings/{lingId}/stats
```

返回：

- 请求总量
- 版本分流
- 活跃请求数
- 统计窗口起点

**重置统计**：

```
POST /lingframe/dashboard/lings/{lingId}/stats/reset
```

### 6. Micrometer 指标桥接

`lingframe-dashboard` 已内置可选的 Micrometer 桥接。

当宿主应用提供 `MeterRegistry` 时，会自动注册以下 gauge：

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

说明：

- 灵珑已完成指标桥接，但不强制宿主采用某一种监控后端
- 如果宿主同时引入 `micrometer-registry-prometheus` 并暴露 actuator 端点，即可被 Prometheus 抓取

### 7. EventBus 事件机制

灵珑内置 EventBus，支持两种订阅模式：

**灵元级监听**（灵元卸载时自动清理）：

```java
eventBus.subscribe(lingId, MyEvent.class, event -> {
    // 处理事件
});
```

**全局监听**（框架级组件使用）：

```java
eventBus.subscribeGlobal(MyEvent.class, event -> {
    // 处理事件
});
```

---

## 宿主接入 Prometheus

最小接入条件：

1. 宿主引入 `spring-boot-starter-actuator`
2. 宿主引入 `micrometer-registry-prometheus`
3. 宿主暴露 `/actuator/prometheus`

示例配置：

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

示例应用 `lingframe-example-lingcore-app` 已补齐这组配置，可直接作为抓取参考。

---

## 日志配置

### 推荐日志级别

```yaml
logging:
  level:
    root: INFO
    com.lingframe: INFO
    # 调试时可开启
    com.lingframe.core.fsm: DEBUG
    com.lingframe.core.pipeline: DEBUG
    com.lingframe.core.classloader: DEBUG
```

### 审计日志

通过 `@Auditable` 注解的方法会记录审计日志：

```java
@Auditable(action = "createOrder", resource = "order")
public OrderInfo createOrder(CreateOrderRequest request) {
    // ...
}
```

---

## 与 Dashboard 配合

Dashboard 是当前可观测能力的主要入口，详见 [Dashboard 文档](dashboard.md)。
