# 路线图

本文档描述 LingFrame 的演进路线。

> 💡 当前已实现的功能请参考 [架构设计](architecture.md)

## 定位

> **JVM 级运行时治理内核（Runtime Governance Kernel）**

核心能力：

- **行为可见**（Observability）
- **行为可控**（Controllability）
- **行为可审计**（Auditability）

---

## Phase 1：三层架构 ✅ 已完成

**目标**：验证 JVM 内治理的可行性

- ✅ 模块生命周期管理
- ✅ Child-First 类加载隔离
- ✅ Spring 父子上下文隔离
- ✅ 三层 ClassLoader 架构（SharedApiClassLoader）
- ✅ 服务路由（@LingService + @LingReference）
- ✅ 基础权限治理
- ✅ 基础设施代理（Storage / Cache）

---

## Phase 2：可视化治理 ✅ 基本完成

**目标**：可视化操作入口

- ✅ Dashboard 模块管理
- ✅ 模块状态控制（启动/停止/热重载）
- ✅ 权限动态调整
- ✅ 灰度发布配置
- ⏳ Dashboard UI 打磨

---

## Phase 3：完整治理能力 🔄 进行中

**目标**：全面的运行时治理

### 已实现
- ✅ 权限控制（@RequiresPermission）
- ✅ 安全审计（@Auditable）
- ✅ 全链路追踪（TraceContext）
- ✅ 灰度发布（CanaryRouter）

### 待实现
- ⏳ 熔断（Circuit Breaker）
- ⏳ 降级（Fallback）
- ⏳ 重试（Retry）
- ⏳ 限流（Rate Limiting）
- ⏳ 超时控制（Timeout）

---

## Phase 4：可观测性 ⏳ 计划中

**目标**：全面监控能力

### 系统指标
- CPU / 内存使用率
- JVM 各项指标（GC、堆、线程）
- 系统负载

### 模块指标
- 各模块调用次数、成功率、耗时
- 模块资源占用
- 异常统计

### 技术方案
- 集成 Micrometer
- 支持 Prometheus 采集
- 自定义 Metrics 扩展

---

## Phase 5：生态完善 ⏳ 计划中

**目标**：完整的基础设施代理生态

- ⏳ 消息代理（Kafka / RabbitMQ）
- ⏳ 搜索代理（Elasticsearch）
- ⏳ 更多基础设施代理
- ⏳ 完整示例和教程
