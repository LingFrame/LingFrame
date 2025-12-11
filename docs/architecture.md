# 架构设计

本文档介绍 LingFrame 的核心架构设计和实现原理。

## 设计理念

LingFrame 借鉴操作系统的设计思想：

- **微内核**：Core 只负责调度和仲裁，不包含业务逻辑
- **零信任**：业务插件不能直接访问基础设施，必须经过 Core 代理
- **能力隔离**：每个插件在独立的类加载器和 Spring 上下文中运行

## 三层架构

```
┌─────────────────────────────────────────────────────────────┐
│                      Host Application                        │
│                                                              │
│  ┌───────────────────────────────────────────────────────┐  │
│  │                 Core（仲裁核心）                        │  │
│  │                                                        │  │
│  │   PluginManager · PermissionService · EventBus        │  │
│  │   AuditManager · TraceContext · GovernanceStrategy    │  │
│  │                                                        │  │
│  │   职责：生命周期管理 · 权限治理 · 能力调度 · 上下文隔离  │  │
│  └────────────────────────┬──────────────────────────────┘  │
│                           │                                  │
│         ┌─────────────────┼─────────────────┐               │
│         ▼                 ▼                 ▼               │
│  ┌─────────────┐   ┌─────────────┐   ┌─────────────┐       │
│  │  Storage    │   │   Cache     │   │  Message    │       │
│  │  Plugin     │   │   Plugin    │   │  Plugin     │       │
│  │             │   │             │   │             │       │
│  │ 基础设施层   │   │ 基础设施层   │   │ 基础设施层   │       │
│  └──────┬──────┘   └──────┬──────┘   └──────┬──────┘       │
│         │                 │                 │               │
│         └─────────────────┼─────────────────┘               │
│                           │                                  │
│         ┌─────────────────┼─────────────────┐               │
│         ▼                 ▼                 ▼               │
│  ┌─────────────┐   ┌─────────────┐   ┌─────────────┐       │
│  │   User      │   │   Order     │   │  Payment    │       │
│  │  Plugin     │   │   Plugin    │   │  Plugin     │       │
│  │             │   │             │   │             │       │
│  │  业务插件层  │   │  业务插件层  │   │  业务插件层  │       │
│  └─────────────┘   └─────────────┘   └─────────────┘       │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

### 第一层：Core（仲裁核心）

**模块**：`lingframe-core`

**职责**：

- 插件生命周期管理（安装、卸载、热重载）
- 权限治理（检查、授权、审计）
- 服务路由（FQSID 路由表）
- 上下文隔离（类加载器、Spring 上下文）

**关键原则**：

- Core 是唯一仲裁者
- 不提供业务能力，只负责调度和控制
- 所有跨插件调用必须经过 Core

**核心组件**：

| 组件                 | 职责                   |
| -------------------- | ---------------------- |
| `PluginManager`      | 插件安装/卸载/服务路由 |
| `PluginSlot`         | 蓝绿部署和版本管理     |
| `PermissionService`  | 权限检查和授权         |
| `AuditManager`       | 审计日志记录           |
| `EventBus`           | 事件发布订阅           |
| `GovernanceStrategy` | 权限/审计智能推导      |

### 第二层：Infrastructure Plugins（基础设施层）

**模块**：`lingframe-plugins-infra/*`

**职责**：

- 封装底层能力（数据库、缓存、消息队列）
- 细粒度权限拦截
- 审计上报

**已实现**：

| 插件                       | 说明                       | 能力标识      |
| -------------------------- | -------------------------- | ------------- |
| `lingframe-plugin-storage` | 数据库访问，SQL 级权限控制 | `storage:sql` |
| `lingframe-plugin-cache`   | 缓存访问（待实现）         | `cache:redis` |

**工作原理**：

基础设施插件通过**代理链**拦截底层调用：

```
业务插件调用 userRepository.findById()
    │
    ▼ (透明，通过 MyBatis/JPA)
┌─────────────────────────────────────┐
│ Storage Plugin (基础设施层)          │
│                                      │
│ LingDataSourceProxy                  │
│   └→ LingConnectionProxy             │
│       ├→ LingStatementProxy          │  ← 普通 Statement
│       └→ LingPreparedStatementProxy  │  ← PreparedStatement
│                                      │
│ 拦截点：execute/executeQuery/Update  │
│ 1. PluginContextHolder.get() 获取调用方
│ 2. 解析 SQL 类型 (SELECT/INSERT...)  │
│ 3. permissionService.isAllowed() 鉴权│
│ 4. permissionService.audit() 审计    │
└─────────────────────────────────────┘
    │
    ▼ (权限查询)
┌─────────────────────────────────────┐
│ Core                                 │
│ DefaultPermissionService.isAllowed() │
└─────────────────────────────────────┘
```

> 详细开发指南见 [基础设施插件开发](infrastructure-plugins.md)

### 第三层：Business Plugins（业务层）

**模块**：用户开发的插件

**职责**：

- 实现业务逻辑
- 通过 `PluginContext` 访问基础设施
- 通过 `@LingService` 暴露服务

**关键原则**：

- **零信任**：不能直接访问数据库、缓存等
- 所有能力调用必须经过 Core 代理和鉴权
- 在 `plugin.yml` 中声明所需权限

## 数据流

### 业务插件调用基础设施

```
┌─────────────────────────────────────────────────────────────┐
│ Business Plugin (用户插件)                                   │
│                                                              │
│   userRepository.findById(id)                               │
│         │                                                    │
└─────────┼────────────────────────────────────────────────────┘
          │ JDBC 调用
          ▼
┌─────────────────────────────────────────────────────────────┐
│ Infrastructure Plugin (Storage)                              │
│                                                              │
│   LingPreparedStatementProxy.executeQuery()                 │
│         │                                                    │
│         ├─→ checkPermission()                               │
│         │     │                                              │
│         │     ├─→ PluginContextHolder.get() → "user-plugin" │
│         │     │                                              │
│         │     ├─→ preParsedAccessType (构造时已解析)         │
│         │     │                                              │
│         │     ├─→ permissionService.isAllowed(              │
│         │     │       "user-plugin", "storage:sql", READ)   │
│         │     │                                              │
│         │     └─→ permissionService.audit(...)              │
│         │                                                    │
│         └─→ target.executeQuery()                           │
│                                                              │
└─────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────┐
│ Core                                                         │
│                                                              │
│   DefaultPermissionService.isAllowed()                      │
│         │                                                    │
│         ├─→ 检查白名单                                       │
│         ├─→ 查询权限表                                       │
│         └─→ 开发模式兜底                                     │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

> 注：`LingPreparedStatementProxy` 在构造时预解析 SQL 类型并缓存，执行时直接使用。
> `LingStatementProxy` 则在每次执行时解析传入的 SQL。

### 业务插件调用业务插件（方式一：FQSID 协议调用）

```
┌─────────────────────────────────────────────────────────────┐
│ Order Plugin                                                 │
│                                                              │
│   context.invoke("user-plugin:query_user", userId)          │
│         │                                                    │
└─────────┼────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────┐
│ Core                                                         │
│                                                              │
│   CorePluginContext.invoke()                                │
│         │                                                    │
│         ├─→ GovernanceStrategy.inferAccessType() → EXECUTE  │
│         ├─→ permissionService.isAllowed() 权限检查           │
│         │                                                    │
│         ▼                                                    │
│   PluginManager.invokeService()                             │
│         │                                                    │
│         ├─→ protocolServiceRegistry.get(fqsid) 查找路由      │
│         │                                                    │
│         ▼                                                    │
│   PluginSlot.invokeService()                                │
│         │                                                    │
│         ├─→ instance.enter() (引用计数+1)                    │
│         ├─→ TCCL 劫持                                        │
│         ├─→ serviceMethodCache.get(fqsid) 获取方法           │
│         ├─→ method.invoke() 反射调用                         │
│         ├─→ TCCL 恢复                                        │
│         └─→ instance.exit() (引用计数-1)                     │
│                                                              │
└─────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────┐
│ User Plugin                                                  │
│                                                              │
│   @LingService(id = "query_user")                           │
│   public User queryUser(String userId) { ... }              │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 业务插件调用业务插件（方式二：接口代理调用）

```
┌─────────────────────────────────────────────────────────────┐
│ Order Plugin                                                 │
│                                                              │
│   UserService userService = context.getService(UserService.class).get();
│   userService.queryUser(userId);                            │
│         │                                                    │
└─────────┼────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────┐
│ Core                                                         │
│                                                              │
│   SmartServiceProxy.invoke() (JDK 动态代理)                  │
│         │                                                    │
│         ├─→ PluginContextHolder.set(callerPluginId)         │
│         ├─→ TraceContext.start() 开启链路追踪               │
│         ├─→ checkPermissionSmartly() 权限检查               │
│         │     ├─→ @RequiresPermission 显式声明              │
│         │     └─→ GovernanceStrategy.inferPermission() 推导 │
│         │                                                    │
│         ├─→ activeInstanceRef.get() 获取活跃实例            │
│         ├─→ instance.enter() (引用计数+1)                    │
│         ├─→ TCCL 劫持                                        │
│         ├─→ method.invoke(realBean, args)                   │
│         ├─→ TCCL 恢复                                        │
│         ├─→ instance.exit() (引用计数-1)                     │
│         └─→ recordAuditSmartly() 智能审计                   │
│                                                              │
└─────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────┐
│ User Plugin                                                  │
│                                                              │
│   public class UserServiceImpl implements UserService {     │
│       public User queryUser(String userId) { ... }          │
│   }                                                          │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

> 两种调用方式的区别：
>
> - `invoke(fqsid)`: 通过 FQSID 字符串调用 `@LingService` 标注的方法
> - `getService(Class)`: 获取接口的动态代理，调用时自动路由到实现类

## 隔离机制

### 类加载隔离

```
┌─────────────────────────────────────────────────────────────┐
│                    AppClassLoader                            │
│                    (宿主应用)                                 │
│                                                              │
│   lingframe-api (共享契约)                                   │
│   lingframe-core                                             │
│   Spring Boot                                                │
│                                                              │
└────────────────────────┬────────────────────────────────────┘
                         │ parent
         ┌───────────────┼───────────────┐
         ▼               ▼               ▼
┌─────────────┐   ┌─────────────┐   ┌─────────────┐
│PluginClassLoader│PluginClassLoader│PluginClassLoader
│  (Plugin A)  │   │  (Plugin B)  │   │  (Plugin C)  │
│              │   │              │   │              │
│ Child-First  │   │ Child-First  │   │ Child-First  │
│ 优先加载自己  │   │ 优先加载自己  │   │ 优先加载自己  │
└─────────────┘   └─────────────┘   └─────────────┘
```

**白名单委派**（强制走父加载器）：

- `java.*`, `javax.*`, `jdk.*`, `sun.*`
- `com.lingframe.api.*`（契约层必须共享）
- `org.slf4j.*`（日志门面共享）

### Spring 上下文隔离

```
┌─────────────────────────────────────────────────────────────┐
│              Parent Context (宿主应用)                        │
│                                                              │
│   PluginManager, ContainerFactory, PermissionService        │
│   公共 Bean...                                               │
│                                                              │
└────────────────────────┬────────────────────────────────────┘
                         │ parent
         ┌───────────────┼───────────────┐
         ▼               ▼               ▼
┌─────────────┐   ┌─────────────┐   ┌─────────────┐
│ Child Ctx A │   │ Child Ctx B │   │ Child Ctx C │
│ (Plugin A)  │   │ (Plugin B)  │   │ (Plugin C)  │
│             │   │             │   │             │
│ 独立 Bean   │   │ 独立 Bean   │   │ 独立 Bean   │
│ 独立配置    │   │ 独立配置    │   │ 独立配置    │
└─────────────┘   └─────────────┘   └─────────────┘
```

## 生命周期

### 插件安装流程

```
PluginManager.install(pluginId, version, jarFile)
    │
    ├─→ createPluginClassLoader(file)     // Child-First 类加载器
    │
    ├─→ containerFactory.create()          // SPI 创建容器
    │
    ├─→ slot.upgrade(instance, context)    // 蓝绿部署
    │       │
    │       ├─→ 背压检查 (死亡队列 < 3)
    │       ├─→ container.start(context)   // 启动 Spring 子上下文
    │       ├─→ scanAndRegisterLingServices()  // 注册 @LingService
    │       ├─→ plugin.onStart(context)    // 生命周期回调
    │       └─→ activeInstance.set(new)    // 原子切换
    │
    └─→ 旧版本进入死亡队列，等待引用计数归零后销毁
```

### 蓝绿部署

```
时间线 ─────────────────────────────────────────────────────→

v1.0 运行中
    │
    │  新版本安装请求
    ▼
┌─────────────────────────────────────────────────────────────┐
│ v1.0 (active)                                                │
│ 继续处理请求                                                  │
│                                                              │
│                    v2.0 启动中...                            │
│                    ┌─────────────────────────────────────┐  │
│                    │ 创建 ClassLoader                     │  │
│                    │ 启动 Spring Context                  │  │
│                    │ 注册 @LingService                    │  │
│                    └─────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
    │
    │  原子切换
    ▼
┌─────────────────────────────────────────────────────────────┐
│ v2.0 (active)                                                │
│ 接收新请求                                                    │
│                                                              │
│ v1.0 (dying)                                                 │
│ 处理剩余请求，引用计数递减                                     │
└─────────────────────────────────────────────────────────────┘
    │
    │  引用计数归零
    ▼
┌─────────────────────────────────────────────────────────────┐
│ v2.0 (active)                                                │
│                                                              │
│ v1.0 销毁                                                    │
│ - plugin.onStop()                                           │
│ - Spring Context.close()                                    │
│ - ClassLoader 释放                                          │
└─────────────────────────────────────────────────────────────┘
```

## 模块对应关系

| 架构层         | Maven 模块                       | 说明                 |
| -------------- | -------------------------------- | -------------------- |
| Core           | `lingframe-core`                 | 仲裁核心实现         |
| Core           | `lingframe-api`                  | 契约层（接口、注解） |
| Core           | `lingframe-spring-boot3-starter` | Spring Boot 集成     |
| Infrastructure | `lingframe-plugin-storage`       | 存储插件             |
| Infrastructure | `lingframe-plugin-cache`         | 缓存插件（待实现）   |
| Business       | 用户插件                         | 业务逻辑实现         |
