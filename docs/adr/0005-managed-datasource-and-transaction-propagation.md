# ADR-0005: 灵珑多模数据源引渡与跨灵元双轨制事务一致性治理架构

- **状态**: Accepted
- **决策日期**: 2026-08-29

---

> **修订记录**
> - **v6**：新增灵元侧 JPA 运行期硬边界决策（**决策 D18**，经 `ManagedJpaBoundaryTest` 5 用例全绿实证）：
>   1. **方言强制显式配置**：受管代理元数据 URL 安全脱敏（`jdbc:lingframe:masked`）导致 Hibernate 方言自动检测失败，灵元必须显式配置 `spring.jpa.database-platform`，否则 EMF 启动崩溃；
>   2. **双事务管理器自动抑制**：`JpaBaseConfiguration` 的 `@ConditionalOnMissingBean` 生效，`LingManagedTransactionManager` 成为灵元容器唯一 PTM，无歧义；
>   3. **物理提交权安全降级**：穿透命中时 Hibernate 的 `commit/close/setAutoCommit` 降级为 safe no-op，底层连接生命周期与提交权由根事务统一协调。
>
> - **v5**：对齐 [design v5](../zh-CN/design/managed-datasource-and-transaction-propagation.md)（D17 新增）。本 ADR 决策方向不变，新增一项生命周期策略决策：
>   1. **基础设施灵元只增不减（D17）**：模式 3 存储灵元**本期不提供热卸载**——只允许热挂载，卸载入口禁用；即使闲置也保留（宁可放着不用，也不冒卸载的级联失效与 ClassLoader/驱动回收风险）。`ManagedDataSourceRegistry.unregister` 保留为 API 但基础设施路径不触发；**业务灵元（模式 2）卸载不受影响**，仍走既有四层回收（驱动反注册经既有 `LingUnloadHook`，决策 D14）。
>
> - **v4**：对齐 [design v4](../zh-CN/design/managed-datasource-and-transaction-propagation.md)（D15/D16 新增）。本 ADR 决策方向不变，按生产级评审补两点决策与一处边界声明（细节以 design v4 为准）：
>   1. **TSM 共享启动期自检（D15）**：穿透地基 = 灵核与灵元共享同一份 `TransactionSynchronizationManager`（spring-tx 父委派）。装配期用 `Class.forName` 做 Class 身份比较，不一致 → 启动期 WARN：穿透不激活——把「父委派配置错误的静默失效」提升为「启动期可见」（与 D11 同手法）；
>   2. **穿透总开关与降级路径（D16）**：新增配置键 `lingframe.tx.propagation.enabled`（默认 `true`）。`false` 时 Filter 直接放行、灵元侧不注册 `LingManagedTransactionManager`——应急时「先关穿透，业务退回模式 2 + EventBus 最终一致兜底，再排查」，避免只能改代码排查；
>   3. **D9 接受的残余风险声明**：poisoned-close 缩小了并发访问窗口但未消灭——若 worker 阻塞在不可中断 I/O，join 超时后 close 与 worker 并发访问同一 Connection 仍是未定义行为；文档明确该残余风险为**接受项**，宽限期是概率性缓解而非硬保证。
>
> - **v3**：对齐 [多模数据源与事务一致性设计方案 v3](../zh-CN/design/managed-datasource-and-transaction-propagation.md)。本 ADR 决策方向不变，按生产级评审**补全决策 D7–D14** 的正文表述（D7/D8/D9/D10/D11/D12/D13/D14 已分别落入 §3.2–§3.5 与 §4；细节以 design v3 为准）。三条核心决策摘要如下：
>   1. **跨线程回滚信号合并（D7）**：事务状态 = 资源（连接，向下传递）+ 信号（rollbackOnly，向上回传）。快照为**双向载体**，worker 线程 `restore` 采用**合并语义**（`carrier.rollbackOnly |= worker 期间置位`）而非覆盖——覆盖式 restore 会随状态恢复丢弃 worker 置位信号，造成静默部分提交；
>   2. **执行模式门控（D12）**：事务穿透**仅 NORMAL 模式**激活；SIMULATION / GOVERN_ONLY 直接放行（二者无真实终端执行，压栈的连接无消费者）；
>   3. **卸载驱动反注册挂既有回收通道（D14）**：`DriverManager.deregisterDriver()` 经**既有** `LingUnloadHook`（core.spi，开发手册 §6.11 ①卫生层）执行，不新造钩子通道。
>
> - **v2**：采纳 [design v2](../zh-CN/design/managed-datasource-and-transaction-propagation.md) 的修订——跨线程穿透接线既有 `ThreadLocalPropagator` SPI、事务状态提取 SPI 化（core 零 Spring）、明确事务根模型。**v2 确立 D1–D6 基础决策**（D1 事务根模型 / D2 事务状态提取 SPI 化 / D3 受管数据源独立总线 / D4 跨线程穿透 / D5 防泄漏反向引用解除 / D6 契约与配置同步），对应条款标注见 §2 决策驱动与 §3 决策内容。

---

## 1. 背景与问题 (Context)

在 JVM 微内核与灵珑架构演进中，针对灵元数据源访问与事务一致性，存在三大架构模式：
- **模式 1（基础设施托管模式，Managed DataSource）**：灵核底座静态托管单一物理连接池（启动时配置固定、运行时不可变），灵元 0 配置共享连接，支持本地单机 ACID 强事务回滚。这是工程便利性与架构纯洁性之间的务实权衡（开箱即用推荐态）；
- **模式 2（领域完全自治模式，Database-per-Ling）**：灵元自建独立连接池与私有/异构数据库，依靠微内核 EventBus 实现进程内最终一致性（物理隔离态）；
- **模式 3（基础设施灵元化模式，Storage-Ling）**：灵核 0 存储/0 JDBC 依赖，一个或多个专职存储灵元运行时动态挂载，各自持有独立连接池（可异构），JDBC 驱动随存储灵元 ClassLoader 一同加载和回收，支持运行时数据源拓扑热演化与穿透强事务（极限纯洁扩展态）。

### 历史现状与演进痛点
1. **现有物理实现的局限**：LingFrame 0.4.0 底层仅原生支持“模式 2（独立数据源模式）”。当灵元配置 `spring.datasource.url` 时，`LingDataSourceRegistrar` 在灵元子容器中独立拉起物理连接池。这导致：
   - 连接池严重碎片化与数量膨胀（N 个灵元占用 N 个连接池）；
   - **跨灵元本地 ACID 事务彻底断裂**：各灵元使用不同数据库连接，Spring `@Transactional` 无法跨边界回滚。
2. **纯微内核治理中心的诉求（Zero-Business LingCore）**：
   - 存量改造中（如 RuoYi `notice-v2`），灵元是通过反向 Pinning 灵核既有业务 Service 来借道实现数据复用；
   - 但当灵核彻底剥离业务、蜕变为“纯管理中心（0 业务表、0 业务 Service）”时，该方式彻底失效，业务灵元必须在自身内部编写 Mapper/SQL。
3. **强引用与类泄漏恐惧**：
   - 为确保 ClassLoader 可证 GC 回收，`SpringContainerFactory` 严厉执行“不设父容器”；
   - `LingCoreServiceRegistrarProcessor` 将 `"dataSource"` 与 `"transactionManager"` 列入服务注册排除名单——这是**服务契约语义**（数据源/事务管理器不是业务服务契约，本就不该进 FQSID 目录），并非“粗暴封杀”数据源；真正缺失的是**独立的基础设施引渡总线**：受管数据源没有一条与业务服务目录解耦的供给通道。
4. **线程隔离与 ThreadLocal 的天然冲突**：`ThreadIsolationGovernanceFilter`（`EXECUTION_ISOLATION`）在 NORMAL 模式下把终端执行 `submit` 到每灵元专属线程池，靠 `LingCallContextSnapshot` 搬运调用上下文。任何“同线程 ThreadLocal 传递连接”的假设都会在跨线程边界静默丢失——**任何事务穿透设计必须显式考虑线程边界**。
5. **跨 ClassLoader 事务可见性陷阱（TSM 分叉）**：Spring 的 `TransactionSynchronizationManager` 是静态 ThreadLocal 单例；灵核与灵元若各持一份 Spring 栈，活跃事务状态互不可见。穿透可行的前提是 runtime 注入的 Spring 生态包父委派共享同一份 TSM——这必须作为显式契约，而非巧合。

---

## 2. 决策驱动因素 (Decision Drivers)

- **第一性原理（事务物理现实）**：单机 ACID 强事务的物理前提是调用链必须共享同一个 `java.sql.Connection`。在单进程微内核架构中，强推分布式事务（XA/Saga）违反 KISS 原则。
- **用户体验分层（“推荐 1，保留 2，扩展 3”）**：
  - **推荐 1（开箱即用）**：绝大多数企业用户只需在灵核配置常规连接池，上层所有业务灵元零配置共享连接与本地事务，开发体验与单体无异；
  - **保留 2（异构隔离）**：对使用私有库或异构存储的灵元，由微内核 `EventBus` 建立进程内最终一致性通道；
  - **扩展 3（动态多源）**：灵核保持 0 存储/0 JDBC 依赖，由一个或多个专职存储灵元动态挂载提供异构连接池。与模式 1 的本质区别在于：数据源拓扑可运行时动态演化，JDBC 驱动随存储灵元生灭而非灵核常驻。
- **基于现有资产微创演进（严禁重复造轮子）**：
  - 深度复用现有的 `lingframe-infra-storage`、`LingDataSourceProxy`、`LingConnectionProxy`、`DataSourceWrapperProcessor` 以及 `InvocationPipelineEngine`，不生造冗余类库；
  - 接线 core.spi 中**既有但尚未接线**的 `ThreadLocalPropagator` 契约作为跨线程搬运通道；复用 `EventBus`（含灵元级监听卸载自动清理）作为模式 2 最终一致性的进程内分发通道。
- **模块边界铁律（core 零 Spring；决策 D2）**：`lingframe-core` 不以 Spring 为设计前提（pom 无 Spring 依赖）。事务状态提取必须 SPI 化：core 只面向 `TransactionBindingHook` 等 SPI，Spring 实现（对接 `TransactionSynchronizationManager`）下沉 runtime starter。
- **受管数据源独立总线（不污染服务目录；决策 D3）**：`LingServiceRegistry` 是 FQSID → 方法签名/提供方权重的服务契约目录，无泛型基础设施 Bean 查找能力。受管数据源引渡新建独立总线 `ManagedDataSourceRegistry`（`register / unregister / lookup`），与业务服务目录解耦。
- **单向引用与防泄漏铁律（Zero ClassLoader Leak；决策 D5）**：
  - 恪守“子引用父无害，父引用子必死”的 GC 物理法则，连接池常驻长生命周期端，通过无状态隔离代理单向引渡给灵元子容器；
  - 微内核流水线执行强制 `finally` 护栏，**双端**清洗 `ThreadLocal`（主线程端 `TransactionPropagationFilter` + worker 线程端 `ThreadIsolationGovernanceFilter`）；
  - 基础设施灵元（模式 3）**本期不提供热卸载**（决策 D17：只增不减），`ManagedDataSourceRegistry.unregister()` 在基础设施路径不触发；若未来引入停用/卸载，再经既有通道解除父→子强引用并触发 `DriverManager.deregisterDriver()` 反注册，粉碎 Bootstrap 类加载器引发的泄漏陷阱。

---

## 3. 决策内容 (Decision)

### 3.1 抽象统一数据源提供者 SPI 与独立总线 (`lingframe-api`)
定义中立的基础设施契约（SPI 与总线分离，均归 `com.lingframe.api.storage`）：
```java
package com.lingframe.api.storage;

import javax.sql.DataSource;

/** 数据源供给 SPI：灵核（模式 1）或存储灵元（模式 3）向总线供给受管数据源 */
public interface ManagedDataSourceProvider {
    DataSource getDataSource();
    default String getDataSourceId() { return "default"; }
}

/** 受管数据源独立总线：与 LingServiceRegistry（FQSID 服务契约目录）职责分离 */
public interface ManagedDataSourceRegistry {
    void register(String dataSourceId, ManagedDataSourceProvider provider);
    void unregister(String dataSourceId);      // 基础设施（模式 3）路径本期不触发（D17：只增不减）；保留为运维停用/未来能力预留
    DataSource lookup(String dataSourceId);
}
```

### 3.2 双模供给端落地（模式 1 与模式 3）
1. **模式 1（灵核底座静态托管）**：
   - **不改造 `LingCoreServiceRegistrarProcessor` 的排除名单**——那是服务契约语义（数据源/事务管理器本就不该进 FQSID 目录），保持原样；
   - 由 runtime starter 装配 `ManagedDataSourceRegistry` 灵核级单例：检测灵核存在已由 `DataSourceWrapperProcessor` 包装的 `LingDataSourceProxy` 后，将其包装为 `ManagedDataSourceProvider`，以 `dataSourceId="default"` 注册到独立总线；
   - 数据源在灵核启动时由 `application.yml` 静态配置，运行期间不可变。
2. **模式 3（存储灵元动态外挂）**：
   - 灵核保持 0 存储/0 JDBC 依赖；
   - 可挂载一个或多个 `lingframe-infra-storage` 存储灵元，每个存储灵元各自拉起独立连接池（可连接不同类型的数据库），通过 `DataSourceWrapperProcessor` 包装后，以不同的 `dataSourceId` 向 `ManagedDataSourceRegistry` 注册 `ManagedDataSourceProvider`；
   - 存储灵元支持运行时**热挂载**，JDBC 驱动 jar 随存储灵元 ClassLoader 一同加载；**本期不提供热卸载**（决策 D17：基础设施只增不减），卸载入口禁用，即使闲置也保留——承载型基础设施的卸载级联失效与回收复杂度高于「闲置占用」的成本。**业务灵元（模式 2）卸载不受影响**：其驱动反注册经既有 `LingUnloadHook`（core.spi，开发手册 §6.11 ①卫生层）执行，不新造钩子通道（D14）。

### 3.3 灵元接入端改造 (`LingDataSourceRegistrar`)
重构灵元数据源发现决策树：
- **分支 A（独立库）**：灵元配置了 `spring.datasource.url`，维持现状自建连接池（模式 2，用于私有异构存储）；
- **分支 B（受管共享）**：灵元未配 `url` 但需要数据访问时，从 `ManagedDataSourceRegistry.lookup(dataSourceId)` 获取受管 `LingDataSourceProxy`，以单向 Singleton 注入灵元子容器，标记为 `@Primary` `dataSource`。**坚决不建立 Spring 父子容器！**
  - 目标数据源 ID 通过配置键 `lingframe.ling.datasource-ref` 指定（默认 `"default"`，与 3.1 的 `getDataSourceId()` 一致；配置键统一 `lingframe.*` 前缀，不使用裸魔法键）；
  - 分支 B 同时注册**双路径事务管理器** `LingManagedTransactionManager`（决策 D1 事务根模型 + D13 完整规格，见 design §4.6）：实现 Spring `PlatformTransactionManager`，但不激活 TSM 资源绑定——判根真源 = `getTransaction()` 时刻 `LingTransactionContext` 栈（按 dataSourceId）空与否；**根路径**（栈空，灵元为事务根，如纯灵元发起业务事务）借连接 → `setAutoCommit(false)` → push，commit/rollback 物理执行 + pop + close 归还池；**加入路径**（栈非空，灵核侧入口 Bean 已开根事务）不 bind TSM、不碰连接，非根 commit 前检测 rollbackOnly（置位则抛 `LingTransactionRollbackException`，对齐 Spring `UnexpectedRollbackException` 语义）。`REQUIRES_NEW` 物理不可达，显式降级为加入（REQUIRED）并告警（对应 design **约束 1**），避免「写了事务注解却静默无效」的误解；timeout 不由 TM 实现，由流水线 resilience 治理兜底（见 design §4.5）。

### 3.4 连接复用增强 (`LingDataSourceProxy`)
改造现有的 `LingDataSourceProxy.getConnection()`：
- **身份门控（决策 D8）**：受管代理构造时显式携带 `dataSourceId`，`getConnection()` 用**自身 id** 精确查 `LingTransactionContext` 连接栈——命中则复用穿透物理连接；**模式 2 私有池代理无 `dataSourceId`、永不查栈**（混合链路下绝不误用受管连接，串库路径物理切断）；既有无参构造器保留（默认 null，行为与现状一致），存量灵元零感知；
- 若存在活跃事务连接，返回防早闭、防早提、防篡改 autoCommit 的 `NonCloseableLingConnectionProxy`：`close()` / `commit()` / `setAutoCommit()` 空实现，`rollback()` 仅标记 `LingTransactionContext.setRollbackOnly()`，物理提交/回滚权归根事务发起方；若无事务，维持原样向底层连接池借出连接；
- **审计不降级（决策 D10）**：`NonCloseableLingConnectionProxy` 降级的只是**物理行为**——`checkTransactionPermission` 事务权限门与审计事件（`downstream-*-suppressed`）**全部保留**，no-op 不豁免治理门；并补 `setTransactionIsolation` / `setReadOnly` / `setHoldability` 拦截（根连接属性防篡改，仅记审计不执行）；
- `LingTransactionContext` 按 `dataSourceId` 维护连接栈（`Map<String, Deque<Connection>>`），模式 1 单栈（`"default"`）与模式 3 多栈语义一致，杜绝同一事务内多库写操作连接串用（对应 design **约束 3** 多数据源上下文映射）。

### 3.5 微内核调用流水线事务穿透 (`TransactionPropagationFilter`)
在 `lingframe-core` 的 `InvocationPipelineEngine` 过滤链中引入事务穿透拦截器，**位置为具名常量 `FilterPhase.TRANSACTION_PROPAGATION`（= `ROUTING + 50` = 250，POLICY_PREFILL(240) 与 RESILIENCE(300) 之间，禁止裸写魔法表达式）**——路由确定之后、TCCL 切换之前：
1. **事务状态提取 SPI 化（core 零 Spring；决策 D8 带身份维度）**：core 的 `TransactionPropagationFilter` 只面向 `core.spi.TransactionBindingHook`（`isTransactionActive()` / `getActiveBoundDataSourceIds()` / `getBoundConnection(String dataSourceId)`）；Spring 实现 `SpringTransactionBindingHook`（对接 `TransactionSynchronizationManager.isActualTransactionActive()`，按受管代理实例为 TSM 资源键提取连接；JPA 根无 DataSource 资源键 → 提取 null，D11 边界）放 runtime starter；
2. **调用前**【v3：仅 NORMAL 模式，决策 D12】:经 SPI 判定当前活跃事务后，按 hook 报告的**活跃绑定源集合**（`getActiveBoundDataSourceIds()`，模式 1 恒为 `{"default"}`）逐源提取连接、按 dataSourceId 压入 `LingTransactionContext`；SIMULATION / GOVERN_ONLY 直接放行（D12，二者无真实终端执行，压栈的连接无消费者）；
3. **穿透调用**：目标灵元在自身 ClassLoader 下执行 Mapper/SQL 时，经受管代理按自身 dataSourceId 精确查栈复用同一个物理连接（决策 D8 身份门控，串库断绝）；
4. **跨线程搬运（决策 D4/D7）**：`ThreadIsolationGovernanceFilter`（`EXECUTION_ISOLATION`）接线 core.spi 既有 `ThreadLocalPropagator` 契约——任务提交前 `capture`、worker 线程 `apply`、finally `restore`，把穿透连接随任务搬运到 worker 线程，解决 NORMAL 模式线程池边界导致的连接丢失。搬运由 core 侧 `TransactionContextPropagator`（实现 `ThreadLocalPropagator<TransactionSnapshot>`，内部委托 api 的 `LingTransactionContext` 快照方法）完成，快照类型归 api、契约实现归 core，不产生 api→core 依赖；**快照为双向载体（下行携带连接、上行携带 rollbackOnly 信号），worker `restore` 采用合并语义（`carrier.rollbackOnly |= worker 期间置位`）而非覆盖（D7）**；
5. **回滚信号回传**：调用返回后（worker 信号经快照合并逐层 OR 回主线程后），检查 `LingTransactionContext.isRollbackOnly()`，若为 `true` 则主动抛出 `LingTransactionRollbackException`，确保上游 Spring 事务管理器触发物理回滚；
6. **强清护栏（finally 双端）**：主线程端 `TransactionPropagationFilter` 执行 `popConnection()` + `cleanIfEmpty()`，worker 线程端 `ThreadIsolationGovernanceFilter` 执行 `restoreSnapshot()`，双端擦除彻底防止线程池复用时的 `ThreadLocal` 污染与连接强引用残留；
7. **流水线契约同步（决策 D6）**：新增内置过滤器必须同步 `FilterRegistry.RESERVED_BUILTIN_ORDERS`（追加 `FilterPhase.TRANSACTION_PROPAGATION` 保留位）、`FilterRegistry.assertOrder` 断言链与 `PipelineArchitectureContractTest`，防止 SPI/动态过滤器占用保留位；
8. **超时/放弃执行安全（决策 D9 / 约束 5）**：穿透连接的独占窗口 = 整条跨灵元调用链（含线程池排队与嵌套等待，对应 design **约束 5** 持有时长运营边界）。resilience 超时或调用方放弃 `Future` 后，被放弃的 worker 可能仍在同一物理连接上执行 SQL——JDBC `Connection` 非线程安全，主线程 rollback 与 worker 语句并发执行是未定义行为。处理时序：`cancel(true)`（中断传播级联 `Statement.cancel()`）→ 有界 join（`lingframe.ling.transaction.abandoned-join-timeout`，默认 2s）→ 超宽限期则该连接标记 **poisoned**（跳过 `rollback()` 直接 `close()` 废弃 + ERROR 审计 + `lingframe.tx.connection.poisoned` 指标），根事务其余连接照常回滚；二次 close 幂等，不额外处理；
9. **事务根管理器类型边界（决策 D11 / 约束 4）**：穿透前提 = 根事务管理器为 `DataSourceTransactionManager`（JDBC 资源以受管代理实例为键 bind 进 TSM）；JPA 根（`JpaTransactionManager`）物理连接封装在 EntityManager 内，hook 无法提取 → 穿透不激活，受管灵元 SQL 独立提交（autoCommit 即提交，无一致性保障）。runtime 装配时检测灵核 `PlatformTransactionManager` 类型，非 JDBC 型输出 WARN——把「静默失效」提升为「启动期可见」；
10. **TSM 共享启动期自检（决策 D15）**：穿透地基 = 灵核与灵元共享同一份 `TransactionSynchronizationManager`（spring-tx 父委派）。灵核 starter 装配时用 `Class.forName(TSM, false, 各 ClassLoader)` 做 Class 身份比较，不一致（父委派配置错误、两栈分叉）→ 输出 WARN：穿透不激活，受管灵元 SQL 独立提交——与 D11 同手法，把「父委派配置错误导致的静默失效」提升为「启动期可见」；
11. **穿透总开关与降级路径（决策 D16）**：新增配置键 `lingframe.tx.propagation.enabled`（默认 `true`）。`false` 时：`TransactionPropagationFilter` 直接放行（不 push）、分支 B 不注册 `LingManagedTransactionManager`（灵元仍注入受管数据源可读写，但退回独立连接心智）——提供明确的应急降级路径：「线上出现穿透机制自身引发的疑难时，先关总开关，业务退回模式 2 + EventBus 最终一致兜底，再排查」，避免只能改代码排查。

### 3.6 模式 2（独立数据源）的最终一致性治理轨道 (Eventual Consistency Track)
对于显式配置了 `spring.datasource.url` 的灵元（独立私有库/异构存储），物理上无法参与单机 ACID 事务。LingFrame 为其确立“双轨制”中的**最终一致性轨道**：
1. **事务后事件驱动（Post-Commit EDA）**：灵元在自身独立库事务完成提交后（`TransactionSynchronization.afterCommit`），通过微内核 `EventBus` 广播领域事件；
2. **进程内极速分发（In-Process EventBus）**：单进程内 `EventBus` 事件分发耗时极低，无网络 RPC 抖动与丢包风险。**可靠性边界**：当前为纯内存总线，进程崩溃时未消费事件将丢失；若业务要求 crash-safe 最终一致性（如支付、账务），未来可演进为 Transactional Outbox Pattern；
3. **全链路幂等追踪**：通过 `LingCallContext.getTraceId()` 携带全局唯一幂等流水号，下游灵元据此实现防重插入与幂等处理；
4. **弹性补偿（Saga Fallback）**：结合 `ResilienceGovernanceFilter` 的熔断降级能力，当下游处理失败时回掷补偿事件，上游执行反向冲正。

### 3.7 灵元侧引入 JPA 运行期硬边界（决策 D18）
针对灵元内部引入 `spring-boot-starter-data-jpa` 的场景，经 `ManagedJpaBoundaryTest`（5 用例全绿）实证确立三项硬边界：
1. **方言自动检测失败（必须显式配置方言）**：`LingDatabaseMetaDataProxy` 的 URL 脱敏（`jdbc:lingframe:masked`）导致 Hibernate 拿不到完整的 `DialectResolutionInfo`，启动报 `Access to DialectResolutionInfo cannot be null when 'hibernate.dialect' not set`。灵元必须显式配置 `spring.jpa.database-platform`，否则 `EntityManagerFactory` 启动即失败；
2. **双事务管理器自动抑制（无歧义）**：Spring Boot 的 `JpaBaseConfiguration` 带 `@ConditionalOnMissingBean`。灵元注入 `lingTransactionManager`（`LingManagedTransactionManager`）后，`JpaTransactionManager` 自动被抑制，容器仅保留唯一的 PTM，按类型解析无歧义；
3. **穿透命中时 Hibernate 物理提交权降级**：穿透栈非空时返回 `NonCloseableLingConnectionProxy`，Hibernate 发起的 `setAutoCommit(false)` / `commit()` / `close()` 全部安全降级为 safe no-op，`rollback()` 仅置回滚信号上行，物理提交权由根事务统一协调；栈空时返回普通代理连接，维持独立连接心智。

---

## 4. 后果与影响 (Consequences)

### 正向收益 (Positive)
- **真·进程内微服务强一致性**：业务灵元完全模块化独立开发，但跨灵元调用享受毫秒级、零分布式开销的本地 ACID 事务回滚；
- **连接池集约化**：N 个灵元共享单一连接池，相比模式 2 下各自独占，连接总数显著降低（如 5 灵元各默认 10 连接变为共享池 10-15 连接）；
- **开发门槛极低**：对业务人员而言，心智模型与原生 Spring Boot + MyBatis 100% 吻合；
- **可证 GC 闭环**：在拥有共享连接能力的同时，严格守护了类加载器防泄漏底线——双端 finally 擦除 + 注册中心反注册 + 驱动反注册三道卸载防线齐备；
- **职责分离**：受管数据源独立总线与 `LingServiceRegistry` 服务契约目录解耦，数据源引渡不再污染 FQSID 业务目录，也无需触碰既有排除名单；
- **治理不降级（决策 D10）**：`NonCloseableLingConnectionProxy` 降级的只是物理行为，`checkTransactionPermission` 事务权限门与审计事件全部保留——下游对共享连接的每一次越权尝试（commit / rollback / setAutoCommit / 隔离级别 / readOnly / holdability）都可观测、可审计、可拒绝；代价仅为每次 no-op 仍执行权限检查与审计打点的微小开销。

### 潜在成本与权衡 (Trade-offs)
- **模式 1 物理同构与传播约束**：享受本地 ACID 强事务要求参与灵元共享同一个物理关系数据库实例（逻辑表隔离）；受管模式下灵元注册**双路径** `LingManagedTransactionManager`（决策 D13，完整规格见 design §4.6），不激活 TSM 资源绑定，`REQUIRES_NEW` 物理不可达——**显式降级为加入（REQUIRED）并告警**，而非静默失败（独立提交需走模式 2 + EventBus）；
- **事务根模型约束**：根事务由调用链上第一个 `@Transactional` 边界开启（常见形态为灵核侧入口 Bean，**也可以是灵元**——纯灵元发起的业务事务走 D13 根路径）；下游一律「加入」。开发者必须清楚「灵元内注解在已有根事务时不会开启独立子事务」「`REQUIRES_NEW` 会被降级」这两个语义边界；
- **跨线程穿透实现成本**：NORMAL 模式下终端执行在线程池边界搬运连接与信号（`ThreadLocalPropagator` 接线 + 双向快照 capture/apply/restore 合并语义 + 主线程/worker 双端 finally 擦除），比同线程 ThreadLocal 直传复杂，且要求 `LingTransactionContext` 快照只存活于单次调用窗口，任何遗漏都会造成连接强引用残留或线程池污染；
- **超时与放弃执行的连接安全成本（决策 D9）**：穿透连接独占整条跨灵元调用链，超时/放弃后需要 `cancel(true)` + 有界 join + poisoned 连接废弃的完整时序——比单库事务多一层「被放弃 worker 可能仍在写同一连接」的并发防护，且超时废弃会直接损失池中连接（依赖池重建）。**接受的残余风险**：若 worker 阻塞在不可中断 I/O，join 超时后 close 与 worker 并发访问同一 Connection 仍是未定义行为——宽限期是概率性缓解而非硬保证，文档不得声称「超时后连接已安全」；
- **穿透总开关的权衡（决策 D16）**：总开关 `lingframe.tx.propagation.enabled=false` 提供应急降级自由度（先关穿透、退回模式 2 + EventBus 兜底），但代价是**一致性语义显式降级**——关闭期间受管灵元 SQL 独立提交（autoCommit 即提交），跨灵元原子回滚不再可用。运维必须清楚：总开关是「应急逃生门」而非「常规配置」，开启后需在业务低峰恢复并验证穿透链路（Phase 8 契约自检）；
- **事务根类型边界（决策 D11）**：穿透仅对 `DataSourceTransactionManager` 根生效；JPA 根场景穿透不激活、受管灵元 SQL 独立提交——灵核若混用 JPA 与受管灵元，需明确该场景无跨灵元强一致保障（启动期 WARN 可见）；
- **模式 2 最终一致性研发约束**：放弃了单机事务，业务层必须基于状态机、幂等与 Saga 进行补偿设计，心智负担高于单体模式；纯内存 EventBus 进程崩溃时未消费事件丢失（crash-safe 场景需演进 Transactional Outbox）；
- **模式 3 生命周期策略约束（决策 D17）**：基础设施灵元（存储灵元）**本期不提供热卸载**——只允许热挂载，卸载入口禁用，即使闲置也保留。取舍：以「闲置占用」（连接池/驱动常驻、资源不回收）换取「零级联卸载风险」（依赖它的业务灵元连接池永不失效）与「零 ClassLoader/驱动回收复杂度」；若未来业务确需释放基础设施资源，再评估「依赖反压检查 → 总线 `unregister()` → 停供新连接 → `deregisterDriver()` → 关池」的卸载路径（该路径机制已由 D14 四层回收预留，本期不启用）；
- **灵元侧引入 JPA 的约束与要求（决策 D18）**：官方推荐路径始终为受管代理 + JDBC / MyBatis。若灵元引入 JPA，必须接受「显式配置方言」「穿透命中时 Hibernate 物理提交权降级」的物理硬约束；且长链路下需警惕 Hibernate 实体一级缓存延迟 Flush 与跨 ClassLoader 卸载泄漏的潜在风险。
