# AI Assistant Guide

> 本文件帮助 AI 助手快速理解 LingFrame 项目。

## 项目定位

**LingFrame（灵珑）** = JVM 运行时治理框架

核心能力：插件隔离 + 权限治理 + 审计追踪 + 热重载

## 技术栈

- Java 21+
- Spring Boot 3.5.6
- Maven 3.8+

## 架构原则（必须遵守）

### 核心设计
1. **零信任**：业务模块不能直接访问 DB/Redis，必须经过 Core 代理
2. **微内核**：Core 只做调度仲裁，不包含业务逻辑
3. **契约优先**：所有交互通过 `lingframe-api` 接口
4. **上下文隔离**：每个模块独立 ClassLoader + Spring 子上下文
5. **FQSID 路由**：服务通过 `pluginId:serviceId` 全局唯一标识

### 模块职责
- **lingframe-core**：纯 Java 实现，**不依赖任何框架**（无 Spring、无 ORM）
- **lingframe-runtime**：生态适配层，如 `spring-boot3-starter` 适配 Spring
- **lingframe-api**：契约层，Core 和插件都依赖它

### 设计原则
- **单一职责（SRP）**：每个类只做一件事
- **依赖倒置（DIP）**：Core 依赖抽象，不依赖具体实现
- **开闭原则（OCP）**：通过扩展点增加功能，不修改核心代码
- **接口隔离（ISP）**：小而专的接口，不强迫实现不需要的方法

## 编写规范

### 对 Core 模块的修改
- **禁止**引入 Spring、Hibernate、MyBatis 等框架依赖
- **禁止**直接 new 具体实现，使用工厂或注入
- 公开 API 必须定义在 `lingframe-api` 中

### 对运行时模块的修改
- 适配层负责桥接 Core 和具体框架
- 使用 `@Configuration` 装配 Core 组件

### 模块开发
- 只依赖 `lingframe-api`，**禁止**依赖 `lingframe-core`
- 用 `@LingReference` 注入其他模块服务，**不用** `@Autowired`
- 用 `@LingService` 暴露服务

### 权限声明
- 敏感操作加 `@RequiresPermission`
- 需要审计加 `@Auditable`
- 框架会智能推导：get/find → READ，save/delete → WRITE

### 共享 API 设计
- 只放接口和 DTO，**不放**实现
- 接口在消费者侧定义（消费者驱动契约）
- DTO 必须可序列化，**不放**业务逻辑

### 命名约定
| 类型 | 规则 | 示例 |
|------|------|------|
| 接口 | 描述性名称 | `UserService` |
| 实现类 | `Default` 或 `Core` 前缀 | `DefaultPermissionService` |
| 代理类 | `Proxy` 后缀 | `SmartServiceProxy` |
| 配置类 | `Properties` 后缀 | `LingFrameProperties` |
| 工厂类 | `Factory` 后缀 | `SpringContainerFactory` |

## 核心类速查

| 类 | 职责 |
|---|---|
| `PluginManager` | 插件安装/卸载/服务路由 |
| `PluginRuntime` | 单个插件的运行时环境 |
| `InstancePool` | 蓝绿部署、版本切换 |
| `SharedApiClassLoader` | 加载插件间共享的 API |
| `PluginClassLoader` | 插件类加载（Child-First） |
| `ServiceRegistry` | 服务注册表 |
| `InvocationExecutor` | 调用执行器 |
| `GovernanceKernel` | 治理内核 |
| `SmartServiceProxy` | 智能服务代理 |
| `GlobalServiceRoutingProxy` | @LingReference 的代理实现 |

## 三层 ClassLoader

```
AppClassLoader (宿主)
    ↓ parent
SharedApiClassLoader (共享 API)
    ↓ parent
PluginClassLoader (插件，Child-First)
```

## 关键配置格式

### 宿主应用 (application.yaml)

```yaml
lingframe:
  enabled: true
  dev-mode: true                    # 开发模式，权限宽松
  plugin-home: "plugins"            # JAR 包目录
  plugin-roots:                     # 开发时的插件根目录
    - "../my-plugin"
  preload-api-jars:                 # 共享 API JAR
    - "shared-api/*.jar"
```

### 插件元数据 (plugin.yml)

```yaml
id: my-plugin                       # 无 plugin: 根节点！
version: 1.0.0
mainClass: "com.example.MyPlugin"
governance:
  permissions:
    - methodPattern: "storage:sql"  # 不是 capability
      permissionId: "READ"          # 不是 access
```

## ⚠️ 常见错误

| 错误 | 正确 |
|------|------|
| `devMode: true` | `dev-mode: true` (kebab-case) |
| `plugin.yml` 有 `plugin:` 根节点 | 直接写属性，无根节点 |
| 插件依赖 `lingframe-core` | 只依赖 `lingframe-api` |
| 用 `@Autowired` 注入其他插件服务 | 用 `@LingReference` |
| 找 `PluginSlot` 类 | 不存在，用 `PluginRuntime` |

## 模块结构

```
lingframe/
├── lingframe-api/              # 契约层
├── lingframe-core/             # 治理内核
├── lingframe-runtime/
│   └── lingframe-spring-boot3-starter/
├── lingframe-infrastructure/
│   ├── lingframe-infra-storage/
│   └── lingframe-infra-cache/
├── lingframe-dashboard/        # 可视化治理
└── lingframe-examples/
```

## 常用命令

```bash
mvn clean install -DskipTests          # 构建
mvn spring-boot:run -pl lingframe-examples/lingframe-example-host-app  # 运行示例
```

## 文档索引

| 文档 | 用途 |
|------|------|
| [getting-started.md](docs/zh-CN/getting-started.md) | 5 分钟上手 |
| [plugin-development.md](docs/zh-CN/plugin-development.md) | 模块开发 |
| [shared-api-guidelines.md](docs/zh-CN/shared-api-guidelines.md) | API 设计规范 |
| [architecture.md](docs/zh-CN/architecture.md) | 架构详解 |
| [infrastructure-development.md](docs/zh-CN/infrastructure-development.md) | 基础设施代理 |
| [dashboard.md](docs/zh-CN/dashboard.md) | Dashboard |
| [roadmap.md](docs/zh-CN/roadmap.md) | 路线图 |
