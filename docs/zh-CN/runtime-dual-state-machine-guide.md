# 运行时双层状态机技术指导

本文档面向第一次接触这套设计的人，重点不是讲“理念有多好”，而是帮助你真正读懂、调试、扩展这套架构。

## 先建立一个最小心智模型

不要一上来就试图把所有类背下来。

你只需要先记住三句话：

1. **实例状态** 由 `InstanceCoordinator` 写
2. **运行时状态** 由 `RuntimeCoordinator` 写
3. **生命周期编排** 由 engine / manager 组织顺序，但不直接掌握状态真源

如果你能始终守住这三句话，后面再读代码就不会乱。

## 五个最关键的对象

### `LingInstance`

它代表某个版本的真实运行实体。

你可以把它想成：

- 一个容器
- 一组定义信息
- 一个内部实例状态机
- 一个活动请求计数器

它负责“承载事实”，但不负责“对外暴露状态修改”。

### `InstanceCoordinator`

它是实例状态唯一正式写入口。

常见入口：

- `prepare()`
- `start()`
- `markReady()`
- `stop()`
- `error()`
- `tearDown()`

如果你在别处看到有人想直接给 `LingInstance` 改状态，那通常就是越界。

### `InstancePool`

它只负责池成员关系：

- 当前哪些实例活着
- 哪个是默认实例
- 哪些实例进入了 `dyingQueue`

它不是状态机本身，也不是生命周期总控器。

### `LingRuntime`

它是运行时宿主，不是运行时状态机所有者。

它负责：

- 配置
- 统计
- 实例池
- 对外暴露只读状态视图

它不负责：

- 直接写 `RuntimeStatus`
- 驱动实例状态机
- 编排完整部署卸载流程

### `RuntimeCoordinator`

它是 `RuntimeStatus` 的唯一拥有者。

它监听实例层事件，维护快照，聚合出运行时状态。

如果你只看一个类来理解“双层状态机是怎么联动的”，优先看它。

## 如何阅读部署链路

建议直接按下面顺序跟：

1. `DefaultLingLifecycleEngine.deploy()`
2. `ensureRuntimeForDeployment()`
3. `createDeploymentInstance()`
4. `driveInstanceToLoading()`
5. `startPreparedInstance()`
6. `publishReadyInstance()`

阅读时重点看三件事：

1. 谁在编排顺序
2. 谁在写实例状态
3. 谁在最终决定运行时状态

你会看到：

- engine 负责阶段顺序
- `InstanceCoordinator` 负责实例状态
- `RuntimeCoordinator` 负责运行时状态

这就是当前架构的主干。

## 如何阅读卸载链路

建议按下面顺序跟：

1. `DefaultLingLifecycleEngine.undeploy()`
2. `enterRuntimeStopping()`
3. `drainInstances()`
4. `unloadSingleInstance()`
5. `InstanceCoordinator.tearDown()`
6. `RuntimeCoordinator.onInstanceStateChanged()` / `onInstanceDestroyed()`
7. `tryFinishShutdown()`

阅读时重点观察：

- 为什么先把 runtime 推进到 `STOPPING`
- 为什么实例销毁后还要发事件
- 为什么运行时不是在 `undeploy()` 里直接写成 `REMOVED`

原因很简单：

`REMOVED` 必须由“所有实例事实已清空”来支撑，而不是由编排层拍脑袋宣布。

## 为什么要先入池再标记 READY

当前实现里，`publishReadyInstance()` 的顺序是：

1. `instancePool.addInstance(instance, isDefault)`
2. `instanceCoordinator.markReady(instance)`

这个顺序不是偶然。

这样做可以保证：

- 一旦 `READY` 事件把 runtime 聚合成 `ACTIVE`
- runtime 侧的成员视图里已经能看到这个实例

否则会出现一种短暂但很恶心的不一致：

- 运行时状态已经是 `ACTIVE`
- 但池里还没有这个实例

这种瞬时割裂在治理框架里非常危险。

## 为什么 `LingRuntime` 不持有状态机后，架构反而更清晰

很多人第一反应是：

“对象有状态，不就应该把状态机放对象里吗？”

这句话只对了一半。

对实例层来说，状态机跟着 `LingInstance` 走是合理的，因为它描述的是这个对象自己的生命周期事实。

但对运行时层来说，`LingRuntime` 不是单纯的“状态对象”，它还是宿主、统计容器、实例池容器。如果把 runtime FSM 也塞进去，编排层就很容易直接拿它改状态，最后又回到“谁都能写”的老路。

所以当前设计是：

- `LingInstance` 内部保留 FSM，但不开放写权限
- `LingRuntime` 完全不保留 runtime FSM，只读 `RuntimeCoordinator`

这两个选择连在一起，才构成完整边界。

## 常见误解

### 误解一：双层状态机就是两个完全独立的状态机

不对。

它们不是互不相关，而是：

- **状态真源独立**
- **联动链路存在**

也就是“各自独立拥有状态”，但“通过事件联动收敛”。

### 误解二：`InstancePool` 可以顺手处理完整生命周期

不对。

`InstancePool` 只能做池成员调整，不能自己演化成部署卸载总控。

一旦它开始掌管完整生命周期，状态写入、成员关系、资源回收就会重新揉成一团。

### 误解三：`RuntimeCoordinator` 直接扫描对象图更简单

表面上简单，长期看更糟。

因为一旦直接扫对象图：

- 运行时层会强耦合实例对象结构
- 并发下更难保证一致性
- 状态联动的边界会重新消失

快照虽然多了一层，但它把边界保住了。

### 误解四：既然 `RuntimeStatus` 混了事实态和意图态，就应该立刻继续抽象

不一定。

当前这一步收敛的目标是先把边界稳住，而不是把抽象做得无限漂亮。

只要你已经明确知道：

- `STOPPING` 本质上是运维意图态
- 它会压制实例层的向上聚合

那这套实现就是可解释、可维护的。

后续要不要继续拆“事实态 / 命令态”，要看真实复杂度是否配得上抽象成本。

## 扩展时怎么做，才不容易把架构搞坏

### 场景一：新增一个实例状态

先问自己三个问题：

1. 这是单实例生命周期事实，还是路由策略问题
2. 它是否真的需要成为 FSM 状态，而不是一个附加属性
3. 它加入后是否仍能保持单向、无环、可收敛

如果它只是“是否参与流量”之类的策略开关，通常不应该塞进 `InstanceStatus`。

### 场景二：新增一个运行时状态

先问自己两个问题：

1. 这是运行时整体对外呈现的宏观状态吗
2. 它是事实态，还是运维命令态

如果这两类语义已经明显打架，再考虑把 `RuntimeStatus` 继续拆层；否则不要为了抽象而抽象。

### 场景三：增加新的部署阶段

优先改编排层：

- `DefaultLingLifecycleEngine`
- `LingLifecycleManager`

不要先去改 coordinator。

因为阶段扩展大多数是“顺序扩展”，不是“状态所有权变化”。

### 场景四：增加新的运行时治理联动

优先想清楚它应该挂在哪一层：

- 实例事实变化，就挂实例事件链
- 运行时宏观变化，就挂运行时事件链
- 池成员关系变化，就挂宿主层

不要跨层偷写状态。

## 调试时怎么查

### 看部署异常

优先看：

1. `DefaultLingLifecycleEngine.deployInternal()`
2. `InstanceCoordinator.prepare()/start()/markReady()`
3. `RuntimeCoordinator.onInstanceStateChanged()`

如果实例已经 `READY`，但 runtime 还是 `INACTIVE`，第一怀疑点应该是：

- 事件没发出来
- 快照没更新
- 聚合策略没把当前快照评估成 `ACTIVE`

### 看卸载卡住

优先看：

1. `InstancePool.dyingQueue`
2. `LingInstance.getActiveRequestCount()`
3. `InstanceCoordinator.tearDown()`
4. `RuntimeCoordinator.tryFinishShutdown()`

典型原因通常是：

- 实例还没空闲，排空没完成
- teardown 没走到 `DEAD`
- 快照还残留版本项，所以 runtime 不能进入 `REMOVED`

### 看状态回跳

如果 runtime 进入 `STOPPING` 后又被拉回 `ACTIVE`，那通常说明有人绕过了当前约束，直接写了不该写的状态，或者改坏了 `RuntimeCoordinator.reevaluate()` 对 `STOPPING` 的压制逻辑。

## 代码评审时重点看什么

看到任何状态相关改动时，建议直接拿下面清单过一遍：

1. 是否新增了绕过 coordinator 的状态写入口
2. 是否让 `LingRuntime` 重新持有 runtime FSM
3. 是否让 `LingInstance` 重新暴露原始状态机
4. 是否让 `InstancePool` 承担了超出成员关系的职责
5. 是否让编排层直接改运行时状态而不是通过 coordinator
6. 是否破坏了事件联动链
7. 是否把“事实状态”和“运维意图”进一步混乱化

只要其中任意一条答案是“是”，就要谨慎。

## 给新人的推荐阅读路径

如果你完全不懂这套设计，按下面顺序读最稳：

1. 本文档
2. `InstanceStatus`
3. `RuntimeStatus`
4. `LingInstance`
5. `InstanceCoordinator`
6. `RuntimeCoordinator`
7. `InstancePool`
8. `LingRuntime`
9. `LingLifecycleManager`
10. `DefaultLingLifecycleEngine`
11. [运行时双层状态机架构设计](runtime-dual-state-machine-architecture.md)

## 最后一句建议

理解这套架构时，不要反复问“为什么不把所有事都放进一个对象里”。

真正该问的是：

> 这段代码到底是在编排顺序、承载事实、维护成员关系，还是决定宏观状态？

一旦这个问题问清楚，类的职责边界自然就清楚了。
