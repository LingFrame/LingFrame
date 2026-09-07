# ADR-0001: 机制统一收归内核与策略上层编排（生命周期互斥锁下沉）

- **状态**: Accepted
- **决策日期**: 2026-08-20

---

## 1. 背景与问题 (Context)

在 LingFrame 演进过程中，上层消费者（如 `LingAI` 的 `DeploymentOperations` 以及 `LingFrame Dashboard` 的 `DashboardLingOperations`）为了防止同灵元 ID 的并发热重载（Reload）、灰度提升（Promote Canary）、回滚（Rollback）产生竞态，各自在本地维护了一套私有的并发分段锁字典（如 `ConcurrentHashMap<String, LockWrapper>`）。

这种架构存在显著的设计气味与隐患：
1. **机制重复 (DRY 违背)**：每个上层消费者都需要重复编写一套引用计数、防内存泄漏清理、超时等待的样板锁代码；
2. **锁作用域隔离失效**：如果 Dashboard 和 LingAI 同时对同一个灵元发起操作，由于各自持有不同的锁实例，跨进程/跨消费者的并发互斥完全失效；
3. **单方法入口缺乏防御**：如果直接调用内核的 `lifecycleEngine.deploy` 或 `undeploy`，底层没有并发互斥保护，容易引发状态机错乱。

---

## 2. 决策驱动因素 (Decision Drivers)

- **第一性原理 (First Principles)**：并发安全与生命周期互斥属于底座的**核心机制（Mechanism）**，而不是上层业务的**策略（Strategy）**。
- **KISS 原则**：上层编排者只需要声明“这几步操作需要作为一个原子事务执行”，不需要关心锁的创建、等待与释放。
- **可重入与零死锁 (Reentrancy)**：复合操作（如先 reload 新版本，再 undeploy 旧版本）必须在同一把锁下安全嵌套执行。

---

## 3. 决策内容 (Decision)

1. **机制统一下沉到 `DefaultLingLifecycleEngine` 内核**：
   - 内核统一维护基于 `ConcurrentHashMap<String, LockWrapper>` 的分段 `ReentrantLock`；
   - 采用 `holdCount` 引用计数机制，在锁持有者归零时原子移除条目，彻底杜绝灵元 ID 无界增长导致的内存泄漏；
   - 支持通过 `LifecycleEngineConfig.lifecycleLockTimeoutMs` 配置防死锁超时。

2. **接口提供 `withLifecycleLock` 声明式能力**：
   - 在 `LingLifecycleEngine` 顶层接口提供 `<T> T withLifecycleLock(String lingId, Callable<T> action)` 默认方法；
   - 上层消费者（LingAI / Dashboard）彻底删除本地私有锁，复合操作统一包裹在 `withLifecycleLock` 闭包中执行。

3. **内核单方法入口织入纵深防御**：
   - 内核生命周期单入口（`deployInternal`、`recover`、`undeployWithReport`、`bootstrapLingCoreInstance`）内部全部自动接入 `withLifecycleLock`；
   - 由于底层使用 `ReentrantLock`，被上层 `withLifecycleLock` 包裹调用时天然可重入，无额外阻塞。

---

## 4. 后果与影响 (Consequences)

### 正向收益
- **彻底消除重复代码**：上层适配层（如 LingAI `DeploymentOperations`）精简大量锁样板代码；
- **跨模块统一互斥**：无论流量来自 Dashboard 还是 LingAI，同灵元操作在 JVM 进程内绝对串行化；
- **纵深防御**：裸调 `deploy`/`undeploy` 同样具备并发安全性。

### 限制与注意事项
- 上层在使用 Mockito 对 `LingLifecycleEngine` 进行单测 Mock 时，需配置 `Mockito.CALLS_REAL_METHODS` 以确保接口 `default` 方法体被正确调用执行。
