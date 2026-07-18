# 运行时双层状态机架构设计

本文档描述灵珑当前围绕 `LingRuntime` / `LingInstance` 收敛出的双层状态机架构。

它重点回答的是：**为什么要这样拆、每一层到底拥有什么、哪些架构约束不能退化**。

如果你想看更偏实践的“怎么读代码、怎么调试、怎么扩展”，请直接读 [运行时双层状态机技术指南](runtime-dual-state-machine-guide.md)。

它不是一套“为了状态机而状态机”的设计，而是为了解决单 JVM、单进程、多版本并存场景下最容易失控的三个问题：

1. 单实例生命周期要可预测，不能被外部任意改写
2. 多实例聚合后的运行时状态要有唯一真源，不能对象之间互相写状态
3. 生命周期编排、状态写入、池成员变更必须分层，否则越改越乱

## 一句话理解

把这套模型看成两层：

- **实例层**：描述“某一个版本的实例现在到底处于什么事实状态”
- **运行时层**：描述“这个灵元作为一个整体现在对外呈现什么宏观状态”

两层之间不直接互相写状态，只通过事件联动。

## 为什么必须做成两层

如果只有一层状态机，会出现三个架构问题：

1. 单实例事实和运行时整体意图会被混在一起，状态语义会越来越脏
2. 生命周期编排代码为了“图省事”会直接改对象状态，最终谁都能写
3. 蓝绿部署、热重载、优雅卸载时，会同时出现“新版本启动中、旧版本排空中、整体仍可服务”的复合场景，单层模型难以表达

双层之后，语义可以拆开：

- **InstanceStatus** 关注单实例真实生命周期
- **RuntimeStatus** 关注整体运行时的宏观健康度与运维意图

## 核心角色

| 角色 | 所在层 | 职责 | 是否拥有状态写权限 |
| --- | --- | --- | --- |
| `LingInstance` | 实例层 | 持有单实例运行实体与实例级 FSM 载体 | 否，对外不开放 |
| `InstanceCoordinator` | 实例层 | 单实例状态唯一正式写入口，发布实例状态事件 | 是 |
| `InstancePool` | 灵核成员层 | 管理活跃实例、默认实例、死亡队列 | 否，只管成员关系 |
| `LingRuntime` | 运行时聚合层 | 持有配置、统计和实例池，对外暴露运行时只读视图 | 否 |
| `RuntimeCoordinator` | 运行时层 | 持有 `RuntimeStatus` FSM，聚合实例快照并发布运行时事件 | 是 |
| `DefaultLingLifecycleEngine` | 编排层 | 把部署/卸载意图翻译成阶段化动作，并驱动部署/卸载顺序 | 否，编排而不直接改状态 |
| `LingUnloadCoordinator` | 卸载清理层 | 回收管道资源、调用卸载钩子清理并执行泄漏检测 | 否 |

## 两层状态机分别管什么

### 实例层：`InstanceStatus`

实例层表达单个版本的真实生命周期事实。

```text
CREATED -> LOADING -> STARTING -> READY -> STOPPING -> DEAD
   \          \           \          \          \
    +--------> ERROR -----+----------+----------+
```

语义重点：

- `CREATED`：对象已构造，但未开始加载
- `LOADING`：字节码校验、元数据准备、部署前置阶段
- `STARTING`：容器正在启动
- `READY`：实例可以接受流量
- `STOPPING`：实例已停止接流量，正在优雅排空
- `DEAD`：实例已彻底销毁
- `ERROR`：异常态，允许收敛到 `STOPPING` 或 `DEAD`

实例层只回答一个问题：

> 这个具体实例现在真实处于什么生命周期阶段？

### 运行时层：`RuntimeStatus`

运行时层表达整个灵元对外呈现的宏观状态。

```text
INACTIVE <-> ACTIVE <-> DEGRADED
    |          |
    +--------> STOPPING -> REMOVED
```

语义重点：

- `INACTIVE`：已注册，但没有可服务实例
- `ACTIVE`：整体可服务
- `DEGRADED`：整体可服务，但已降级
- `STOPPING`：运维主动要求进入关闭流程
- `REMOVED`：已完全移除

这里需要明确一个事实：

`RuntimeStatus` 当前同时承载了两类语义：

1. **事实状态**：`INACTIVE / ACTIVE / DEGRADED`
2. **运维意图**：`STOPPING / REMOVED`

这也是为什么 `STOPPING` 进入后会压制后续聚合评估，不允许被实例层“拉回去”。

## 为什么 `LingRuntime` 不再持有运行时状态机

这是本次架构收敛的关键。

如果 `LingRuntime` 自己持有 runtime FSM，就会天然诱导两种错误：

1. 生命周期编排代码直接拿 `LingRuntime` 改状态
2. `RuntimeCoordinator` 和 `LingRuntime` 各维护一份状态，真源分裂

现在的规则是：

- runtime FSM 只存在于 `RuntimeCoordinator`
- `LingRuntime` 只通过 `currentStatus()` 读取
- 外部要改 `RuntimeStatus`，只能走 `RuntimeCoordinator`

这让运行时层重新获得了“唯一真源”。

## 为什么 `LingInstance` 里仍然保留状态机

这也是最容易误解的一点。

`LingInstance` 里保留实例级 FSM，不等于它对外重新开放了状态写权限。

保留它的原因只有三个：

1. 单实例生命周期本身就需要一个原子一致性的承载体
2. `InstanceCoordinator` 需要基于 CAS 驱动状态跃迁
3. 状态机跟随实例对象存在，最符合对象边界

但与此同时，外部规则是硬性的：

- 不能暴露 `StateMachine`
- 不能暴露 `markReady()`、`destroy()` 之类的公开改状态方法
- 只能由 `InstanceCoordinator` 通过包内入口驱动状态变化

所以现在的结构是：

- **状态机在对象内部**
- **状态写权限在协调器手里**

这不是矛盾，而是职责拆分。

## 事件联动，而不是对象互写

双层状态机的关键联动链路如下：

```text
InstanceCoordinator
  -> drive InstanceStatus
  -> publish InstanceStateChangedEvent

RuntimeCoordinator
  -> subscribe instance events
  -> update snapshots[lingId][version]
  -> reevaluate RuntimeStatus
  -> publish RuntimeStateChangedEvent

LingRuntime
  -> subscribe runtime events
  -> tighten LingCore-side runtime behavior when STOPPING / REMOVED
```

这里必须强调：

- `InstanceCoordinator` 不直接去写 `RuntimeStatus`
- `RuntimeCoordinator` 不直接去改 `LingInstance`
- `LingRuntime` 不反向持有 runtime FSM

它们通过事件形成单向联动链，而不是相互侵入。

## 快照为什么重要

`RuntimeCoordinator` 不直接遍历对象图来推导运行时状态，而是维护一份实例状态快照：

```text
snapshots[lingId][version] = InstanceStatus
```

这样做有四个好处：

1. 聚合计算只依赖事实快照，不依赖复杂对象结构
2. 实例层和运行时层可以通过事件边界解耦
3. 并发下只要事件最终到达，运行时层就能重新收敛
4. `STOPPING -> REMOVED` 这种“等实例全部消失再完成”的逻辑更清晰

## 编排层与状态层的边界

### 编排层做什么

- `DefaultLingLifecycleEngine` 负责把部署/卸载意图拆成阶段
- `DefaultLingLifecycleEngine` 负责实例启动、入池、退役和卸载的整体顺序
- `LingUnloadCoordinator` 负责卸载后的资源清理与泄漏检测

### 编排层不做什么

- 不直接改 `LingRuntime` 状态
- 不直接暴露 `LingInstance` 状态机
- 不跳过 coordinator 自己发布状态事件

一句话概括：

> 编排层决定顺序，协调器决定状态。

## 典型链路的架构视角

### 首次部署

```text
register runtime
-> prepare instance (CREATED -> LOADING)
-> start instance (LOADING -> STARTING)
-> add to instance pool
-> mark ready (STARTING -> READY)
-> runtime snapshots sees READY
-> runtime reevaluate (INACTIVE -> ACTIVE)
```

设计目的：

- 先保证运行时聚合器已注册
- 再发布实例事件
- `READY` 事件出现时，实例成员关系已经可以被 runtime 侧看见

### 热重载 / 多版本切换

```text
old default = v1
deploy v2
-> v2 reaches READY
-> v2 becomes default
-> v1 moves to dying queue
-> v1 drains active requests
-> v1 tearDown -> DEAD
```

设计重点：

- 新版本先启动成功，再切默认
- 旧版本不立即消失，而是进入 `dyingQueue`
- 优雅排空由实例层状态和引用计数共同保证

### 卸载

```text
runtime shutdown
-> RuntimeStatus enters STOPPING
-> instance pool stops accepting new instances
-> each instance tearDown
-> snapshots become empty
-> RuntimeStatus goes STOPPING -> REMOVED
-> purge runtime
```

设计重点：

- `STOPPING` 是运行时层的意图态
- `REMOVED` 必须在实例层事实已经全部清空后才能进入

## 当前的硬约束

以下规则建议直接视为架构红线：

1. 不允许在业务代码或普通灵核集成代码中直接操作状态机
2. 不允许 `LingRuntime` 持有第二份 runtime FSM
3. 不允许把 `InstancePool` 写成“生命周期管理器”
4. 不允许在实例层和运行时层之间相互直接写状态
5. 不允许为了方便重新暴露兼容型状态修改 API

## 当前仍然存在的抽象现实

虽然本轮已经完成了较大收敛，但仍要清楚两个现实：

### 第一，`RuntimeStatus` 仍然混合了事实与意图

这是当前实现里的有意识权衡，不是 bug。

好处：

- 简化实现
- 让运行时关闭流程足够稳定

代价：

- `STOPPING` 语义和 `ACTIVE / DEGRADED` 并不完全同类
- 后续如果治理模型继续细化，可能需要把“事实状态”和“运维命令状态”拆开

### 第二，实例池与生命周期编排仍存在顺序耦合

这是灵核侧不可避免的现实，因为“入池”“切默认”“退役旧版本”“销毁资源”本来就有顺序要求。

当前做法不是消除顺序，而是把顺序集中在编排层，并把状态写权限收敛到协调器。

## 架构收益

本轮收敛完成后，这套模型带来的核心收益是：

1. **状态真源唯一**：运行时层和实例层都只有一个正式写入口
2. **联动方向清晰**：实例事实上行，运行时聚合下结论
3. **编排代码可读**：阶段顺序和状态写入不再混杂
4. **多版本治理稳定**：蓝绿、热重载、卸载更容易推理
5. **后续演进有抓手**：如果将来拆“事实态 / 意图态”，已有边界可以承接

如果你接下来要顺着代码路径继续读、改、排障，直接去 [运行时双层状态机技术指南](runtime-dual-state-machine-guide.md)。
