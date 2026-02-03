# 架构设计

本文档介绍 LingFrame 的核心架构设计和实现原理。

## 设计理念

LingFrame 借鉴操作系统的设计思想：

- **微内核**：Core 只负责调度和仲裁，不包含业务逻辑
- **零信任**：业务模块不能直接访问基础设施，必须经过 Core 代理
- **能力隔离**：每个插件在独立的类加载器和 Spring 上下文中运行

## 三层架构

```
┌─────────────────────────────────────────────────────────────┐
│                      Host Application                        │
│                                                              │
│  ┌───────────────────────────────────────────────────────┐  │
│  │                 Core（治理内核）                        │  │
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
│  │  业务模块层  │   │  业务模块层  │   │  业务模块层  │       │
│  └─────────────┘   └─────────────┘   └─────────────┘       │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

### 第一层：Core（治理内核）

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

| 组件                      | 职责                   |
| ------------------------ | ---------------------- |
| `PluginManager`          | 插件安装/卸载/服务路由 |
| `PluginRuntime`          | 插件运行时环境         |
| `InstancePool`           | 蓝绿部署和版本管理     |
| `ServiceRegistry`        | 服务注册表             |
| `InvocationExecutor`     | 调用执行器             |
| `PluginLifecycleManager` | 生命周期管理           |
| `PermissionService`      | 权限检查和授权         |
| `AuditManager`           | 审计日志记录           |
| `EventBus`               | 事件发布订阅           |
| `GovernanceKernel`       | 治理内核               |

### 第二层：Infrastructure（基础设施层）

**模块**：`lingframe-infrastructure/*`

**职责**：

- 封装底层能力（数据库、缓存、消息队列）
- 细粒度权限拦截
- 审计上报

**已实现**：

| 模块                         | 说明                       | 能力标识      |
| -------------------------- | -------------------------- | ------------- |
| `lingframe-infra-storage`  | 数据库访问，SQL 级权限控制 | `storage:sql` |
| `lingframe-infra-cache`    | 缓存访问（待实现）         | `cache:redis` |

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

> 详细开发指南见 [基础设施代理开发](infrastructure-development.md)

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

### 业务模块调用基础设施

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

### 业务模块间调用（方式一：@LingReference 注入 - 推荐）

**消费者驱动契约**：Order 插件（消费者）定义 `UserQueryService` 接口，User 插件（生产者）实现

```
┌─────────────────────────────────────────────────────────────┐
│ Order Plugin（消费者）                                       │
│                                                              │
│   // Order 定义它需要的接口（在 order-api 中）               │
│   interface UserQueryService { UserDTO findById(userId); }  │
│                                                              │
│   @LingReference                                             │
│   private UserQueryService userQueryService;                │
│                                                              │
│   userQueryService.findById(userId);  // 直接调用           │
│         │                                                    │
└─────────┼────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────┐
│ Core                                                         │
│                                                              │
│   GlobalServiceRoutingProxy.invoke() (JDK 动态代理)         │
│         │                                                    │
│         ├─→ resolveTargetPluginId() 解析目标插件            │
│         │     ├─→ 检查注解指定的 pluginId                   │
│         │     └─→ 遍历所有插件查找接口实现（带缓存）         │
│         │                                                    │
│         ├─→ pluginManager.getRuntime(pluginId)              │
│         │                                                    │
│         ▼                                                    │
│   SmartServiceProxy.invoke() (委托给智能代理)               │
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
│ User Plugin（生产者）                                        │
│                                                              │
│   // 实现消费者定义的接口                                    │
│   public class UserQueryServiceImpl implements UserQueryService {
│       @LingService(id = "find_user", desc = "查询用户")     │
│       public UserDTO findById(String userId) { ... }        │
│   }                                                          │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 业务模块间调用（方式二：FQSID 协议调用）

```
┌─────────────────────────────────────────────────────────────┐
│ Order Plugin（消费者）                                       │
│                                                              │
│   context.invoke("user-plugin:find_user", userId)           │
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
│   PluginRuntime.invokeService()                             │
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
│ User Plugin（生产者）                                        │
│                                                              │
│   @LingService(id = "find_user", desc = "查询用户")         │
│   public UserDTO findById(String userId) { ... }            │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 业务模块间调用（方式三：接口代理调用）

**消费者驱动契约**：Order 定义 `UserQueryService` 接口，通过 getService() 获取实现

```
┌─────────────────────────────────────────────────────────────┐
│ Order Plugin（消费者）                                       │
│                                                              │
│   // 获取消费者自己定义的接口实现                            │
│   UserQueryService userService = context.getService(UserQueryService.class).get();
│   userService.findById(userId);                             │
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
│ User Plugin（生产者）                                        │
│                                                              │
│   // 实现消费者定义的接口                                    │
│   public class UserQueryServiceImpl implements UserQueryService {
│       public UserDTO findById(String userId) { ... }        │
│   }                                                          │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

> 三种服务调用方式的区别：
>
> - **@LingReference 注入**（推荐）：通过 LingReferenceInjector 自动注入，使用 GlobalServiceRoutingProxy 实现延迟绑定和智能路由
> - **invoke(fqsid)**：通过 FQSID 字符串调用 `@LingService` 标注的方法
> - **getService(Class)**：获取接口的动态代理，调用时自动路由到实现类

## 服务调用方式详解

### @LingReference 注入机制（推荐）

@LingReference 是 LingFrame 推荐的服务调用方式，提供最接近 Spring 原生的开发体验：

#### 工作原理

```
宿主应用启动
    │
    ▼
LingReferenceInjector (BeanPostProcessor)
    │
    ├─→ 扫描所有 Bean 的 @LingReference 字段
    │
    ├─→ 调用 PluginManager.getGlobalServiceProxy()
    │     │
    │     └─→ 创建 GlobalServiceRoutingProxy
    │
    └─→ 通过反射注入代理对象到字段
```

#### 延迟绑定机制

```
@LingReference 字段调用
    │
    ▼
GlobalServiceRoutingProxy.invoke()
    │
    ├─→ resolveTargetPluginId() 动态解析目标插件
    │     ├─→ 检查注解指定的 pluginId
    │     ├─→ 查询路由缓存 (ROUTE_CACHE)
    │     └─→ 遍历所有插件查找接口实现
    │
    ├─→ pluginManager.getRuntime(pluginId) 获取运行时
    │
    └─→ 委托给 SmartServiceProxy 执行治理逻辑
```

#### 使用示例

```java
// Order 插件（消费者）定义它需要的接口（在 order-api 模块中）
// 位置：order-api/src/main/java/com/example/order/api/UserQueryService.java
public interface UserQueryService {
    Optional<UserDTO> findById(String userId);
}

// User 插件（生产者）实现消费者定义的接口
// 位置：user-plugin/src/main/java/com/example/user/service/UserQueryServiceImpl.java
@Component
public class UserQueryServiceImpl implements UserQueryService {
    @LingService(id = "find_user_by_id", desc = "根据ID查询用户")
    @Override
    public Optional<UserDTO> findById(String userId) {
        return userRepository.findById(userId).map(this::toDTO);
    }
}

// Order 插件中使用自己定义的接口
@RestController
public class OrderController {
    
    // 注入消费者自己定义的接口，由 User 插件实现
    @LingReference
    private UserQueryService userQueryService;
    
    @GetMapping("/orders/{userId}")
    public List<Order> getUserOrders(@PathVariable String userId) {
        // 直接调用，框架自动路由到 User 插件的实现
        UserDTO user = userQueryService.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        
        return orderService.findByUser(user);
    }
}
```

#### 配置选项

| 属性       | 说明                                    | 默认值 |
| ---------- | --------------------------------------- | ------ |
| `pluginId` | 指定目标插件ID，为空时自动发现          | 空     |
| `timeout`  | 调用超时时间（毫秒）                    | 3000   |

#### 优势特性

1. **延迟绑定**：插件未启动时也能创建代理，运行时动态路由
2. **智能路由**：自动路由到最新版本插件，支持蓝绿部署
3. **缓存优化**：接口到插件的映射关系会被缓存，避免重复查找
4. **故障隔离**：插件下线时抛出明确异常，不影响其他功能
5. **开发友好**：最接近 Spring 原生体验，学习成本最低

### FQSID 协议调用

适合松耦合场景，不需要接口依赖：

```java
@Service
public class OrderService {
    @Autowired
    private PluginContext context;
    
    public Order createOrder(String userId) {
        // 通过 FQSID 直接调用生产者的服务，返回 Optional
        Optional<UserDTO> user = context.invoke("user-plugin:find_user", userId);
        
        if (user.isEmpty()) {
            throw new BusinessException("用户不存在");
        }
        
        return new Order(user.get());
    }
}
```

### 接口代理调用

适合需要显式错误处理的场景（消费者定义接口，生产者实现）：

```java
@Service
public class OrderService {
    @Autowired
    private PluginContext context;
    
    public Order createOrder(String userId) {
        // 获取消费者定义的接口实现（由 User 插件提供）
        Optional<UserQueryService> userQueryService = context.getService(UserQueryService.class);
        
        if (userQueryService.isEmpty()) {
            throw new ServiceUnavailableException("用户查询服务不可用");
        }
        
        UserDTO user = userQueryService.get().findById(userId)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        return new Order(user);
    }
}
```

### 调用方式选择指南

| 场景                     | 推荐方式           | 原因                           |
| ------------------------ | ------------------ | ------------------------------ |
| 宿主应用调用插件服务     | @LingReference     | 最简洁，支持延迟绑定           |
| 插件间强依赖调用         | @LingReference     | 类型安全，IDE 友好             |
| 插件间松耦合调用         | FQSID 协议         | 不需要接口依赖                 |
| 需要显式错误处理         | 接口代理           | 可以优雅处理服务不可用情况     |
| 动态服务发现             | 接口代理           | 运行时动态获取可用服务         |
| 可选功能调用             | @LingReference     | 支持 null 检查，不会启动时报错 |

## 隔离机制

### 类加载隔离

LingFrame 采用三层 ClassLoader 架构，解决插件间共享 API 的类型一致性问题：

```
┌─────────────────────────────────────────────────────────────┐
│                    AppClassLoader                            │
│                    (宿主应用)                                 │
│                                                              │
│   lingframe-api (框架契约)                                   │
│   lingframe-core                                             │
│   Spring Boot                                                │
│                                                              │
└────────────────────────┬────────────────────────────────────┘
                         │ parent
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                 SharedApiClassLoader                         │
│                 (共享 API 层)                                │
│                                                              │
│   order-api.jar (插件间共享的接口和 DTO)                     │
│   user-api.jar                                               │
│   ...                                                        │
│                                                              │
└────────────────────────┬────────────────────────────────────┘
                         │ parent
         ┌───────────────┼───────────────┐
         ▼               ▼               ▼
┌─────────────┐   ┌─────────────┐   ┌─────────────┐
│PluginCL A  │   │PluginCL B  │   │PluginCL C  │
│             │   │             │   │             │
│ Child-First │   │ Child-First │   │ Child-First │
│ 优先加载自己 │   │ 优先加载自己 │   │ 优先加载自己 │
└─────────────┘   └─────────────┘   └─────────────┘
```

**配置共享 API**（`application.yaml`）：

```yaml
lingframe:
  preload-api-jars:
    - libs/order-api.jar              # JAR 文件
    - lingframe-examples/order-api    # Maven 模块目录
    - libs/*-api.jar                  # 通配符匹配
```

**白名单委派**（强制走父加载器）：

- `java.*`, `javax.*`, `jdk.*`, `sun.*`
- `com.lingframe.api.*`（框架契约层）
- `org.slf4j.*`（日志门面）
- **SharedApiClassLoader 中的所有类**（自动检测）

> 详见 [共享 API 设计规范](shared-api-guidelines.md)

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
    ├─→ 安全验证 (DangerousApiVerifier)
    │
    ├─→ createPluginClassLoader(file)     // Child-First 类加载器
    │
    ├─→ containerFactory.create()          // SPI 创建容器
    │
    ├─→ 创建 PluginInstance
    │
    ├─→ 获取或创建 PluginRuntime
    │
    ├─→ runtime.addInstance(instance, context, isDefault)  // 蓝绿部署
    │       │
    │       ├─→ instancePool.add(instance)     // 添加到实例池
    │       ├─→ container.start(context)       // 启动 Spring 子上下文
    │       ├─→ serviceRegistry.register()     // 注册 @LingService
    │       ├─→ plugin.onStart(context)        // 生命周期回调
    │       └─→ instancePool.setDefault(instance)  // 设置为默认实例
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
| Core           | `lingframe-core`                 | 治理内核实现         |
| Core           | `lingframe-api`                  | 契约层（接口、注解） |
| Core           | `lingframe-spring-boot3-starter` | Spring Boot 集成     |
| Infrastructure | `lingframe-infra-storage`      | 存储代理             |
| Infrastructure | `lingframe-infra-cache`        | 缓存代理             |
| Business       | 用户插件                         | 业务逻辑实现         |
