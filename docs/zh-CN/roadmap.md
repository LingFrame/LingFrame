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

- ✅ 灵元生命周期管理
- ✅ Child-First 类加载隔离
- ✅ Spring 父子上下文隔离
- ✅ 三层 ClassLoader 架构（SharedApiClassLoader）
- ✅ 服务路由（@LingService + @LingReference）
- ✅ 基础权限治理
- ✅ 基础设施代理（Storage / Cache）

---

## Phase 2：可视化治理 ✅ 基本完成

**目标**：可视化操作入口

- ✅ Dashboard 灵元管理
- ✅ 灵元状态控制（启动/停止/热重载）
- ✅ 权限动态调整
- ✅ 灰度发布配置
- ⏳ Dashboard UI 打磨

---

## Phase 3：完整治理能力 ✅ 已完成

**目标**：全面的运行时治理

### 已实现
- ✅ 权限控制（@RequiresPermission）
- ✅ 安全审计（@Auditable）
- ✅ 全链路追踪（LingCallContext）
- ✅ 灰度发布（CanaryRouter）
- ✅ 熔断机制（SlidingWindowCircuitBreaker）
- ✅ 限流机制（TokenBucketRateLimiter）
- ✅ 超时控制与降级兜底（整合于 SmartServiceProxy）
- ✅ 重试机制（基于 GovernanceKernel 的 retryCount）
- ✅ 复杂路由分发（基于 LabelMatchRouter 的标签与权重路由）

---

## Phase 4：可观测性 ⏳ 计划中

**目标**：全面监控能力

### 系统指标
- CPU / 内存使用率
- JVM 各项指标（GC、堆、线程）
- 系统负载

### 灵元指标
- 各灵元调用次数、成功率、耗时
- 灵元资源占用
- 异常统计

### 技术方案
- 集成 Micrometer
- 支持 Prometheus 采集
- 自定义 Metrics 扩展

---

## Phase 5：生态完善 🔄 进行中

**目标**：完整的基础设施代理生态与外骨骼扩展接入能力

### 已实现
- ✅ 生态级扩展 SPI（LingInvocationFilter、ServiceExporter、LingContextCustomizer、LingDeployService 等外围胶水层）
- ✅ 开发体验增强（devMode 下灵元安装后自动激活）

### 待实现
- ⏳ 消息代理（Kafka / RabbitMQ）
- ⏳ 搜索代理（Elasticsearch）
- ⏳ 更多基础设施代理
- ⏳ 完整示例和教程
