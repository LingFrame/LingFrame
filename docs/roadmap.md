# 路线图与愿景

本文档描述 LingFrame 的长期愿景和演进路线。

> 💡 当前已实现的功能请参考 [架构设计](architecture.md)

## 愿景

LingFrame 的最终目标不仅是"插件框架"，而是：

> **JVM 级运行时治理内核（Runtime Governance Kernel）**

具备四个核心能力：

1. **行为可见**（Observability）
2. **行为可控**（Controllability）
3. **行为可审计**（Auditability）
4. **行为可回滚**（Recoverability）

## 终态架构

```
┌────────────────────────────────────┐
│  Dev Experience Layer（开发体验层） │
├────────────────────────────────────┤
│  Governance Runtime（治理运行时）   │
├────────────────────────────────────┤
│  Policy Engine（策略引擎）          │
├────────────────────────────────────┤
│  State & Resource Kernel（状态内核）│
├────────────────────────────────────┤
│  Microkernel Core（微内核）         │
└────────────────────────────────────┘
```

## 演进路线

### Phase 1：核心框架 ✅ 已完成

**目标**：让框架可用

**已实现**：

- ✅ 插件生命周期管理（`PluginManager`, `PluginSlot`）
- ✅ 蓝绿部署 + 引用计数
- ✅ Child-First 类加载隔离
- ✅ Spring 父子上下文隔离
- ✅ 权限治理（`@RequiresPermission` + 智能推导）
- ✅ 服务扩展（`@LingService` + FQSID 路由）
- ✅ 审计日志（`@Auditable` + 异步记录）
- ✅ 开发模式热重载
- ✅ Spring Boot 3 Starter

### Phase 2：能力增强 🔄 进行中

**目标**：形成"灵珑风格"

**计划**：

- ⏳ 从 `plugin.yml` 加载权限配置
- ⏳ 策略统一引擎
- ⏳ 状态句柄化
- ⏳ 完善基础设施插件（缓存、消息）
- ⏳ 单元测试覆盖

### Phase 3：生态完善 ⏳ 计划中

**目标**：完整的插件生态

**计划**：

- 缓存插件（Redis/Caffeine）
- 消息插件（Kafka/RabbitMQ）
- 搜索插件（Elasticsearch）
- 插件市场基础设施
- 完整文档和教程

### Phase 4：平台化 ⏳ 远期规划

**目标**：操作系统级能力

**计划**：

- 字节码级全量治理
- 无侵入可观测性
- 行为沙箱化
- 插件行为回放
- 多租户隔离

## 核心模块终态设计

### 治理运行时

每一次方法调用都会穿过：

```
TraceContext → Permission Gate → Policy Hook → Execute → Audit Tail
```

### 策略引擎

层级策略模型：

```
Global → Tenant → Plugin → Class → Method
```

- 策略可热更新
- 冲突自动裁决

### 状态内核

把"状态"变成一等公民：

```java
StateHandle {
    ownerPluginId
    lifecycle
    isolationLevel
    recoveryPolicy
}
```

### 审计系统

不只是日志，而是：

- 因果链记录
- 行为重构能力
- 可回放（Replayable）

## 灵珑的精神

- 不是追求快，是构建秩序
- 对野蛮增长建立约束
- 这不是短跑项目，是典型的"十年项目"
