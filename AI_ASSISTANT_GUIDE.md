# AI Assistant Guide

本文件为 AI 助手在本仓库中工作的指导说明。

## 项目概览

**LingFrame（灵珑）** 是基于 JVM 的新一代微内核插件框架（v0.0.1）。

**当前状态**：核心框架已实现，包括插件管理、热重载、权限治理、Spring Boot 3 集成。

## 技术栈

- Java 21 / Maven / Spring Boot 3.5.6 / Lombok

## 构建命令

```bash
mvn clean install          # 完整构建
mvn clean install -DskipTests  # 跳过测试
mvn test -pl lingframe-core    # 测试指定模块
```

## 核心机制

### 服务扩展（@LingService）

```java
@LingService(id = "query_user", desc = "查询用户")
public User queryUser(String userId) { ... }

// 调用：context.invoke("plugin-id:query_user", userId)
```

### 权限治理

1. 显式：`@RequiresPermission("capability")`
2. 智能推导：get→READ, save→WRITE
3. 开发模式：`LingFrameConfig.setDevMode(true)` 仅警告

### 插件生命周期

- 安装：`PluginManager.install()` / `installDev()`
- 蓝绿部署：`PluginSlot.upgrade()` 原子切换
- 热重载：`HotSwapWatcher` 文件监听

### 类加载

- Child-First 优先加载插件类
- 白名单委派：`com.lingframe.api.*`、JDK 类

## 关键类

| 类                   | 职责       |
| -------------------- | ---------- |
| `PluginManager`      | 插件管理   |
| `PluginSlot`         | 蓝绿部署   |
| `SmartServiceProxy`  | 动态代理   |
| `CorePluginContext`  | 上下文实现 |
| `PluginClassLoader`  | 类加载器   |
| `GovernanceStrategy` | 权限推导   |

## 模块结构

```
lingframe-api/       # 契约层（接口、注解）
lingframe-core/      # 核心实现
lingframe-runtime/   # Spring Boot 集成
lingframe-plugins-infra/  # 基础设施插件
lingframe-samples/   # 示例
```

## 架构原则

- **零信任**：插件通过 Core 仲裁访问基础设施
- **契约优先**：交互通过 `lingframe-api` 接口
- **上下文隔离**：每个插件独立 Spring 子上下文
- **FQSID 路由**：`pluginId:serviceId` 全局唯一

## 文档

- [快速入门](docs/getting-started.md)
- [插件开发](docs/plugin-development.md)
- [架构设计](docs/architecture.md)
- [API 参考](docs/api-reference.md)
