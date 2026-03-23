# 可观测性

本文档描述灵珑当前的可观测性能力。

> ⚠️ **注意**：本文档仅描述已实现的功能。Prometheus/Grafana/ELK 集成正在规划中，详见 [路线图](roadmap.md)。

---

## 当前已实现

### 1. Dashboard SSE 事件流

灵珑通过 Dashboard 提供 SSE（Server-Sent Events）实时事件流。

**端点**：`GET /lingframe/dashboard/stream`

**支持的事件类型**：

| 事件类型 | 说明 |
|----------|------|
| `trace` | 调用追踪事件 |
| `audit` | 审计事件 |
| `lifecycle` | 生命周期事件 |
| `circuit-breaker` | 熔断器状态变更 |
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

通过 Dashboard API 获取 JVM 指标快照：

**端点**：`GET /lingframe/dashboard/lings/metrics`

**返回内容**：

| 指标类别 | 具体指标 |
|----------|----------|
| CPU | 系统 CPU 使用率、进程 CPU 负载 |
| 内存 | 总内存、堆内存、非堆内存、Metaspace |
| JVM | GC 次数/耗时、类加载数、线程数 |
| 系统 | 系统负载 |

### 3. 灵元健康快照

**单个灵元健康快照**：
```
GET /lingframe/dashboard/lings/{lingId}/health
```

**全量灵元健康快照**：
```
GET /lingframe/dashboard/lings/health/all
```

### 4. 流量统计

**获取灵元流量统计**：
```
GET /lingframe/dashboard/lings/{lingId}/stats
```

返回：请求总量、版本分流、活跃请求数、统计窗口起点。

**重置统计**：
```
POST /lingframe/dashboard/lings/{lingId}/stats/reset
```

### 5. EventBus 事件机制

灵珑内置 EventBus，支持两种订阅模式：

**灵元级监听**（灵元卸载时自动清除）：
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

## 规划中

以下功能在 [路线图](roadmap.md) Phase 4 中规划：

| 功能 | 状态 |
|------|------|
| Micrometer 集成 | ⏳ 规划中 |
| Prometheus 采集支持 | ⏳ 规划中 |
| 自定义 Metrics 扩展 | ⏳ 规划中 |
| 灵元级调用指标（次数、成功率、耗时） | ⏳ 规划中 |

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
