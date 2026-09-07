# 灵珑开发手册

> 本手册是灵珑当前阶段的统一开发规范源。
>
> 适用对象：
> - 第一次进入仓库的新同学
> - 参与架构、开发、测试、文档编写的维护者
> - 任何会修改本仓库代码、文档、测试的 AI 助手
>
> 若旧文档、历史习惯、局部实现与本手册冲突，以本手册、[manifesto.md](manifesto.md)、[why.md](why.md) 与当前代码事实为准。

---

## 1. 总原则

如果你只记得几件事，就先记住下面这 6 条：

1. **第一原则是为人服务，而且是长期地为人服务。**
2. **灵珑首先是面向 JVM 单进程长期运行系统的运行时治理框架，不是功能集合。**
3. **先收束边界、隔离、权限、回收、可观测，再讨论功能扩张。**
4. **复杂性不会消失，但必须待在可控、可解释、可验证的位置。**
5. **任何设计都要先回答：谁有写权限，谁只读，谁负责编排。**
6. **新人看不懂的文档、无法验证的语义、无法追踪的治理，都不算完成。**

当你对某个改动拿不准时，先回到这 6 条。

---

## 2. 灵珑风格

灵珑的风格不是“文学气质”，而是一套稳定的工程判断。

### 2.1 灵珑偏好的方向

- 为人服务，而不是为了技术完美牺牲理解成本、维护体验与协作信心
- 边界优先，而不是先堆功能再回头补约束
- 唯一真源，而不是多个对象各自维护一份状态
- 运行时治理优先，而不是把问题一直推给部署形态
- 骨架稳定、实现可替换，而不是频繁改动公共语义
- 长期运行可观测，而不是只在理论上“应该可修”
- 务实、低侵入、可回退、可验证，而不是浪漫化重构

### 2.2 不符合灵珑风格的信号

如果一个方案虽然“技术上能做”，但它：

- 扩大隐式状态和字符串魔法键
- 把复杂性继续堆进 Spring / JVM 深水区补丁
- 依赖“默认兜底放过”而不是边界清晰
- 让 timeout、卸载、权限等语义无法证明
- 为了架构整洁牺牲理解成本和维护体验
- 让写权限散落到多个对象、多个层次
- 为了兼容保留已经确认错误的旧边界

那它通常就不符合灵珑风格。

---

## 3. 术语与表达

术语必须统一。不要在不同文档、代码、日志、测试里各叫各的。

| 术语 | 含义 | 禁止或不推荐 |
| --- | --- | --- |
| 灵珑 | 整个项目 / 整个框架 | Ling 插件系统、插件平台 |
| 灵核 | 承载治理能力的核心进程 / 核心应用侧 | 宿主、Host |
| 灵元 | 被治理、被隔离、可装载 / 卸载的运行单元 | 插件、Plugin |
| 运行时 | 某个灵元在灵核内的运行时聚合视图 | 宿主状态对象 |
| 实例 | 某个灵元某个版本的具体运行实体 | 版本对象、槽位对象 |
| 共享 API | 灵元之间共享的接口与 DTO 契约层 | 共享实现、共享业务逻辑 |
| 治理内核 | `lingframe-core` 提供的治理能力集合 | 业务层、应用层 |

### 3.1 术语硬规则

- 中文语境下，项目名优先使用“灵珑”；如需补充英文名，写作“灵珑（LingFrame）/ 灵珑 · LingFrame”
- 英文语境下，项目名使用 `LingFrame`
- 不要在中文语境中写成“LingFrame（灵珑）”或“LingFrame 的……”
- 文档、注释、评审说明中统一使用“灵核”“灵元”
- 不要把灵核重新叫回“宿主”
- 不要把灵元重新降格成“Plugin”
- 若旧字段、旧文档仍有历史术语，新增内容不得继续沿用

---

## 4. 新人最小心智模型

第一次进入代码库，不要试图一次性背下所有类。先记住这套最小模型。

### 4.1 运行时双层状态机

- **实例层**回答：某个具体版本实例现在真实处于什么生命周期阶段
- **运行时层**回答：某个灵元作为整体现在对外呈现什么宏观状态
- 两层之间有关联，但没有互相直改
- 联动依赖事件与快照，不是对象之间互相拿着对方乱改
- **全量卸载 drain 窗口**：`undeployWithReport` 先将活跃实例 `moveToDying`（实例层 STOPPING），随后立即 `enterRuntimeStopping` 推进运行时层 STOPPING，再阻塞排空——drain 窗口内宏观状态呈现 STOPPING（下线意图）而非 ACTIVE，消除「实例已停摆 / 宏观仍 ACTIVE」的状态失真；drain 完成后实例全部 DEAD，运行时层自动推进 REMOVED，最后由 `unregister` 收口。

### 4.2 五个关键角色

| 角色 | 作用 | 核心约束 |
| --- | --- | --- |
| `LingInstance` | 单个灵元实例的承载体 | 对外不暴露状态机写权限 |
| `InstanceCoordinator` | 实例状态唯一写入口 | 只有它能推进实例状态 |
| `InstancePool` | 管理活跃成员、默认实例、濒死队列 | 只管成员关系，不管完整生命周期 |
| `LingRuntime` | 灵元运行时聚合体 | 对外只暴露只读视图，不持有运行时状态机写权限 |
| `RuntimeCoordinator` | 运行时状态唯一写入口 | 只有它能推进 `RuntimeStatus` |

### 4.3 两个编排角色

| 角色 | 作用 | 不能做什么 |
| --- | --- | --- |
| `DefaultLingLifecycleEngine` | 组织部署、切换、卸载顺序 | 不能绕过 coordinator 直接改状态 |
| `LingUnloadCoordinator` | 负责卸载清理、资源回收、泄漏检测 | 不能替代生命周期编排 |

如果你理解了这 7 个角色，仓库里大多数架构判断都能看懂。

---

## 5. 仓库结构与职责边界

| 模块 | 角色 | 允许做什么 | 不允许做什么 |
| --- | --- | --- | --- |
| `lingframe-api` | 契约层 | 定义灵元必须的接口、注解、基础契约 | 放业务实现、放重依赖 |
| `lingframe-core` | 治理内核 | 生命周期、状态机、权限、审计、路由、隔离、治理 | 依赖任何生态环境 |
| `lingframe-runtime/*` | 运行时适配层 | 把治理内核接到 Spring Boot 等运行时生态 | 反向污染 `lingframe-core` 的边界 |
| `lingframe-infrastructure/*` | 基础设施代理层 | 以代理方式接管 DB / Cache / 消息等能力 | 让业务灵元直接穿透到底层设施 |
| `lingframe-dashboard` | 可视化与治理入口 | 展示状态、更新治理策略、触发治理动作 | 越权写入核心内部状态 |
| `lingframe-examples` | 示例与验证 | 演示典型使用方式 | 承担核心设计事实来源 |

### 5.1 依赖规则

- 业务灵元依赖 `lingframe-api`，不依赖 `lingframe-core`
- `lingframe-core` 必须尽量保持纯 Java 核心，不以 Spring 为设计前提
- 运行时适配放在 `lingframe-runtime`，不要反向把适配细节塞回 `lingframe-core`
- 基础设施能力通过代理接入治理，不让业务灵元裸连资源

---

## 6. 架构硬约束

这一章是红线。违反这里的规则，通常不是“风格差异”，而是架构退化。

### 6.1 写权限必须清晰

任何设计都必须先回答：谁有写权限，谁只读，谁负责编排。

| 概念 | 唯一真源 | 唯一写入口 | 其他角色 |
| --- | --- | --- | --- |
| 实例状态 | `LingInstance` 内部状态机 | `InstanceCoordinator` | 其他对象只能读或响应事件 |
| 运行时状态 | `RuntimeCoordinator` 内部 FSM / 快照 | `RuntimeCoordinator` | `LingRuntime` 只读 |
| 实例成员关系 | `InstancePool` | `InstancePool` 受编排驱动变更 | 不额外维护第二份成员真源 |
| 生命周期阶段顺序 | `DefaultLingLifecycleEngine` | 编排逻辑本身 | 不能把顺序逻辑散落到各对象 |
| 卸载清理 | `LingUnloadCoordinator` | 清理协调器 | 不能由任意业务代码直接接管 |

### 6.2 状态机相关规则

- 不允许在业务代码中直接操作状态机
- 不允许让 `LingRuntime` 重新持有第二份 runtime FSM
- 不允许让 `LingInstance` 暴露公共状态修改 API
- 不允许让 `InstancePool` 演化成“生命周期总控器”
- 不允许让编排层跳过 coordinator 直接改状态
- 不允许为了兼容旧接口重新开放已经收敛掉的状态修改入口

### 6.3 事件联动规则

- 状态联动优先走事件，不走对象间互相改状态
- 实例层事件向上汇聚到运行时层，运行时层不反向篡改实例事实
- 运行时聚合优先使用**快照**，不要直接扫描复杂对象图作为唯一依据
- 新增联动链路前，先说明它属于实例层、运行时层、成员层还是卸载层

### 6.4 Shared API / ClassLoader 规则

- Shared API 里只放接口、DTO、必要注解，不放业务实现
- Shared API 的目标是保证契约一致，不是共享逻辑
- 新增共享包、共享 JAR、类加载边界规则时，必须同步考虑卸载和类型一致性
- 不要为了“方便”把实现类塞进共享 API
- 类加载冻结、共享边界冻结这类语义不能只停留在口头约定，必须有 API、日志和测试支撑

### 6.4.1 Shared API 契约演进硬约束

灵珑中的 `Shared API` 不是普通依赖，而是进入共享类加载边界后的**进程级公共契约**。

- Shared API 设计遵循**消费者驱动契约**
- Shared API 只允许**向后兼容的增量演进**
- **全新的 Shared API JAR** 可以热加载进入共享边界
- **已经进入共享边界的 Shared API JAR** 不允许热更新，也不允许热卸载
- 任何涉及替换、覆盖、删除、回滚、重命名、签名变化的 Shared API 变更，都必须通过**重启进程**生效

### 6.4.2 Shared API 破坏性更新判定

以下情况一律按**破坏性更新**处理：

- 删除、重命名或移动已有接口、类、方法、字段、枚举项
- 修改方法签名、返回类型、参数类型、泛型边界、异常契约
- 修改 DTO 字段名、字段类型、序列化结构、必填语义、默认值语义
- 修改注解语义，导致既有消费者行为变化
- 修改枚举值含义、排序、名称，导致既有判断逻辑或序列化结果变化
- 任何会让既有消费者需要重编译、重适配、重解释才能继续安全运行的变更

### 6.4.3 Shared API 设计建议

- 优先新增接口、默认方法、新 DTO、新版本命名空间，不直接改旧契约语义
- 共享层只表达“能说什么”，不表达“怎么做”
- DTO 尽量保持简单、稳定、可序列化，不塞业务逻辑
- 如确实需要破坏性变化，应显式引入新版本契约，并通过进程重启切换

### 6.4.4 Shared API 变更交付要求

任何涉及 Shared API 的改动，提交时至少要补齐：

- 兼容性说明
- 影响面说明
- 升级方式说明
- 测试或验证说明

### 6.5 治理语义必须可证明

以下语义不能靠“大家默认懂”：

- timeout
- permission
- audit
- unload
- resource cleanup
- routing fallback
- degraded / stopping / removed 等状态含义

要求：

- 必须有明确归属对象或归属状态
- 必须能被日志、事件、测试或文档解释
- 必须有失败路径，而不只是成功路径
- 必须能被验证，不接受“理论上应该如此”

### 6.6 反射与底层补丁规则

灵珑不是禁止反射或 JVM 深水区处理，而是禁止**失控地使用**。

如果必须做反射或 JVM 补丁：

- 先证明这是必要复杂性，而不是偷懒复杂性
- 把复杂性封装在边界内，不能到处散开
- 写清楚风险、前提、失败后果和退出策略
- 配套测试、日志和可观测性说明
- 不把不可维护的补丁扩散成项目通用模式

### 6.7 治理流水线与 SPI 过滤器规则

治理流水线（Pipeline）是由 `FilterRegistry` 在启动时严格校验和保护的核心防线：
- **内置保留位**：`[100, 1000]` 范围内的特定 order 被内置基础、路由、权限、隔离过滤器占用。
- **沙箱约束**：外部通过 SPI 或动态注册注入的 `LingInvocationFilter`，其 `order` 必须避开这些内置保留位（推荐使用 `order < 100` 的前置处理，或在特定保留区间之间的空隙）。
- **Fail-Fast**：一旦 SPI 过滤器非法占用内置位，内核将在启动期立刻抛出异常并失败，拒绝以“失真的治理链”处理线上流量。

**路由层去身份化原则**：路由层（`ContractProviderRoutingFilter` / `ProviderWeightRouter`）只认 `weight` 和方法资格，不引用实现方身份（灵核/灵元）。身份在注册时沉淀为 `weight` 数值：灵核默认 `weight=100`，灵元默认 `weight=0`。方法资格通过 `LingServiceRegistry.hasMethod(lingId:contractId, methodName, paramTypes)` 判定——未声明被调用方法的 provider 被自然剔除，流量落回声明了该方法的 provider，方法级 fallback 是路由的副产物而非新增能力。

**路由层 N 元权重分流（「禁止叠加」从规范升级为系统能力）**：同一契约同一时刻允许多 provider 共存，由 `ProviderWeightRouter` 按权重比例随机分配（二元只是 N=2 的特例，N≥3 即多版本共存/多租户场景）：

- **注册层允许多 provider**：`DefaultLingServiceRegistry.registerProvider` 允许任意 N 个 provider 注册，Dashboard 控制权重覆盖。
- **路由层 N 元权重分流**：`ProviderWeightRouter.selectProvider` 天然支持任意 N 个候选按权重比例随机分配。候选数 > 2 时仅「候选数变化」时告警一次（避免热路径打满日志），**不主动抛异常强打断业务**——承认多版本共存（稳定版 + 灰度版 + 紧急 Patch 版）是生产真实需求。

N 元权重分流不是"理论上应该如此"，而是有 API、日志、测试支撑的系统能力。`MigrationPhase` 状态机表达功能（契约）流量治理的宏观阶段——CORE_EXCLUSIVE / MIGRATING / LING_EXCLUSIVE / ITERATING，二元态（N=2）是特例，N≥3 时即多版本共存/多租户场景，路由器天然支持。

**事务穿透阶段（`TransactionPropagationFilter`，order=250，位于 `POLICY_PREFILL`(240) 与 `RESILIENCE`(300) 之间）**：路由确定之后、TCCL 切换之前，把上游活跃事务的物理连接按 dataSourceId 推入 `LingTransactionContext`，供下游灵元经受管数据源代理复用（跨灵元单机 ACID，细则见 ADR-0005）：

- **SPI 化（硬约束）**：core 只面向 `core.spi.TransactionBindingHook`（`isTransactionActive` / `getActiveBoundDataSourceIds` / `getBoundConnection`），Spring 实现（对接 `TransactionSynchronizationManager`）下沉 runtime starter——core 零 Spring 的模块边界不可因穿透功能破坏。
- **执行模式门控**：仅 NORMAL 模式穿透；SIMULATION / GOVERN_ONLY 直接放行（二者无真实终端执行，压栈的连接无消费者）。
- **总开关**：`lingframe.tx.propagation.enabled`（默认 `true`）关闭时过滤器直接放行、灵元侧不注册受管事务管理器——应急降级路径，关闭期间跨灵元原子回滚不可用；它是「逃生门」而非常规配置，恢复后需验证穿透链路。
- **线程边界（双端协同）**：主线程端（本过滤器 push / rollbackOnly 信号回传 / finally 弹栈 + `cleanIfEmpty`）与 worker 线程端（`ThreadIsolationGovernanceFilter` 经 `ThreadLocalPropagator` 快照搬运，restore 采用**合并语义**——`carrier.rollbackOnly |= worker 期间置位`——回传信号）缺一不可；任何一端遗漏都会造成连接强引用残留或回滚信号丢失（静默部分提交）。
- **超时/放弃执行**：穿透连接独占整条跨灵元调用链；超时后 `cancel(true)` + 有界 join（`lingframe.runtime.abandoned-join-timeout`，默认 2s）+ 宽限期超时则 poisoned 废弃（跳过 rollback 直接 close，未提交写随 close 丢弃，治理指标 `connectionPoisonedCount` 计数）——宽限期是概率性缓解而非硬保证，**不得声称「超时后连接已安全」**。

### 6.8 迁移状态机（`MigrationPhase`）

路由层与功能管理层（迁移状态机）彻底拆分，建立双层清晰架构：

- **功能管理层**：`MigrationPhase` 枚举（`CORE_EXCLUSIVE` / `MIGRATING` / `LING_EXCLUSIVE` / `ITERATING`）+ `MigrationStateHolder`，表达"迁移阶段是路由层的元状态"。
- **路由层**：`ProviderWeightRouter` 纯权重 N 元选路，支持任意 N 个候选按 weight 比例随机分配（二元只是 N=2 的特例）。

四状态迁移图：

```
CORE_EXCLUSIVE ──startMigration──→ MIGRATING
MIGRATING      ──confirmPhase───→ LING_EXCLUSIVE
MIGRATING      ──rollback──────→ CORE_EXCLUSIVE
LING_EXCLUSIVE ──startIteration─→ ITERATING
ITERATING      ──confirmPhase───→ LING_EXCLUSIVE
ITERATING      ──rollback──────→ LING_EXCLUSIVE
```

归属与边界：

- `MigrationPhase` / `MigrationStateHolder` 归属 `com.lingframe.core.routing` 包，与路由器同包。
- **不入侵运行时 FSM**（`RuntimeStatus`）：`MigrationPhase` 是路由层的元状态，与实例/运行时状态机正交。
- `MigrationStateHolder` 是迁移阶段的唯一真源，`DefaultLingLifecycleEngine` 编排 + `confirmPhaseTransition` 显式确认推进阶段。

显式确认 + 排空校验机制：

- 否定"权重归零即自动相变"的过度自动化，防止运维临时拉零观察时触发不可逆跃迁。
- 采用"权重归零为必要条件 + 显式确认指令（`confirmPhaseTransition`）"。
- **无自动推进**：`MigrationStateHolder` 不监听任何权重变更事件；`ProviderWeightChangedEvent`
  历史上宣称被监听（死广播），实际从未订阅，已删除发布与伪声明。阶段推进仅由
  `startMigration` + 权重调整 + 排空校验 + `confirmPhaseTransition` / `rollbackPhaseTransition` 显式驱动。
- 确认相变前校验两个硬指标：
  1. 待退出方的权重必须已降为 0；
  2. 待退出方的在途请求数必须已排空（`activeRequests == 0`）。

相变方向控制：

- 归零并确认退出的是 `oldCandidateKey` → 视为"迁移/迭代完成"（前进至 EXCLUSIVE 阶段，注销旧候选位）。
- 归零并确认退出的是 `newCandidateKey` → 视为"迁移/迭代回滚"（后退至上一个 EXCLUSIVE 阶段，注销新候选位）。

Provider 标识与版本化注册：

- **注册键恒带版本**：写侧 `registerProvider(contractId, lingId, version, weight)` 统一携带版本——灵核标识裸 `lingcore-app`（无实例上下文，版本为 `null`），灵元标识恒为 `lingId:version`（版本真源 `DefaultLingContext.getVersion()`，从绑定实例派生）。迁移期与迭代期一致，不再区分。
- **同灵元多版本并存**：同一灵元部署两个版本时并存两个 provider 候选（例如 `user-ling:1.0.0` 与 `user-ling:1.1.0`），路由层按权重分流。
- **退役版本精确清理**：实例退役时按 `lingId:version` 精确驱逐该版本 provider，其余版本继续服务；灵元全量卸载才做全量 evict（`evictProvider(lingId)` / `evict(lingId)`）。
- **无灵核基线兜底**：某契约下无 `weight=100` provider 且所有 provider 权重为 0 时，首个注册 provider 提升为基线 100，杜绝「全部为 0 的静默空转」。Dashboard 权重覆盖的 `providerKey` 恒为 `lingId:version`（灵核为 `lingcore-app`）。

持久化与重启一致性：

- `MigrationPhase` 状态及候选元数据（`lingId`, `phase`, `oldCandidate`, `newCandidate`）统一持久化至 `GovernanceStorage`（`config_type = 'migration'`）。
- `GovernanceConfigRestorer` 在启动恢复时重建 `MigrationStateHolder` 阶段（含候选元数据），保障重启后迁移/迭代阶段语义一致；旧灰度 `percent` 格式向后兼容映射。
- **诚实边界**：`ProviderWeightRouter` 的权重覆盖为运行期下发、**不持久化**。重启后切流比例回到注册默认权重，需要运维重新下发；不能声称「重启前后状态完全一致」。

### 6.9 数据源代理边界与受管数据源总线

**SQL 治理代理边界**：

- 灵珑的 SQL 治理依赖对 `DataSource` 的代理。
- 如果是由 Spring 容器管理的 Bean，`DataSourceWrapperProcessor` 会自动进行拦截与包装。
- **红线**：如果业务代码或三方件直接通过 `DriverManager`、静态代码块、或自行 `new HikariDataSource()` 创建了不归 Spring 容器管辖的数据源，它们将脱离治理网络。
- **要求**：开发者必须显式调用 `LingConnectionProxyFactory.wrap(...)` 手动包装此类野生数据源，否则其数据库访问将绕过所有隔离和鉴权规则。

**受管数据源总线（`ManagedDataSourceRegistry` / `ManagedDataSourceProvider`，`api.storage` 包）**：与 `LingServiceRegistry`（FQSID 服务契约目录）职责分离的独立总线，承载「dataSourceId → 受管 DataSource」的基础设施引渡，不污染业务服务目录：

- **三模式**：模式 1 灵核静态托管（dataSourceId 恒为 `default`，开箱即用推荐态，灵核 `application.yml` 静态配置运行期不可变）；模式 2 灵元独立库（灵元自配 `spring.datasource.url` 自建连接池，物理隔离态）；模式 3 存储灵元动态外挂（灵元配置 `lingframe.ling.datasource-id` 声明供给身份，自建数据源以该 id 注册到总线，业务灵元经 `lingframe.ling.datasource-ref` 拉取共享——默认 `default`）。模式 3 **只增不减**：基础设施灵元本期不提供热卸载，卸载入口禁用；业务灵元（模式 2）卸载不受影响。
- **身份门控（硬约束）**：受管代理携带 `dataSourceId`，`getConnection()` 只按自身 id 精确查穿透上下文连接栈；模式 2 私有池代理 id 为 null、**永不查栈**——混合链路下绝不误用受管连接（串库路径物理切断）。
- **同实例提升（装配契约，P0 级红线）**：`DataSourceWrapperProcessor` 包装产生的代理先以 null 身份存在，注册到总线时必须经 `promoteToManaged(dataSourceId)` **同实例**提升——TSM 资源键以实例为键，「新建带 id 代理替代同实例提升」会导致灵核事务管理器与总线查找键失配、穿透静默失效。该契约由 `ManagedAssemblyChainContractTest`（直接调用真实装配方法，非复刻逻辑）守护。
- **NonCloseable 语义**：穿透命中时返回 `NonCloseableLingConnectionProxy`——`close` / `commit` / `setAutoCommit` 及根连接属性（隔离级别 / 只读 / 保持性）降级为 no-op，`rollback` 仅置 rollbackOnly 信号（经快照合并语义上行回传）；**审计不降级**：事务权限门与 `transaction:*-suppressed` 审计事件全保留，no-op 不豁免治理门。Statement 工厂直通内层（已治理）代理、薄代理只修正 `getConnection()` 视图——**禁止**对内层代理再包一层 Statement，否则每次 SQL 执行两遍权限检查与两遍审计（审计计数虚高）。
- **受管事务管理器（`LingManagedTransactionManager`，双路径）**：判根真源 = `getTransaction()` 时刻穿透上下文连接栈（按 dataSourceId）空与否。根路径借连接 → 设置隔离级别/readOnly → `setAutoCommit(false)` → push，commit/rollback 物理执行 + pop + close 归还池；加入路径不 bind TSM、不碰连接，非根 commit 前检测 rollbackOnly（置位则抛 `LingTransactionRollbackException`，对齐 Spring `UnexpectedRollbackException` 语义）。传播边界：`REQUIRES_NEW` / `NESTED` 物理不可达，显式降级为加入（REQUIRED）并告警；`NEVER` / `NOT_SUPPORTED` 显式拒绝（静默降级会反转开发者意图——事务外写被纳入根事务）；`MANDATORY` 栈空拒绝。根路径 commit/rollback 连接缺失时抛 `TransactionSystemException`（含 dataSourceId 与阶段信息），**禁止裸 NPE**。
- **启动期可见性（把静默失效提升为启动期 WARN）**：灵核根事务管理器非 JDBC 型（如 JPA 根，无法提取连接）、灵核与灵元 TSM 类身份不一致（spring-tx 未父委派、两栈分叉）时输出启动期 WARN——穿透不激活必须可见，禁止「悄悄退化」。
- **穿透上下文（`LingTransactionContext`，`api.storage` 包）**：资源（连接，按 dataSourceId 分栈、向下传递）与信号（rollbackOnly，向上回传）分离的线程局部存储；跨线程搬运由快照（`TransactionSnapshot`，含 pushOrder 压入顺序）完成，`restoreSnapshot` 必须采用合并语义。清理护栏：主线程端 finally 弹栈 + `cleanIfEmpty`，worker 线程端 `restoreSnapshot`，`closeAllConnections`（poisoned 路径）三个 ThreadLocal 一并清空——任何清理不完整的残留都会在线程池复用时污染后续调用。

### 6.10 Dashboard 控制面鉴权规范

控制面（Dashboard）是治理读功能区，其安全默认必须可证明、可回归：

- **鉴权装配 fail-closed**：`lingframe.dashboard.access-token.enabled` 默认 `true`（POJO 默认即强制鉴权），且 Bean 装配条件必须与之一致——`@ConditionalOnProperty(..., matchIfMissing = true)`，即运维只配 `token`、省略 `enabled` 时也必须注册鉴权拦截器。禁止「POJO 默认 true、Bean 条件默认不注册」的不一致（2026-08-03 评审 A1，已修复并有反射测试防护）。
- **Token 恒时比较**：`isValidToken` 必须用 `MessageDigest.isEqual` 恒时比较，禁止 `List.contains`（时序侧信道，A3 已修复）。
- **Playground 权限纪律**：模拟调用临时 `grant` 的能力必须在 `finally` 中 `revoke` 配对，禁止永久累积（A2）；`resolveClass` 禁类初始化（`Class.forName(name, false, cl)`）。
- **转发头白名单**：`X-Forwarded-Prefix / X-Forwarded-Path` 仅在配置白名单 `lingframe.trusted-forwarded-prefixes` 内才被采信；空列表 = 不采信任何客户端转发头（C10）。
- **定时任务**：依赖 `@Scheduled` 的清理/采样任务（ticket、限流桶、指标采样、备份）必须在装配类显式开启调度（B2）。

以上语义均有归属（dashboard 安全组件）、有失败路径（fail-closed 启动失败/拒绝）、有测试与事件支撑，符合「治理语义必须可证明」。

### 6.11 回收职责划分（四层）

卸载后的资源回收按职责分层，**各层不得越权替代或互相覆盖**（依 2026-08-20 边界级 `AutoCloseable` 自动回收方案 v3.2 落地）：

| 层 | 归属 | 负责 | 不负责 |
| --- | --- | --- | --- |
| ① 卫生层 | `LingUnloadHook`（spi） | 跨切面 JVM / 生态泄漏（JDBC 驱动、线程引用、ShutdownHook、日志框架、RMI 等） | 业务对象生命周期 |
| ② 运行时层 | `LingResourceManager` 缓存清理 / 线程池回收 | 进程级通用缓存（Introspector）与按 lingId 共享的线程池 | 逐个资源 close |
| ③ 孤儿层 | `LingResourceManager.closeableRegistry`（本次新增） | 只因"作者未交给 Spring"而游离的 `AutoCloseable` 资源，**逆注册序**关闭 | Spring 管理的 Bean（由容器自关，避免二次关闭） |
| ④ 主入口层 | `Ling.onStop(LingContext)` | 有顺序 / 依赖关系的精细逻辑拆卸 | 物理句柄兜底 |

**关键边界（硬约束）**：

- **孤儿层只登记"非 Spring 管理"的资源**：Spring 灵元路径下 Bean 由 `closedContext.close()` 销毁，若把 Bean 也注册进来会造成二次关闭。作者只需 `ctx.registerCloseable(orphan)` 一行，对 Spring 管理的资源零灵珑 API 导入。
- **版本粒度**：孤儿资源按 `(lingId, version)` 复合 key 登记。`LingUnloadCoordinator` 在 `onVersionUnload` 调 `closeResources(lingId, version)`，保证**多版本滚动更新时旧版本孤儿随版本卸载即时释放、不累积**；`onLingUnload` 调 `closeResources(lingId)` 兜底释放所有残留（含关闭期间迟到注册，有界留存不丢失）。
- **安装失败回滚收敛**：`onFailureCleanup(lingId, version, ClassLoader)` 在钩子清理后追加版本级孤儿关闭，避免 `onStart` 阶段已注册的孤儿在安装失败时泄漏到整 Ling 卸载。
- **并发策略**：注册表操作用类自有 `registryLock` 保证；`close()` 在锁外执行，单个资源 `close()` 阻塞或抛异常不扩散到其他灵元的注册 / 反注册。
- **逆序是启发式近似，非依赖拓扑**：有顺序依赖的关闭必须在 `onStop` 手动编排，孤儿层定位是"物理句柄兜底"。
- **只注册孤儿，不自动扫描全量 Bean**：真实价值是统一注册契约 + 兜底关闭机制。

---

## 7. 开发规范

### 7.1 语言与表达

- 注释用中文
- 日志用英文
- 测试展示名、文档正文、设计说明优先中文
- 术语统一遵循第 3 章

### 7.2 注释要求

灵珑不追求“表面干净”，追求“内容完整且有用”。

必须保留或补充以下注释：

- 设计动机
- 边界说明
- 并发前提
- 状态机联动理由
- 踩坑说明
- 资源回收风险
- 不直观但必要的顺序约束

禁止为了“统一风格”做这些事：

- 删除高价值踩坑注释
- 删除重要设计解释
- 删除风险提示
- 只为了看起来整洁而移除有信息量的内容

### 7.3 类型与结构

- 优先使用显式类型、枚举、状态对象、领域对象
- 谨慎使用 `Map<String, Object>`、附件表、字符串状态键
- 如必须使用字符串键，必须解释来源、范围、生命周期与约束
- 新增复杂上下文时，优先拆成有名字的状态对象，不要继续往大对象上挂零散字段

### 7.4 API 设计

- 骨架与约束稳定，实现允许替换
- 公共 API 命名要表达语义，不要只表达技术动作
- 设计时优先考虑“半年后别人还能不能读懂”
- 不要为了省一个类，把两层职责混到一个对象里

### 7.5 异常、日志、可观测性

- 错误日志必须说明对象、动作、原因和关键上下文
- 不要吞异常
- 不要只在最底层抛异常而上层完全失语
- 治理关键路径必须能从日志看出发生了什么
- 新增关键治理语义时，优先补事件、日志或断言，而不是只补注释

---

## 8. 测试规范

### 8.1 基本规则

- 默认使用 JUnit 5
- 需要 mock 时使用 Mockito
- 核心架构行为优先做单元测试和契约测试
- 测试展示名统一中文，优先使用 `@Nested + @DisplayName`

### 8.2 必测内容

涉及以下内容时，必须优先补测试：

- 状态机迁移
- 生命周期编排顺序
- 多版本切换
- 濒死队列 / 排空 / 回收
- timeout
- permission / audit
- routing
- pipeline 顺序
- classloader 边界
- shared API 冻结语义
- 并发安全
- 卸载后的资源清理
- 事务穿透（跨线程快照搬运 / rollbackOnly 信号合并 / 装配链契约 / ThreadLocal 双端擦除）

### 8.3 测试红线

- 不要只测 happy path
- 不要只测“代码跑通”，不测“语义正确”
- 不要把架构顺序依赖写死在实现细节里却没有契约测试
- 不要因为重构测试风格而改动测试语义
- 不要为了整洁删除有信息量的展示名和说明

### 8.4 推荐的测试补位方式

| 变更类型 | 至少需要的测试 |
| --- | --- |
| 状态机改动 | 状态迁移测试 + 异常迁移测试 |
| 生命周期编排改动 | 顺序测试 + 失败回滚 / 中断测试 |
| Filter 顺序改动 | Pipeline 契约测试 |
| 卸载与回收改动 | 资源回收测试 + 长时间运行退化测试 |
| 权限 / 超时 / 审计改动 | 成功 / 拒绝 / 回退 / 审计测试 |

---

## 9. 文档规范

### 9.1 哪些改动必须更新文档

以下改动不能只改代码：

- 架构边界变化
- 术语变化
- 状态机变化
- 生命周期顺序变化
- 共享 API 规则变化
- 测试规范变化
- AI 必须遵守的规则变化

### 9.2 文档更新原则

- 文档必须服务理解，不服务概念堆叠
- 优先解释“为什么这样做”，其次才是“怎么做”
- 已成为硬约束的规则，不要只写在聊天记录里
- 新人看不懂的文档，不算完成

---

## 10. AI 与提交要求

这一章是执行层，不重复解释总原则，只说必须做什么。

### 10.1 修改前必须做的事

1. 先识别这次改动位于哪一层：实例层、运行时层、成员层、编排层、卸载层、适配层或文档层
2. 先确认谁是唯一真源、谁有写权限、谁只读
3. 先确认这次改动是否影响测试、日志、文档和术语

如果答不出“谁有写权限”，就不应该开始改代码。

### 10.2 禁止做的事

- 绕过 `InstanceCoordinator` / `RuntimeCoordinator` 直接改状态
- 把写权限重新散回聚合对象、池对象、业务对象
- 继续扩大字符串魔法键
- 为了兼容保留已经确认错误的旧边界
- 把复杂性继续往 Spring 反射补丁、JVM 黑盒补丁里堆
- 删除高价值设计注释、踩坑说明、风险提示
- 擅自把“灵核”“灵元”改回旧术语

### 10.3 交付最低要求

只要改动涉及代码，至少同时考虑：

- 代码是否守住边界
- 测试是否覆盖关键语义
- 文档是否同步
- 术语是否统一
- 日志是否仍为英文
- 注释是否仍为中文

---

## 11. 提交前检查清单

### 11.1 架构检查

- 我能明确说出谁有写权限、谁只读、谁编排
- 我没有新增第二份状态真源
- 我没有让对象之间重新互相改状态
- 我没有把成员管理对象做成生命周期总控器
- 我没有扩大字符串魔法键的使用范围

### 11.2 代码检查

- 注释是中文
- 日志是英文
- 术语统一遵循第 3 章
- 高价值风险注释没有被清掉
- 新增复杂逻辑的位置是可解释的

### 11.3 测试检查

- 测试展示名是中文
- 必要的场景分组使用了 `@Nested`
- 关键语义有测试，而不是只有流程测试
- 架构顺序变化时有契约测试或等价覆盖

### 11.4 文档检查

- 改动影响到规则、边界、术语、状态机时，我同步更新了文档
- 新人看到这次改动，仍然能找到解释它的文档

---

## 12. 新人建议阅读顺序

如果你是第一次接触灵珑，建议按这个顺序理解项目：

1. [why.md](why.md)
2. [manifesto.md](manifesto.md)
3. [README.md](README.md)
4. 本手册
5. `LingInstance` / `InstanceCoordinator`
6. `LingRuntime` / `RuntimeCoordinator`
7. `InstancePool`
8. `DefaultLingLifecycleEngine`
9. `LingUnloadCoordinator`
10. 相关测试类

如果你是 AI 助手，建议在修改前至少阅读：

1. 本手册
2. 涉及模块的代码
3. 对应测试
4. 对应架构文档

---

## 13. 最后一条判断标准

判断一个改动值不值得做，最后只问三个问题：

1. 它有没有让边界更清楚？
2. 它有没有让语义更可证明？
3. 它有没有让长期运行更可解释？

如果三个问题都答不上来，这个改动大概率不该进入灵珑。
