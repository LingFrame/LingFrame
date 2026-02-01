# 更新日志

本项目的所有重大更改都将记录在此文件中。

## [V0.1.0-Preview] - 2026-02-01

> **初版 (Preview)**：此版本验证了 JVM 进程内运行时治理的可行性。
> 核心关注点：边界、隔离与控制。

### 🚀 新特性

#### 核心架构 (JVM Runtime Governance)
- **三层 ClassLoader 架构**：实现了 `HostClassLoader` -> `SharedApiClassLoader` -> `PluginClassLoader` 的层级结构，确保严格隔离的同时允许受控共享。
- **Child-First 类加载机制**：插件优先加载自身依赖，防止与宿主应用发生“依赖地狱”。
- **Spring 上下文隔离**：每个插件在独立的 Spring `ApplicationContext` 中运行，确保 Bean 隔离和独立的生命周期。

#### 插件系统 (Plugin System)
- **生命周期管理**：通过 `PluginManager` 全面支持 `LOAD`（加载）、`START`（启动）、`STOP`（停止）、`UNLOAD`（卸载）及热重载能力。
- **Manifest 配置**：定义了 `plugin.yml` 标准，用于声明元数据、依赖项和所需能力。
- **服务导出/导入**：
  - `@LingService`：将 Bean 导出为跨边界的服务。
  - `@LingReference`：注入来自其他插件或宿主的服务。

#### 治理与安全 (Governance & Security)
- **权限控制**：
  - 实现了 `GovernancePolicy` 用于定义访问控制列表 (ACL)。
  - 添加了 `@RequiresPermission` 用于细粒度的方法级授权。
- **审计与追踪**：
  - `@Auditable` 注解用于记录敏感操作。
  - `TraceContext` 用于跨插件边界传播请求元数据。
- **流量路由**：
  - `LabelMatchRouter` 实现，支持灰度发布和基于标签的流量路由。

#### 仪表盘与运维 (Dashboard & Operations)
- **可视化管理**：提供基于 Web 的仪表盘（预览版），用于监控插件状态和管理配置。
- **动态控制**：
  - 通过 UI/API 启动/停止插件。
  - 无需重启 JVM 即可热重载插件。
  - 运行时调整权限策略。

#### 基础设施 SPI (Infrastructure SPI)
- **代理抽象**：
  - `StorageService` 代理文件操作。
  - `CacheService` 代理缓存（本地/远程）。

### ⚠️ 技术边界与限制
- **仅限单进程**：专为单体应用改造设计，而非分布式微服务框架。
- **兼容性**：基于 JDK 21 (LTS) 和 Spring Boot 3.x 构建。
- **待实现功能** (Phase 3)：熔断 (Circuit Breaking)、限流 (Rate Limiting) 和降级 (Fallback) 机制已定义但尚未完全投入使用。

### 🛠 基础设施
- 建立了标准的 Maven 多模块项目结构 (`core`, `api`, `dashboard`, `runtime`, `infrastructure`)。
- 集成了 `maven-compiler-plugin` 和 `flatten-maven-plugin` 以标准化构建。
