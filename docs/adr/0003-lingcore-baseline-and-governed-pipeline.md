# ADR-0003: 灵核永久基线与灵元动态生命周期的非对称统一管道路由

- **状态**: Accepted
- **决策日期**: 2026-08-20

---

## 1. 背景与问题 (Context)

在灵珑架构中，存在两种本质不同的实体：
1. **灵元 (Ling Instance)**：动态化单元，支持热加载、多版本共存、热重载、优雅排空与彻底卸载，由 `RuntimeCoordinator` 和 `InstanceCoordinator` 维护复杂状态机。
2. **灵核 (Ling Core, lingcore-app)**：寄宿灵核 Spring Boot ApplicationContext 的永久基线，作为共享 API 的生产者与全局基础设施。灵核不能被热卸载，不应该参与动态生命周期的复杂状态跃迁。

如果将灵核生硬包装为 `LingRuntime`，会导致：
- 灵核混入动态状态机，存在被误调用 `undeploy` 的风险；
- 如果灵核独立走一套调用通道，又会导致上层对灵核与灵元的调用逻辑分叉，破坏统一的服务治理管道（Pipeline）。

---

## 2. 决策驱动因素 (Decision Drivers)

- **类型派生能力 (Capabilities as Derived Attributes of Types)**：通过类型系统本身区分能力的边界，而不是通过打布尔标记。
- **治理统一性 (Unified Governance)**：无论请求目标是灵核还是动态灵元，鉴权、路由、超时、拦截、度量监控必须走同一条 `InvocationPipelineEngine`。

---

## 3. 决策内容 (Decision)

1. **引入 `RoutableTarget` 窄接口与 `LingCoreRoutableTarget`**：
   - 灵核被建模为 `LingCoreRoutableTarget`，直接实现 `RoutableTarget` 接口，并注册到 `LingRepository`；
   - 灵核不是 `LingRuntime`，因此 `lingRepository.getRuntime("lingcore-app")` 自然返回 `null`。

2. **卸载链路严格类型守卫**：
   - 内核 `undeploy` 路径判断：如果目标不是 `LingRuntime`（即为灵核），直接拦截并拒绝卸载；
   - 灵核不进入 `RuntimeCoordinator` 的状态机集合，从类型层面杜绝了灵核被误关的可能。

3. **管道路由层统一抽象**：
   - `InvocationPipelineEngine` 统一从 `LingRepository` 查询 `RoutableTarget`，不管是灵核还是灵元，统一进入治理链路执行。

---

## 4. 后果与影响 (Consequences)

### 正向收益
- **兼顾治理统一与绝对安全**：灵核既享受了 Pipeline 的全量治理能力，又获得了永久存活的不可卸载铁律；
- **类型安全**：消除脆弱的 `if ("lingcore-app".equals(id))` 硬编码判断，利用面向对象多态与类型守卫解决问题。
