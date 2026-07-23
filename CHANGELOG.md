# 更新日志

本项目的所有重大更改都将记录在此文件中。

## [V0.4.0] - 2026-07-19

版本：`lingframe-dependencies` → `revision=0.4.0`。  
主验证路径：Spring Boot 2.7 + JDK 8（默认 profile）；支持线：Spring Boot 3.5 + JDK 17（`-Pspring-boot3`）。

### 交付目标

在 0.3 治理内核的基础上，交付**控制面、路由升维、卸载/隔离、基础设施代理、双线示例与工程化**，并完成关键正确性收口。

### 🚀 控制面（Dashboard）

- **全局概览与生命周期**：交付控制台概览（统计 / 事件 / 灵元列表）、生命周期管理中心（部署、启动/停止、重载、规范卸载）与包管理。
- **服务演练场**：默认真调用，支持可选模拟；用例保存与回放，支持按比例路由演练。
- **治理中心**：集中配置资源权限、调用治理策略、预设与规则概览矩阵。
- **金丝雀与迁移辅助**：金丝雀决策辅助（错误率波动提示）；契约 / Provider 权重与迁移进度大盘。
- **监控与运维**：JVM / 逐收集器 GC 监控、灵元资源下钻、泄漏检测记录、线程池 SPI 统计与指标趋势；支持日志控制台（暂停/继续）、主题切换与多语言（i18n）、CORS 与限流。
- **持久化与安全**：SQLite 持久化存储、访问令牌认证与只读模式。

### 🚀 核心与治理主链

- **Pipeline 升维**：新增 `ContractProviderRoutingFilter`（L0 Provider 权重路由）与 `InvocationPolicyPrefillFilter`，与既有弹性/权限/线程隔离 Filter 统筹织入。
- **金丝雀与服务路由**：全链路多版本金丝雀与服务路由，支持运行时通过 `ProviderWeightRouter` 覆盖；提供隐式接口注册开关（`implicitRegistration`）。
- **微内核 SPI 解耦**：生态父委派包从 core 剥离，由 runtime 注入。
- **规范卸载清理**：通过 `LingUnloadHook` 统筹卸载清理（覆盖线程、JDBC、日志、RMI、ShutdownHook、Debugger 等）。
- **双层状态机与写权限收束**：写权限收束于 `InstanceCoordinator` / `RuntimeCoordinator`；`LingRuntime` 为只读聚合视图；流量与 `RuntimeStatus` 分职（用二维路由/权重切流，状态仅反映实例事实快照）。
- **快照与防错机制**：快照按 `instanceId` 标识；部署失败或全量卸载触发 `RuntimeCoordinator.unregister`；支持强制父委派独占与 `force-drain-on-timeout` 可配置排空；明确舱壁错误 `BULKHEAD_FULL`（`LING-2003`）；危险 API 加载期拒绝。
- **装配树 Builder 化**：收敛为 `LifecycleEngineConfig` / `FilterRegistryConfig` Builder，消除全局静态单例，`init` 失败即止。
- **正确性收口**：`AsyncLingEvent` 标记接口；分区 `InvocationContext`；异常体系统一为 `LingInvocationException`；深拷贝保护 `GovernancePolicy.copy`；统一术语为 LingCore / Ling。

### 🚀 基础设施代理

- **缓存治理代理**：Caffeine / Spring Cache / Redis 包装路径与命名空间隔离。
- **存储代理硬化**：代理层拦截连接级破坏性调用（如 `Connection.abort`）。
- **边界透明化**：明确存储治理主要覆盖 Spring `DataSource` Bean 代理路径，`DriverManager` / 非 Bean 连接池在此链之外。

### 🚀 示例与基准

- **入门示例**：`lingframe-example-lingcore-app` + user / order (+ canary) + Shared API。
- **老系统渐进改造**：`lingframe-example-ling-mall` → `lingframe-example-saas-mall`（涵盖 oauth / 退款 / 秒杀 等经典业务场景）。
- **配置与基准**：提供 `application-prod.yaml.example` 生产配置示例；`lingframe-benchmark` 包含流水线、状态机、类加载器与端到端生命周期的 JMH 压测套件。

### 🛠 运行时适配器与工程化

- **包排除**：自动配置排除与服务注册排除包。
- **ClassLoader 卸载与 Spring 生态深清**：
  - Web 元数据作为运行时真源：注册时预提取权限 / 审计 / OpenAPI（包含类级 `@Tag`），灵元 HTTP 路径信任 `WebInterfaceMetadata`。
  - 通过 `LingScanCachePurger` 有界清理注解缓存；深清 `BridgeMethodResolver.cache` 及相关 Soft 缓存；优先清理 HttpClient SelectorManager TCCL。
  - 卸载回归：开发路径与生产路径（含 Web 分发）双栈 ClassLoader 可回收性门控。
- **双栈布局收口（无反射探测 Servlet）**：
  - Runtime：公共 `lingframe-spring-boot-starter` + 栈专属 `lingframe-spring-boot2-starter` / `lingframe-spring-boot3-starter`（类型化 javax / jakarta）。
  - Dashboard：单 GAV + `java-javax` / `java-jakarta` 矩阵源码集（`build-helper` 按 profile 追加）。
  - 共享生命周期钩子使用 `InitializingBean` / `DisposableBean`，不绑定单一栈的 `javax.annotation`。
- **Spring Boot 3 对等**：`LingGatewayHandlerMapping` 与 SB2 对等，完成 CI 冒烟与示例集成测试。

### 📦 文档与规范

- 公开 [生产硬化配置清单](docs/zh-CN/production-hardening.md)、Shared API 安全边界与 [路线图](docs/zh-CN/roadmap.md) V0.4.0 章节。
- 交付 [示例地图](lingframe-examples/zh-CN/README.md)、[0.4.0 发布说明](docs/release/0.4.0-release-notes.md)。
- 明确说明 Shared-Spring 隔离边界与规范卸载 SLA。

### ⚠️ 风险与注意事项

- 存储治理主要覆盖 Spring `DataSource` Bean 代理路径，`DriverManager` / 非 Bean 连接池在此链之外。
- 已进入共享边界的 Shared API JAR 不支持热更新/热卸载，破坏性变更需重启进程。
- 默认情况下进程共享 `org.springframework.*`（运行时父委派），进程级静态缓存写入是共享 Spring 的模型代价。
- 卸载 SLA：规范卸载后，`LingClassLoader` 为 GC 可回收（可证明）。

## [V0.3.0] - 2026-03-23

### 🚀 新增

- 围绕 `InvocationPipelineEngine` 与 `FilterRegistry` 收束统一治理 Pipeline，并显式支持 `NORMAL`、`SIMULATION`、`GOVERN_ONLY`
- 让灵元调用、Spring Boot 2/3 Web 治理、灵核 Bean 拦截、Dashboard 模拟共用同一条内核路径
- Dashboard 通过真实治理链路执行模拟，并通过 SSE 输出 trace、audit、circuit-breaker、lifecycle、leak-detection 事件

### 🛠 变更

- 运行时状态写入权收束到 `InstanceStatus`、`RuntimeStatus`、`InstanceCoordinator`、`RuntimeCoordinator`
- 生命周期编排变得更显式：部署、旁路重载、排空后卸载、清理形成更清晰的运行时路径
- `SharedApiManager` 明确共享 API 启动顺序：预加载、注册包、冻结边界、再加载灵元
- 卸载清理正式纳入 Pipeline 资源驱逐与泄漏诊断

### ⚠️ 说明

- `0.3.0` 对外交付：Pipeline 收束、运行时状态收敛、Dashboard 治理 / 控制面、生命周期编排、Shared API 边界冻结、长期运行稳定性相关工作

## [V0.2.0] - 2026-02-23

### 🚀 新特性

- **弹性治理**：在 `GovernanceKernel` 中全面实现滑动窗口熔断器、令牌桶限流、重试及降级机制。
- **生态兼容性**：在支持 JDK 17 / Spring Boot 3.x 的基础上，新增对 JDK 8 和 Spring Boot 2.7.x 的全量兼容支持。
- **开发效率提升**：
    - 新增 `dev-mode` 开发模式，支持宽松的运行时权限模型。
    - 开发模式下支持灵元安装后自动激活，无需手动操作。
    - 集成 SpringDoc (Swagger) 支持，实现 API 自动分组（核心、灵元、宿主）。

### 🛠 重构与优化

- **全局术语重构**：将所有“插件 (Plugin)”相关表述统一更名为“灵元 (Ling)”，将“宿主 (Host)”更名为“灵核 (LingCore)”，确保概念高度一致。
- **隔离性增强**：优化 `SmartServiceProxy` 和 `InvocationExecutor`，强化灵元边界审计能力。
- **基础设施 SPI**：针对 `StorageService` 和 `CacheService` 代理进行了稳定性优化。

### 🐛 问题修复

- **内存泄漏缓解**：通过系统性清理 Spring 缓存及 Jakarta EL/Objenesis 静态引用，尽可能缓解并优化了灵元热重载过程中的 ClassLoader 内存泄漏问题。
- **路径匹配**：修复了 Swagger 及 Web 接口映射中的多项路径匹配兼容性问题。

## [V0.1.0-Preview] - 2026-02-01

> **初版 (Preview)**：此版本验证了 JVM 进程内运行时治理的可行性。
> 核心关注点：边界、隔离与控制。

### 🚀 新特性

#### 核心架构 (JVM Runtime Governance)
- **三层 ClassLoader 架构**：实现了 `HostClassLoader` -> `SharedApiClassLoader` -> `LingClassLoader` 的层级结构，确保严格隔离的同时允许受控共享。
- **Child-First 类加载机制**：灵元优先加载自身依赖，防止与灵核应用发生“依赖地狱”。
- **Spring 上下文隔离**：每个灵元在独立的 Spring `ApplicationContext` 中运行，确保 Bean 隔离和独立的生命周期。

#### 灵元系统 (ling System)
- **生命周期管理**：通过 `LingManager` 全面支持 `LOAD`（加载）、`START`（启动）、`STOP`（停止）、`UNLOAD`（卸载）及热重载能力。
- **Manifest 配置**：定义了 `ling.yml` 标准，用于声明元数据、依赖项和所需能力。
- **服务导出/导入**：
  - `@LingService`：将 Bean 导出为跨边界的服务。
  - `@LingReference`：注入来自其他灵元或灵核的服务。

#### 治理与安全 (Governance & Security)
- **权限控制**：
  - 实现了 `GovernancePolicy` 用于定义访问控制列表 (ACL)。
  - 添加了 `@RequiresPermission` 用于细粒度的方法级授权。
- **审计与追踪**：
  - `@Auditable` 注解用于记录敏感操作。
  - `TraceContext` 用于跨灵元边界传播请求元数据。
- **流量路由**：
  - `LabelMatchRouter` 实现，支持灰度发布和基于标签的流量路由。

#### 仪表盘与运维 (Dashboard & Operations)
- **可视化管理**：提供基于 Web 的仪表盘（预览版），用于监控灵元状态和管理配置。
- **动态控制**：
  - 通过 UI/API 启动/停止灵元。
  - 无需重启 JVM 即可热重载灵元。
  - 运行时调整权限策略。

#### 基础设施 SPI (Infrastructure SPI)
- **代理抽象**：
  - `StorageService` 代理文件操作。
  - `CacheService` 代理缓存（本地/远程）。

### ⚠️ 技术边界与限制
- **仅限单进程**：专为单体应用改造设计，而非分布式微服务框架。
- **兼容性**：基于 JDK 17 (LTS) 和 Spring Boot 3.x 构建。
- **待实现功能** (Phase 3)：熔断 (Circuit Breaking)、限流 (Rate Limiting) 和降级 (Fallback) 机制已定义但尚未完全投入使用。

### 🛠 基础设施
- 建立了标准的 Maven 多灵元项目结构 (`core`, `api`, `dashboard`, `runtime`, `infrastructure`)。
- 集成了 `maven-compiler-Ling` 和 `flatten-maven-Ling` 以标准化构建。
