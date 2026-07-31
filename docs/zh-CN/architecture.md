# 架构设计

> 灵珑当前架构不只是在回答"灵元怎么被加载",
> 也在正式回答"灵元怎么被治理、被收敛、被规范地下线与清理"。

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
| `lingframe-api` | 协约、注解、异常、安全抽象 |
| `lingframe-core` | Pipeline、路由、运行时状态、生命周期编排、事件总线、治理逻辑 |
| `lingframe-runtime` | Spring Boot 集成：公共 `spring-boot-starter` + 栈专属 `spring-boot2/3-starter`（类型化 Web Filter / Mapping）；Bean 拦截 |
| `lingframe-dashboard` | 治理控制面、模拟 API、迁移操作、SSE 事件流；**单 GAV**，Servlet 差异在 `java-javax` / `java-jakarta` 矩阵源码集 |
| `lingframe-infrastructure` | 基础设施代理路径，目前以存储与缓存为已实现参考路径 |
| `lingframe-examples` | 示例灵核应用与演示灵元 |

---

## 核心架构设计

当前实现围绕八个架构设计组织。每一个都回答长期运行 JVM 系统会面对的一个具体问题。

### 1. 双层状态机：实例层 + 运行时层

这是灵珑架构的"中心引力"。

**它解决的问题**：单 JVM 内多版本灵元并存时，谁来代表灵元对外的可用性？如果同一个对象既拥有"某一个版本的生命周期"又拥有"灵元整体的宏观状态"，两个职责会纠缠，卸载安全性退化。

**设计**：

- **实例层**（`InstanceStatus`）：单个灵元某个版本实例的真实生命周期阶段
  - 状态：`CREATED → LOADING → STARTING → READY → STOPPING → DEAD`（外加 `ERROR`）
  - 所有者：`InstanceCoordinator` 是唯一写入口；`LingInstance` 持有内部状态机但不暴露公共修改 API

- **运行时层**（`RuntimeStatus`）：灵核侧看到的灵元整体宏观可用性
  - 状态：`INACTIVE / ACTIVE / DEGRADED / STOPPING / REMOVED`
  - 所有者：`RuntimeCoordinator` 是唯一写入口；`LingRuntime` 是只读聚合，不持有第二份运行时 FSM

**联动方式**：两层之间**不互相直改状态**。实例层发布事实（事件）；`RuntimeCoordinator` 订阅这些事实并从快照重新评估宏观状态。运行时宏观状态是实例事实的**聚合**，不是另一份真源。

**硬约束**：

- 业务代码不能直接操作状态机
- `LingRuntime` 不能持有第二份运行时 FSM
- `InstancePool` 不能演化成"生命周期总控器"
- 编排层不能绕过协调器直改状态

### 2. 写权限收束：唯一真源 + 唯一写入口

**它解决的问题**：长期运行进程里，治理漂移最常见的成因是写权限散——多个对象各自维护一份状态，各自以为自己的视图是真源。

**设计**：任何改动前必须先回答三个问题——*谁有写权限、谁只读、谁编排*。七个角色固化下来：

| 角色 | 作用 | 核心约束 |
| :-- | :-- | :-- |
| `LingInstance` | 单个灵元实例承载体 | 对外不暴露状态机写权限 |
| `InstanceCoordinator` | 实例状态唯一写入口 | 只有它能推进 `InstanceStatus` |
| `InstancePool` | 管理活跃成员、默认实例、濒死队列 | 只管成员关系，不管完整生命周期 |
| `LingRuntime` | 灵元运行时聚合体 | 对外只暴露只读视图，不持有运行时 FSM 写权限 |
| `RuntimeCoordinator` | 运行时状态唯一写入口 | 只有它能推进 `RuntimeStatus` |
| `DefaultLingLifecycleEngine` | 组织部署、切换、卸载顺序 | 不能绕过协调器直改状态 |
| `LingUnloadCoordinator` | 卸载清理、资源回收、泄漏检测 | 不能替代生命周期编排 |

唯唯真源表：

| 概念 | 唯唯真源 | 唯一写入口 | 其他角色 |
| :-- | :-- | :-- | :-- |
| 实例状态 | `LingInstance` 内部状态机 | `InstanceCoordinator` | 其他对象只能读或响应事件 |
| 运行时状态 | `RuntimeCoordinator` 内部 FSM / 快照 | `RuntimeCoordinator` | `LingRuntime` 只读 |
| 实例成员关系 | `InstancePool` | `InstancePool` 受编排驱动变更 | 不额外维护第二份成员真源 |
| 生命周期阶段顺序 | `DefaultLingLifecycleEngine` | 编排逻辑本身 | 不能把顺序逻辑散落到各对象 |
| 卸载清理 | `LingUnloadCoordinator` | 清理协调器 | 不能由任意业务代码直接接管 |

### 3. 单治理脊柱：调用流水线

**它解决的问题**：如果 Web、Bean、灵元间调用各自走一套治理逻辑，治理一定会重新分裂。

**设计**：`InvocationPipelineEngine` 是治理主链。所有入口共用**同一条治理流水线**，而不是每个入口各自重写一套治理逻辑。

`FilterRegistry` 负责组装内建 Filter 与 SPI Filter，并在启动时校验阶段顺序约束。

**内建 Filter 顺序**：

```
ContractProviderRoutingFilter   → L0 provider 路由（契约式 FQSID，在指标阶段之前）
TrafficMetricsFilter            → 记录请求事实与早期指标、追踪信息
MacroStateGuardFilter           → 当宏观运行时状态不安全时提前拒绝请求
InvocationPolicyPrefillFilter  → 在弹性治理前把有效策略意图预填入 ctx.governance()
ResilienceGovernanceFilter      → 执行熔断、限流等韧性治理决策
ContextIsolationFilter          → 解析目标类、方法与 ClassLoader 隔离上下文
GovernanceDecisionFilter        → 收束超时、规则来源等治理决策
PermissionGovernanceFilter     → 执行最终权限校验
ThreadIsolationGovernanceFilter → 执行线程隔离与切换
TerminalInvokerFilter           → 执行真实终端调用、生成模拟结果，或在特定模式下跳过终端执行
```

**三种执行模式**（让多个入口复用同一内核的关键机制）：

| 模式 | 含义 | 典型用途 |
| :-- | :-- | :-- |
| `NORMAL` | 执行治理并进入真实终端调用 | 灵元间标准调用 |
| `SIMULATION` | 跑完整治理链，但不产生真实副作用 | Dashboard 模拟与解释 |
| `GOVERN_ONLY` | 执行治理，但不在 Pipeline 内做终端调用 | Spring Web 请求与灵核 Bean 拦截，真实业务仍由原框架路径执行 |

**调用上下文分区**：为防止传统的基于 `Map<String, Object>` 的魔术键（Magic Key）泛滥，`InvocationContext` 被显式划分为四大协议分区：

- `routingState`（路由分区）：指明请求应发往哪个实例（如目标版本、标签）
- `resolutionState`（解析分区）：承载类加载器、方法等短命强引用（强制要求在回收时物理清空，严防跨调用残留）
- `governanceState`（治理分区）：承载权限、审计、限流、超时等不可篡改的运维意图
- `executionState`（执行分区）：掌管当前调用是否触发真实副作用或记录追踪迹

**硬约束**：

- SPI/动态过滤器不得占用内置保留 order，须选择核心阶段之间的非保留序号
- Pipeline 数据流动必须可追溯，禁止扩大字符串魔术键承载核心语义

**路由三层物理分工**：`ContractProviderRoutingFilter` 内部按物理事实分三层，层间单向数据流、零身份泄漏：

```
① 物理安全过滤层
   - 自动剔除 status != READY 的节点（STOPPING/DYING 天然不可选）
   - 方法资格过滤（lingServiceRegistry.hasMethod，剔除未覆盖方法的节点）
   - 输出：物理合格 Candidate 列表
   - 现有底子：filterByMethod + LingRuntime.getReadyInstances
        ↓
② 泛化选路计算层
   - 子顺序：先 LabelMatchRouter 标签精确匹配（命中即返）→ 退化到 ProviderWeightRouter N 元权重概率分流
   - 有效权重：Dashboard 运行期覆盖 > 注册时初始 weight
   - 容量：天然支持 N 个候选，候选数 > 2 时仅「候选数变化」时 WARN 一次，不抛异常强打断业务
   - 输出：选中目标 Provider
        ↓
③ 原生引用计数与排空防护
   - 选中节点执行 enter() 计数 +1，exit() 计数 -1
   - 被替换/下线节点进入 STOPPING 时，依托 awaitIdle() 进行物理排空
   - 现有底子：LingInstance.activeRequests + exit + awaitIdle
```

层间物理定律：

| 定律 | 内容 | 依据 |
| :-- | :-- | :-- |
| 单向数据流 | 第一层输出 → 第二层输入 → 第二层输出 → 第三层接管 | 避免双向耦合死循环 |
| 层间零身份泄漏 | 任一层不判「灵核 vs 灵元」「稳定 vs 金丝雀」具名身份，只认 `weight` + `labels` + `version` + `READY` 谓词 | SPI 纯洁性约束 |
| 物理安全先于选路 | 第一层是第二层的前提——非 READY 或方法资格不过的节点不进选路 | JVM 物理事实：STOPPING 节点不可选 |
| 路由与排空分工 | 路由只负责「在 READY 节点中选一个」；卸载排空由 `LingInstance` 物理接管 | JVM 物理定律：不排空卸载必泄露 |

### 4. Shared API：进程级公共契约

**它解决的问题**：灵元需要与灵核、与其他灵元共享契约（接口、DTO）。如果没有正式的进程级边界，"共享契约"会悄然退化为"共享实现"，热卸载变得不安全。

**设计**：`SharedApiManager` 把 `Shared API` 的边界显式化到了启动流程里：

1. preload 配置好的共享 JAR 或 classes 目录
2. 注册共享包前缀
3. **freeze** 共享边界
4. 然后才允许灵元基于冻结后的契约视图加载

**核心规则**：

- 全新的共享 JAR **可以**热加载进入共享边界
- 已经加载过的共享契约**不能**在同一进程里热更新或热卸载
- 任何替换、覆盖、删除、回滚、重命名、签名变化的 Shared API 变更，都必须通过**重启进程**才能安全生效

**Shared API 里应该放什么**：

- 接口
- DTO
- 必要注解

**不应该放什么**：

- 业务实现逻辑
- 共享行为或共享服务

**设计立场**：Shared API 是**消费者驱动契约**。只允许向后兼容的增量演进。优先新增接口、默认方法、新 DTO、新版本命名空间——不要直接改旧契约语义。

### 5. 类加载隔离与诚实边界

**它解决的问题**：在单 JVM + 共享 Spring 下，"绝对隔离"是物理不可能——进程级静态缓存（`AnnotatedElementUtils`、`BridgeMethodResolver.cache` 等）会持有灵元 Class 引用。把"完全隔离"吹成架构承诺，会制造虚假安全感和 unsafe 卸载预期。

**设计**：`LingClassLoader` 采用 **Child-First** 加载，白名单强制父委派 `java.*`、`com.lingframe.api.*`、`org.slf4j.*` 等系统/API 包。三层类加载边界：

```
SharedApiClassLoader（共享边界）
        ↓ parent
LingClassLoader（灵元业务类，Child-First）
        ↓ parent
父 ClassLoader（生态包，由 runtime 注入；core 不绑定 Spring）
```

**架构能承诺的（SLA）**：

1. **加载隔离**：灵元业务类由 `LingClassLoader`（Child-First + 白名单父委派）加载
2. **契约边界**：Shared API 冻结；灵元只依赖 `lingframe-api`，不依赖 `lingframe-core`
3. **控制面边界**：路由 / �治理尽量只持有字符串与弱引用，避免把灵元 `Method` 注册进灵核 `HandlerMapping`
4. **卸载契约**：双阶段清理 + 证据驱动清单 + ClassLoader 可 GC 为门控（不是"运行期缓存键归零"）
5. **可观测**：泄漏检测、dump、引用链分析闭环

**架构明确承认不能承诺的（在共享 Spring 下）**：

- 进程级任何 `static Map` / SoftReference / JDK 元数据都不键入灵元 Class——做不到
- 运行期"灵核框架永不持有灵元 Class 的任何引用（含 Soft、含 Spring 私有缓存）"——做不到
- 卸载后缓存键归零——做不到；做的是"ClassLoader 可 GC 可证明"

**诚实表述**：灵珑的隔离是**类型隔离 + 编排隔离 + 卸载后可证 GC**，**不是"运行期与灵核 Spring 静态宇宙正交"**。后者不是灵珑写得不够狠的实现细节，而是"单 JVM + 共享 Spring + 零 Agent"前提下 JVM 共享语义本身的物理结果。

### 6. 生命周期编排与卸载作为正式运行时路径

**它解决的问题**：动态加载容易；长期运行进程里的规范热卸载难。如果卸载、清理、泄漏诊断被当成事后工具而不是正式运行时职责，长期稳定性退化。

**设计**：`DefaultLingLifecycleEngine` 是顶层生命周期编排器。它把部署、重载、卸载意图翻译成有序运行时动作，但把状态写入留给 `InstanceCoordinator` 和 `RuntimeCoordinator`。

**部署**：

- 校验灵元定义与安全约束
- 创建 ClassLoader 与容器
- 在首个实例事实出现前先注册运行时聚合（`RuntimeCoordinator.register` 时序：必须先 `register(lingId)` 再出现实例状态事件）
- 驱动实例进入 `LOADING → STARTING → READY`
- 先把实例放入池中，再向上发布 `READY` 事实

**重载**：

- 先旁路部署一个替代实例
- 保留原实例的 default 角色与 labels
- 切流到新实例
- 在新实例 ready 后再卸载旧实例

**卸载**：

- 先把实例标记为 `STOPTING`
- 等待飞行中请求排空，直到空闲或超时
- 驱逐服务、Pipeline 持有资源、缓存与 ClassLoader 关联状态
- 把泄漏诊断纳入卸载完成流程

**卸载作为编排化运行时职责**：

`LingUnloadCoordinator` 协调清理：双阶段清理、资源回收、证据驱动清单。`DefaultLeakDetector` 提供分级泄漏诊断：

- `DEV_AGGRESSIVE`：开发态激进诊断
- `DEV_BOUNDED`：降级有界诊断
- `PROD_PASSIVE`：生产态被动观测

均通过有界并发限制避免在排查时引发 GC 风暴。

**真正特别的**：不是系统"支持卸载"这件事本身，而是卸载已经被当成需要正式编排、清理和诊断的运行时职责——属于正式运行时路径，不是事后工具。

### 7. 双栈适配：类型化差异，不反射探测

**它解决的问题**：Spring Boot 2（`javax.servlet`）和 Spring Boot 3（`jakarta.servlet`）Servlet 命名空间不兼容。朴素做法要么破坏一端，要么把 `javax` / `jakarta` 反射探测泄漏到公共代码。

**设计**：灵珑对面向 Servlet 差异的两层用**两种不同工程策略**：

**Runtime 层** — 公共 starter + 栈专属 starter：

| 模块 | 角色 |
| :-- | :-- |
| `lingframe-spring-boot-starter` | 公共适配：装配、属性、资源清理、与 Servlet 无关的 Web 支撑；可被两边依赖 |
| `lingframe-spring-boot2-starter` | SB2 / `javax.servlet`：类型化 Web 治理 Filter、可重复读 Filter、网关 Mapping、AutoConfig |
| `lingframe-spring-boot3-starter` | SB3 / `jakarta.servlet`：与 boot2 对等的类型化实现 |

Servlet 类型差异**必须**落在 boot2/boot3 starter 源码里，用类型安全实现。公共 starter 不能反射探测 `javax` / `jakarta`。需要 Filter / Request 类型时，通过工厂接口（如 `LingRepeatableReadFilterFactory`）由栈专属模块提供实现并由 AutoConfig `@Import`。

**Dashboard 层** — 单 GAV + 矩阵源码集：

`lingframe-dashboard` 保持**单 artifact 坐标**。Servlet 差异由 `build-helper-maven-plugin` 矩阵源码集表达：

| 目录 | 内容 |
| :-- | :-- |
| `src/main`、`src/test` | 业务、配置、与 Servlet 类型无关的代码与测试 |
| `src/java-javax`、`src/test-javax` | SB2：`javax.servlet` 安全 Filter / Interceptor 等 |
| `src/java-jakarta`、`src/test-jakarta` | SB3：`jakarta.servlet` 对等实现 |

矩阵源码集按 profile 追加（默认 javax；`-Pspring-boot3` 切 jakarta）。

**硬约束**：

- `lingframe-core` / `lingframe-api` 不绑定任一 Servlet 命名空间
- 禁止在 `src/main` 公共业务代码反射探测 Servlet API
- 生命周期初始化优先用 Spring 通用接口（`InitializingBean` / `DisposableBean`），避免 `javax.annotation.PostConstruct` / `PreDestroy` 绑死一端
- **禁止**为 Dashboard 拆 boot2/boot3 双坐标模块——runtime 已用双 starter 表达差异

**验证矩阵**：

```bash
# 主路径
mvn -B clean verify -Pspring-boot2,integration-check

# 支持线（需 JDK 17）
mvn -B clean verify -Pspring-boot3
```

从 SB3 切回 SB2 时务必带 `clean`：SB3 产出的 class 在 JDK 8 上会直接失败。

### 8. 控制面消费真实内核事件

**它解决的问题**：如果 Dashboard 维护一层与运行时内核脱节的平行解释层，控制面和运行时会各自漂移——Dashboard 显示一套故事，内核活另一套。

**设计**：Dashboard 越来越直接消费**真实内核事件**，而不是维护平行解释层。控制面贴在同一条运行时脊柱上。

**机制**：

- `MonitoringEvents` 定义 trace、audit、alert、circuit-breaker、leak-detection 等统一事件语义
- `EngineTrace` 用来保留可解释的决策追踪，供模拟和内核推理使用
- `LogStreamService` 通过 SSE 把这些事件流推送到 Dashboard
- `InvocationPipelineEngine.evictLingResources` 与方法缓存驱逐支撑卸载清理

**入口对同一内核的复用**：

| 入口 | 适配器 | 如何使用内核 |
| :-- | :-- | :-- |
| 灵元服务调用 | Core 标准调用路径 | 通过 `NORMAL` 模式执行完整 Pipeline |
| Spring Boot 2 / 3 Web 请求 | 类型化 `LingWebGovernanceFilter`（`spring-boot2-starter` / `spring-boot3-starter`） | 通过 `GOVERN_ONLY` �借道治理，终端分发仍由 Web 框架完成 |
| 灵核 Bean 方法 | `LingCoreBeanGovernanceInterceptor` | 在 AOP 拦截中通过 `GOVERN_ONLY` 复用治理能力 |
| Dashboard 模拟 | `SimulateService` | 通过 `SIMULATION` 跑真实治理链路但不产生真实副作用 |
| Dashboard 服务演练场 | `ServicePlaygroundService` | 默认 `NORMAL` 真调用便于验接口；请求可显式 `SIMULATION` |

**重要区分**：多入口共享的是**治理** Pipeline，不一定共享业务**终端**路径。Web / AOP 在 `GOVERN_ONLY` 后仍由灵核侧框架路径执行业务。

这也是当前实现与更早零散能力拼装状态的本质差别：控制面贴在同一条运行时脊柱上，而不是挂个旁路解释层。

---

## 可观测性与清理

当前实现进一步拉近了治理与运维的关系。

- `EngineTrace` 用来保留可解释的决策追踪
- `MonitoringEvents` 定义 trace、audit、alert、circuit-breaker、leak-detection 等统一事件语义
- `LogStreamService` 通过 SSE 把这些事件流推送到 Dashboard
- `InvocationPipelineEngine.evictLingResources` 与方法缓存驱逐支撑卸载清理
- `DefaultLeakDetector` 支持分级泄漏诊断策略（包含 `DEV_AGGRESSIVE` 激进诊断、`DEV_BOUNDED` 降级有界诊断、`PROD_PASSIVE` 生产态被动观测三种模式），并通过有界并发限制避免在排查时引发 GC 风暴

架构上的重要变化在于：Dashboard 开始消费**真实内核证据**，而不是单独维护一层解释视图。

这也是灵珑和普通"动态加载 + 管理后台"组合思路的差异所在：
控制面消费的是同一条运行时主链上的真实事件，而不是旁路出来的一层影子解释。

---

## 当前边界

当前对外公开的架构仍然有清晰边界：

- 它仍然是**单进程**治理内核
- `Shared API` 仍然是**进程级契约边界**
- 一旦共享边界已经 freeze，共享契约变更仍然需要重启进程
- 基础设施代理当前以存储与缓存路径最清晰，更多代理生态仍在后续演进
- **真实流量回放验证不属于当前公开能力集**
- 消息 / 搜索代理扩展仍在后续演进，不是已完成的公开能力

这些边界是刻意保留的，也应该在对外文档中持续保持可见。

---

## 怎么读这个项目

| 模块 | 先看什么 |
| :-- | :-- |
| `lingframe-api` | 契约面与共享词汇 |
| `lingframe-core` | 真正的治理内核与运行时收敛点 |
| `lingframe-runtime` | 公共 `spring-boot-starter` + 栈专属 boot2/boot3 starter（类型化 javax / jakarta；无反射探测 Servlet） |
| `lingframe-dashboard` | 单 GAV 控制面；Servlet 相关安全类型在 `src/java-javax` / `src/java-jakarta` |
| `lingframe-infrastructure` | 当前存储 / 缓存代理参考路径 |
| `lingframe-examples` | 把文档接到可运行环境的最短路径 |

状态所有权细节，见上文 §1。
