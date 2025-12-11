# 架构实现指南

本文档描述 LingFrame 核心机制的**实际代码实现**，供开发时参考。

## 服务扩展机制

### @LingService 注解（核心扩展点）

插件通过 `@LingService` 暴露服务，这是 LingFrame 的**主要扩展点机制**：

```java
@LingService(id = "query_user", desc = "查询用户", timeout = 3000)
public User queryUser(String userId) { ... }
```

**工作流程**：

1. `SpringPluginContainer.start()` 启动时调用 `scanAndRegisterLingServices()`
2. 扫描所有 Bean 的方法，查找 `@LingService` 注解
3. 组装 FQSID：`pluginId:shortId`（如 `user-plugin:query_user`）
4. 注册到 `PluginManager.protocolServiceRegistry` 路由表
5. 同时注册到 `PluginSlot.serviceMethodCache` 执行缓存

**调用方式**：

```java
// 通过 PluginContext.invoke() 调用
Optional<User> user = context.invoke("user-plugin:query_user", userId);
```

### 服务发现（按接口类型）

```java
// 通过 PluginContext.getService() 获取
Optional<UserService> service = context.getService(UserService.class);
```

**实现**：`PluginManager.getService()` 遍历所有插件槽位查找实现。

## 权限治理机制

### 权限检查流程

1. **显式声明**：优先使用 `@RequiresPermission` 注解
2. **智能推导**：`GovernanceStrategy.inferPermission()` 基于方法名前缀推导
3. **开发模式兜底**：`LingFrameConfig.isDevMode()` 为 true 时仅警告不报错

### 方法名前缀映射

| 前缀                                                                             | AccessType |
| -------------------------------------------------------------------------------- | ---------- |
| `get`, `find`, `query`, `list`, `select`, `count`, `check`, `is`, `has`          | READ       |
| `create`, `save`, `insert`, `update`, `modify`, `delete`, `remove`, `add`, `set` | WRITE      |
| 其他                                                                             | EXECUTE    |

### 权限检查位置

- **SmartServiceProxy.invoke()**：服务代理层拦截（`getService()` 获取的代理）
- **CorePluginContext.invoke()**：协议调用层拦截（FQSID 调用）
- **LingStatementProxy/LingPreparedStatementProxy**：SQL 执行层拦截（基础设施插件）

## 插件生命周期

### 安装流程

```
install(pluginId, version, jarFile)
    ↓
createPluginClassLoader(file)  // Child-First 类加载器
    ↓
containerFactory.create()      // SPI 创建容器
    ↓
slot.upgrade(instance, context) // 蓝绿部署
    ↓
container.start(context)       // 启动 Spring 子上下文
    ↓
scanAndRegisterLingServices()  // 注册 @LingService
    ↓
plugin.onStart(context)        // 触发生命周期回调
```

### 蓝绿部署（PluginSlot.upgrade）

1. 背压保护：死亡队列超过 3 个时拒绝发布
2. 启动新版本容器
3. 原子切换 `activeInstance` 引用
4. 旧版本进入 `dyingInstances` 死亡队列
5. 定时任务检查引用计数，归零后销毁

### 热重载（开发模式）

```
HotSwapWatcher.register(pluginId, classesDir)
    ↓
WatchService 监听文件变化
    ↓
scheduleReload() 防抖 500ms
    ↓
PluginManager.reload(pluginId)
    ↓
doInstall() 重新安装
```

## 类加载隔离

### PluginClassLoader 策略

1. **白名单强制委派**：`com.lingframe.api.*`、JDK 类、`org.slf4j.*`
2. **Child-First**：优先加载插件内部类
3. **兜底委派**：自己没有再找父加载器
4. **资源隔离**：`getResource()` 也是 Child-First

### TCCL 劫持

所有跨插件调用都会：

```java
ClassLoader original = Thread.currentThread().getContextClassLoader();
Thread.currentThread().setContextClassLoader(pluginClassLoader);
try {
    // 执行调用
} finally {
    Thread.currentThread().setContextClassLoader(original);
}
```

## 审计机制

### 审计触发条件

1. **显式声明**：方法标注 `@Auditable`
2. **智能推导**：写操作（WRITE）或执行操作（EXECUTE）自动审计

### 审计记录

`AuditManager.asyncRecord()` 异步记录：

- TraceId（链路追踪）
- CallerPluginId（调用方）
- Action（操作）
- Resource（资源）
- Cost（耗时）
- Result（结果）

## 事件系统

### 发布事件

```java
context.publishEvent(new MyEvent());
```

### 监听事件

```java
public class MyListener implements LingEventListener<MyEvent> {
    @Override
    public void onEvent(MyEvent event) { ... }
}
// 需要通过 EventBus.subscribe() 注册
```

## 关键类职责

| 类                      | 职责                                |
| ----------------------- | ----------------------------------- |
| `PluginManager`         | 插件安装/卸载/服务路由/全局管控     |
| `PluginSlot`            | 单个插件的蓝绿部署和版本管理        |
| `PluginInstance`        | 插件实例 + 引用计数器               |
| `SmartServiceProxy`     | 动态代理：路由 + TCCL + 权限 + 审计 |
| `CorePluginContext`     | 插件上下文实现，桥接 Core 能力      |
| `SpringPluginContainer` | Spring 子上下文管理                 |
| `PluginClassLoader`     | Child-First 类加载器                |
| `GovernanceStrategy`    | 权限/审计智能推导                   |
