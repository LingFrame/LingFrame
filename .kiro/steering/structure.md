# 项目结构

## 模块组织

LingFrame 采用分层多模块 Maven 架构，职责分离清晰：

### 核心框架模块

- **`lingframe-dependencies/`**：版本管理和依赖协调（父 POM）
- **`lingframe-bom/`**：外部消费者的物料清单
- **`lingframe-api/`**：核心契约和接口（插件生命周期、上下文、事件、安全、注解）
- **`lingframe-core/`**：框架实现（插件管理、事件总线、安全、类加载、代理）

### 运行时集成

- **`lingframe-runtime/`**：运行时环境适配器
  - **`lingframe-spring-boot3-starter/`**：Spring Boot 3 自动配置和集成

### 基础设施插件

- **`lingframe-plugins-infra/`**：基础设施插件实现
  - **`lingframe-plugin-storage/`**：数据库访问代理（DataSource/Connection/Statement 代理链）
  - **`lingframe-plugin-cache/`**：缓存基础设施（待实现）

### 示例应用

- **`lingframe-samples/`**：示例实现和演示
  - **`lingframe-sample-host-app/`**：宿主应用示例，包含 DevLoader
  - **`lingframe-sample-plugin-user/`**：示例插件实现

## 包结构约定

### API 模块 (`com.lingframe.api`)

```
com.lingframe.api/
├── annotation/          # 框架注解
│   ├── @LingService     # 服务扩展点（核心）
│   ├── @RequiresPermission  # 权限声明
│   └── @Auditable       # 审计声明
├── context/
│   ├── PluginContext    # 插件上下文接口
│   └── PluginContextHolder  # ThreadLocal 持有者
├── event/
│   ├── LingEvent        # 事件标记接口
│   └── LingEventListener  # 事件监听器接口
├── exception/
│   ├── LingException    # 基础异常
│   └── PermissionDeniedException  # 权限异常
├── plugin/
│   └── LingPlugin       # 插件生命周期接口
└── security/
    ├── AccessType       # 访问类型枚举
    └── PermissionService  # 权限服务接口
```

### Core 模块 (`com.lingframe.core`)

```
com.lingframe.core/
├── audit/
│   └── AuditManager     # 异步审计管理器
├── classloader/
│   └── PluginClassLoader  # Child-First 类加载器
├── config/
│   └── LingFrameConfig  # 全局配置（devMode）
├── context/
│   └── CorePluginContext  # 上下文实现
├── dev/
│   └── HotSwapWatcher   # 热重载文件监听
├── event/
│   └── EventBus         # 事件总线实现
├── monitor/
│   └── TraceContext     # 链路追踪上下文
├── plugin/
│   ├── PluginManager    # 插件管理器（核心）
│   ├── PluginSlot       # 蓝绿部署槽位
│   └── PluginInstance   # 插件实例 + 引用计数
├── proxy/
│   └── SmartServiceProxy  # 动态代理（路由+权限+审计）
├── security/
│   └── DefaultPermissionService  # 权限服务实现
├── spi/
│   ├── ContainerFactory   # 容器工厂 SPI
│   └── PluginContainer    # 插件容器 SPI
└── strategy/
    └── GovernanceStrategy  # 治理策略推导器
```

### Runtime 模块 (`com.lingframe.starter`)

```
com.lingframe.starter/
├── adapter/
│   ├── SpringContainerFactory  # Spring 容器工厂
│   └── SpringPluginContainer   # Spring 子上下文管理
└── configuration/
    └── LingFrameAutoConfiguration  # 自动配置
```

## 文件命名约定

### Java 类

- **接口**：描述性名称（如 `PluginContext`、`LingPlugin`）
- **实现类**：`Default` 或 `Core` 前缀（如 `DefaultPermissionService`、`CorePluginContext`）
- **异常**：`Exception` 后缀（如 `LingException`、`PermissionDeniedException`）
- **注解**：描述性名称（如 `@LingService`、`@RequiresPermission`）
- **代理类**：`Proxy` 后缀（如 `SmartServiceProxy`、`LingDataSourceProxy`）

### 配置文件

- **插件元数据**：`src/main/resources/plugin.yml`
- **Spring 配置**：`application.yml` 或 `application.properties`
- **Maven**：标准 `pom.xml` 结构，继承父级

## 模块依赖流

```
lingframe-dependencies (父级，版本管理)
├── lingframe-bom (BOM)
├── lingframe-api (契约层，无依赖)
├── lingframe-core (依赖 api)
├── lingframe-runtime/
│   └── lingframe-spring-boot3-starter (依赖 core + Spring Boot)
├── lingframe-plugins-infra/
│   ├── lingframe-plugin-storage (依赖 api)
│   └── lingframe-plugin-cache (依赖 api)
└── lingframe-samples/
    ├── lingframe-sample-host-app (依赖 starter)
    └── lingframe-sample-plugin-user (依赖 api)
```

## 关键架构原则

- **零信任**：业务插件只能通过 `PluginContext` 访问 Core 仲裁的服务
- **上下文隔离**：每个插件在独立的 Spring 子上下文中运行
- **契约优先**：所有交互通过 `lingframe-api` 中定义的接口
- **Child-First 类加载**：插件优先加载自己的类，API 层强制委派
- **FQSID 路由**：`@LingService` 服务通过 `pluginId:shortId` 全局唯一标识

## 核心数据流

### 服务调用链

```
Plugin A 调用 → context.invoke("plugin-b:service_id", args)
    ↓
CorePluginContext.invoke()  // 权限检查
    ↓
PluginManager.invokeService()  // 路由查找
    ↓
PluginSlot.invokeService()  // TCCL 劫持 + 引用计数
    ↓
反射调用目标方法
```

### 插件安装链

```
PluginManager.install(pluginId, version, jarFile)
    ↓
PluginClassLoader 创建  // Child-First
    ↓
ContainerFactory.create()  // SPI
    ↓
PluginSlot.upgrade()  // 蓝绿部署
    ↓
SpringPluginContainer.start()  // 启动子上下文
    ↓
scanAndRegisterLingServices()  // 注册 @LingService
    ↓
LingPlugin.onStart()  // 生命周期回调
```
