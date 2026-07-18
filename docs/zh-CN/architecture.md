# 架构设计

本文描述的是**当前代码里真实落地的对外架构**。
它刻意避开已经不再符合运行时现状的旧叙事。

如果用一句话概括这份架构文档的重点，那就是：

> 灵珑当前架构不只是在回答“灵元怎么被加载”，  
> 也在正式回答“灵元怎么被治理、被收敛、被规范地下线与清理”。

---

## 设计原则

- **治理主链唯一**：治理能力尽量走同一条 Pipeline，而不是每个入口各自实现一套。业务**终端执行**在 `GOVERN_ONLY` 下仍可留在灵核侧 Web/AOP 框架路径
- **状态写入权明确**：实例生命周期状态与宏观运行时状态不能混写在同一组对象里
- **执行模式显式化**：真实执行、模拟执行、仅治理借道都要通过统一模式表达
- **解释性先走事件**：Dashboard 与后续控制面应消费真实内核事件，而不是维护影子模型
- **长期运行职责前置**：卸载、清理、泄漏诊断属于运行时治理本身，不是附属工具
- **热卸载必须有秩序**：排空、资源驱逐、清理与状态收口要被视为正式运行时路径

---

## 模块布局

| 模块 | 当前角色 |
| :-- | :-- |
| `lingframe-api` | 契约、注解、异常、安全抽象 |
| `lingframe-core` | Pipeline、路由、运行时状态、生命周期编排、事件总线、治理逻辑 |
| `lingframe-runtime` | Spring Boot 集成、Web 治理过滤器、Bean 拦截、Starter 装配 |
| `lingframe-dashboard` | 治理控制面、模拟 API、灰度操作、SSE 事件流 |
| `lingframe-infrastructure` | 基础设施代理路径，目前以存储与缓存为已实现参考路径 |
| `lingframe-examples` | 示例灵核应用与演示灵元 |

---

## 调用治理 Pipeline

`InvocationPipelineEngine` 已经成为正式的统一治理执行主链。  
`FilterRegistry` 负责组装内建 Filter 与 SPI Filter，并在启动时校验阶段顺序约束。

### 内建阶段

| Filter | 职责 |
| :-- | :-- |
| `ContractProviderRoutingFilter` | L0 provider 路由（契约式 FQSID，在指标阶段之前） |
| `TrafficMetricsFilter` | 记录请求事实与早期指标、追踪信息 |
| `MacroStateGuardFilter` | 当宏观运行时状态不安全时提前拒绝请求 |
| `CanaryRoutingFilter` | 选择目标实例并处理灰度路由 |
| `InvocationPolicyPrefillFilter` | 在弹性治理前把有效策略意图预填入 `ctx.governance()` |
| `ResilienceGovernanceFilter` | 执行熔断、限流等韧性治理决策 |
| `ContextIsolationFilter` | 解析目标类、方法与 ClassLoader 隔离上下文 |
| `GovernanceDecisionFilter` | 收束超时、规则来源等治理决策 |
| `PermissionGovernanceFilter` | 执行最终权限校验 |
| `ThreadIsolationGovernanceFilter` | 执行线程隔离与切换 |
| `TerminalInvokerFilter` | 执行真实终端调用、生成模拟结果，或在特定模式下跳过终端执行 |

SPI/动态过滤器不得占用内置保留 order，须选择核心阶段之间的非保留序号。

当前实现的关键，不只是“这些 Filter 存在”，
而是它们已经组成了对多个入口都可复用的正式运行时主链。

### 调用上下文 (InvocationContext)

为防止传统的基于 `Map<String, Object>` 的魔术键（Magic Key）泛滥，灵珑强制规范了调用上下文的传输结构。`InvocationContext` 作为贯穿 Pipeline 的唯一通行证，被显式划分为四大协议分区：

- `routingState`（路由分区）：指明请求应发往哪个实例（如目标版本、标签）。
- `resolutionState`（解析分区）：承载类加载器、方法等短命强引用（强制要求在回收时物理清空，严防跨调用残留）。
- `governanceState`（治理分区）：承载权限、审计、限流、超时等不可篡改的运维意图。
- `executionState`（执行分区）：掌管当前调用是否触发真实副作用或记录追踪迹。

这种设计使得 Pipeline 的数据流动具备了极强的可追溯性和健壮性。

---

## 执行模式

`InvocationExecutionMode` 让 Pipeline 具备了明确的模式感知能力。

| 模式 | 含义 | 典型用途 |
| :-- | :-- | :-- |
| `NORMAL` | 执行治理并进入真实终端调用 | 灵元间标准调用 |
| `SIMULATION` | 跑完整治理链，但不产生真实副作用 | Dashboard 模拟与解释 |
| `GOVERN_ONLY` | 执行治理，但不在 Pipeline 内做终端调用 | Spring Web 请求与灵核 Bean 拦截，真实业务仍由原框架路径执行 |

这也是更多入口能够共用同一套治理内核，而不是各自维护实现分支的关键。

---

## 生命周期编排

`DefaultLingLifecycleEngine` 是当前已经落地的顶层生命周期编排器。

它负责把部署、重载、卸载意图翻译成有序运行时动作，  
但不会把状态写入权重新揉回一个对象里；真正的状态写入仍然由 `InstanceCoordinator` 与 `RuntimeCoordinator` 负责。

### 部署

- 校验灵元定义与安全约束
- 创建 ClassLoader 与容器
- 在首个实例事实出现前先注册运行时聚合
- 驱动实例进入 `LOADING -> STARTING -> READY`
- 先把实例放入池中，再向上发布 `READY` 事实

### 重载

- 先旁路部署一个替代实例
- 保留原实例的 default/canary 角色与 labels
- 切流到新实例
- 在新实例 ready 后再卸载旧实例

### 卸载

- 先把实例标记为 `STOPPING`
- 等待飞行中请求排空，直到空闲或超时
- 驱逐服务、Pipeline 持有资源、缓存与 ClassLoader 关联状态
- 把泄漏诊断纳入卸载完成流程

这也是当前架构已经形成收敛主线的重要原因之一。

这里真正特别的，不是系统“支持卸载”这件事本身，  
而是卸载已经被当成需要正式编排、清理和诊断的运行时职责。

---

## Shared API 边界

`SharedApiManager` 把 `Shared API` 的边界显式化到了启动流程里。

- preload 配置好的共享 JAR 或 classes 目录
- 注册共享包前缀
- freeze 共享边界
- 然后才允许灵元基于冻结后的契约视图加载

这不是一个“方便一点的加载顺序”，  
而是明确的进程级契约规则。新的共享契约可以在 freeze 前引入，但已经加载过的共享契约不能在同一进程里热更新或热卸载。

---

## 运行时状态所有权模型

当前实现已经把运行时状态正式收束为两层。

### 实例层

- 状态类型：`InstanceStatus`
- 状态所有者：`InstanceCoordinator`
- 作用：描述单个 `LingInstance` 从 `CREATED` 到 `DEAD` 的生命周期

典型状态包括：

- `CREATED`
- `LOADING`
- `STARTING`
- `READY`
- `STOPPING`
- `DEAD`
- `ERROR`

### 运行时层

- 状态类型：`RuntimeStatus`
- 状态所有者：`RuntimeCoordinator`
- 作用：描述灵核侧视角下的宏观可用性

典型状态包括：

- `INACTIVE`
- `ACTIVE`
- `DEGRADED`
- `STOPPING`
- `REMOVED`

### 联动方式

两层状态通过事件联动，而不是对象互相写状态：

- 实例层发布事实
- `RuntimeCoordinator` 订阅这些事实
- 运行时层基于快照重新聚合宏观状态

这就是当前架构最核心的收敛点之一。

如果你想继续看状态所有权和联动链路的完整说明，直接读 [运行时双层状态机架构设计](runtime-dual-state-machine-architecture.md)。

---

## 治理入口

同一套治理内核已经被复用到多个入口。

| 入口 | 适配器 | 如何使用内核 |
| :-- | :-- | :-- |
| 灵元服务调用 | Core 标准调用路径 | 通过 `NORMAL` 模式执行完整 Pipeline |
| Spring Boot 2 / 3 Web 请求 | `LingWebGovernanceFilter` | 通过 `GOVERN_ONLY` 借道治理，终端分发仍由 Web 框架完成 |
| 灵核 Bean 方法 | `LingCoreBeanGovernanceInterceptor` | 在 AOP 拦截中通过 `GOVERN_ONLY` 复用治理能力 |
| Dashboard 模拟 | `SimulateService` | 通过 `SIMULATION` 跑真实治理链路但不产生真实副作用 |
| Dashboard 服务演练场 | `ServicePlaygroundService` | 默认 `NORMAL` 真调用便于验接口；请求可显式 `SIMULATION` |

**重要区分**：多入口共享的是**治理** Pipeline，不一定共享业务**终端**路径。Web / AOP 在 `GOVERN_ONLY` 后仍由灵核侧框架路径执行业务。

这也是当前实现与更早零散能力拼装状态的本质差别。

---

## 可观测性与清理

当前实现进一步拉近了治理与运维的关系。

- `EngineTrace` 用来保留可解释的决策追踪
- `MonitoringEvents` 定义 trace、audit、alert、circuit-breaker、leak-detection 等统一事件语义
- `LogStreamService` 通过 SSE 把这些事件流推送到 Dashboard
- `InvocationPipelineEngine.evictLingResources` 与方法缓存驱逐支撑卸载清理
- `DefaultLeakDetector` 支持分级泄漏诊断策略（包含 `DEV_AGGRESSIVE` 激进诊断、`DEV_BOUNDED` 降级有界诊断、`PROD_PASSIVE` 生产态被动观测三种模式），并通过有界并发限制避免在排查时引发 GC 风暴

架构上的重要变化在于：Dashboard 开始消费**真实内核证据**，而不是单独维护一层解释视图。

这也是灵珑和普通“动态加载 + 管理后台”组合思路的差异所在：  
控制面消费的是同一条运行时主链上的真实事件，而不是旁路出来的一层影子解释。

---

## 当前边界

当前对外公开的架构仍然有清晰边界：

- 它仍然是**单进程**治理内核
- `Shared API` 仍然是**进程级契约边界**
- 一旦共享边界已经 freeze，共享契约变更仍然需要重启进程
- 基础设施代理当前以存储与缓存路径最清晰，更多代理生态仍在后续演进

这些边界是刻意保留的，也应该在对外文档中持续保持可见。
