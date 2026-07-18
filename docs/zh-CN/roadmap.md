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
- ✅ 模拟测试接口（资源 / IPC / 压测路由）
- ✅ SSE 事件流（`/lingframe/dashboard/stream`）
- ✅ JVM 指标与灵元健康快照
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
- ✅ 统一调用**治理**主链（`InvocationPipelineEngine` + `FilterRegistry`）
- ✅ 三种执行模式：`NORMAL` / `SIMULATION` / `GOVERN_ONLY`
- ✅ 多入口共用**治理内核**（终端执行不必同一条路径）：
  - 灵元 IPC / 服务调用 → `NORMAL` 全链（含 Terminal）
  - Spring Web / 灵核 Bean AOP → `GOVERN_ONLY` 后由灵核侧 Web/AOP 框架路径继续业务执行
  - Dashboard 模拟 → `SIMULATION`
- ✅ 双层运行时状态模型（`InstanceStatus` / `RuntimeStatus`）
- ✅ 状态写入权收束到 `InstanceCoordinator` / `RuntimeCoordinator`
- ✅ 生命周期编排收束到 `DefaultLingLifecycleEngine`
- ✅ 卸载清理、资源驱逐与泄漏检测纳入正式运行时职责
- ✅ Shared API 启动顺序与冻结边界（`SharedApiManager`）

---

## Phase 4：可观测性 🔄 进行中

**目标**：全面监控能力

### 当前已具备
- ✅ Dashboard SSE 监控事件流
- ✅ trace / audit / lifecycle / circuit-breaker / leak-detection 事件输出
- ✅ JVM / 系统指标采集（CPU、进程 CPU 负载、总内存、堆、非堆、Metaspace、类加载、线程、GC、系统负载）
- ✅ 单灵元 / 全量灵元健康快照

### 系统指标
- ✅ CPU / 进程 CPU 负载
- ✅ 总内存 / 堆 / 非堆 / Metaspace
- ✅ JVM 各项指标（GC、类加载、线程）
- ✅ 系统负载

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
- ✅ 生态级扩展 SPI 已建立（`LingInvocationFilter`、`ServiceExporter`、`LingContextCustomizer`、`LingDeployService`）
- ✅ `LingInvocationFilter` 已接入统一治理 Pipeline，可通过运行时装配动态扩展 Filter 链
- ✅ `LingDeployService` 已有默认实现（`DefaultLingDeployService`，当前默认支持本地文件与 `http/https` 下载）
- ✅ 开发体验增强（devMode 下灵元安装后自动激活）

### 当前阶段说明
- `ServiceExporter` 与 `LingContextCustomizer` 已作为扩展点公开，但更多属于外围接入骨架，仍需要后续生态适配逐步补齐
- 当前生态扩展重点仍然是“把接入边界先立住”，不是宣称完整生态已经完成

### 待实现
- ⏳ 消息代理（Kafka / RabbitMQ）
- ⏳ 搜索代理（Elasticsearch）
- ⏳ 更多基础设施代理
- ⏳ 完整示例和教程

---

## V0.4.0：设计债收敛 ✅ 已完成（候选内核）

**目标**：一次性收敛历史遗留设计债务，形成**面向生产的候选内核**。

**诚实表述**：本里程碑表示 0.4 双层状态机 / Pipeline / 卸载等**债务清单**在 SB2 主路径上大体收口；**不等于**双栈已对等认证、生态完备，或“无需再硬化即可对外宣称生产认证”。

**详细实施依据**：[0.4-implementation-plan.md](../development/v0.4/0.4-implementation-plan.md)

### 装配树重构
- ✅ `LingFrameConfig` 去全局静态单例，`init()` 二次调用从静默拒绝改为抛异常（fail-fast）
- ✅ `DefaultLingLifecycleEngine` 13 参构造器收敛为 `LifecycleEngineConfig` Builder，删除 setter
- ✅ `FilterRegistry` 3 构造器 + 4 `initialize()` 重载收敛为 `FilterRegistryConfig` Builder，删除 `initialize()`

### 状态机职责收敛
- ✅ `InstancePool` 强制构造器注入 `InstanceCoordinator`，杜绝静默无事件僵尸版本
- ✅ `RuntimeCoordinator.register()` 收敛到编排层单次调用，消除 `LingRuntime` 双重注册时序耦合

### 治理正确性
- ✅ 熔断参数可配置化（`LingRuntimeConfig` + `application.yml`），消除硬编码魔法数字
- ✅ `GovernancePolicy.copy()` 反射测试断言守护，防止新增字段遗漏
- ✅ `LingDefinition.properties` 原生递归深拷贝（`DeepCopyUtils`，零第三方依赖）
- ✅ 异常体系收敛（`InvocationException`/`ServiceUnavailableException`/`CallNotPermittedException` 已删除，统一到 `LingInvocationException`）

### 可观测性与代码质量
- ✅ `AsyncLingEvent` 标记接口替换包名前缀判断，消除异步分发静默失效风险
- ✅ `InvocationContext` 委派方法已删除，统一分区访问（`ctx.governance().xxx()` / `ctx.execution().xxx()`）
- ✅ `PoolStats` 删 `@Value`，统一 record-style 访问器
