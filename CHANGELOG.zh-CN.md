# 更新日志

本项目的所有重大更改都将记录在此文件中。

## [V0.2.0] - 2026-02-23

### 🚀 新特性

- **弹性治理**：在 `GovernanceKernel` 中全面实现滑动窗口熔断器、令牌桶限流、重试及降级机制。
- **生态兼容性**：在支持 JDK 17 / Spring Boot 3.x 的基础上，新增对 JDK 8 和 Spring Boot 2.7.x 的全量兼容支持。
- **开发效率提升**：
    - 新增 `dev-mode` 开发模式，支持宽松的运行时权限模型。
    - 开发模式下支持灵元安装后自动激活，无需手动操作。
    - 集成 SpringDoc (Swagger) 支持，实现 API 自动分组（核心、灵元、宿主）。

### 🛠 重构与优化

- **全局术语重构**：将所有“插件 (Plugin)”相关表述统一更名为“灵元 (Ling)”，将“宿主 (Host)”更名为“灵核 (LingCore)”，确保概念高度一致。
- **隔离性增强**：优化 `SmartServiceProxy` 和 `InvocationExecutor`，强化灵元边界审计能力。
- **基础设施 SPI**：针对 `StorageService` 和 `CacheService` 代理进行了稳定性优化。

### 🐛 问题修复

- **内存泄漏缓解**：通过系统性清理 Spring 缓存及 Jakarta EL/Objenesis 静态引用，尽可能缓解并优化了灵元热重载过程中的 ClassLoader 内存泄漏问题。
- **路径匹配**：修复了 Swagger 及 Web 接口映射中的多项路径匹配兼容性问题。

## [V0.1.0-Preview] - 2026-02-01

> **初版 (Preview)**：此版本验证了 JVM 进程内运行时治理的可行性。
> 核心关注点：边界、隔离与控制。

### 🚀 新特性

#### 核心架构 (JVM Runtime Governance)
- **三层 ClassLoader 架构**：实现了 `HostClassLoader` -> `SharedApiClassLoader` -> `LingClassLoader` 的层级结构，确保严格隔离的同时允许受控共享。
- **Child-First 类加载机制**：灵元优先加载自身依赖，防止与灵核应用发生“依赖地狱”。
- **Spring 上下文隔离**：每个灵元在独立的 Spring `ApplicationContext` 中运行，确保 Bean 隔离和独立的生命周期。

#### 灵元系统 (ling System)
- **生命周期管理**：通过 `LingManager` 全面支持 `LOAD`（加载）、`START`（启动）、`STOP`（停止）、`UNLOAD`（卸载）及热重载能力。
- **Manifest 配置**：定义了 `ling.yml` 标准，用于声明元数据、依赖项和所需能力。
- **服务导出/导入**：
  - `@LingService`：将 Bean 导出为跨边界的服务。
  - `@LingReference`：注入来自其他灵元或灵核的服务。

#### 治理与安全 (Governance & Security)
- **权限控制**：
  - 实现了 `GovernancePolicy` 用于定义访问控制列表 (ACL)。
  - 添加了 `@RequiresPermission` 用于细粒度的方法级授权。
- **审计与追踪**：
  - `@Auditable` 注解用于记录敏感操作。
  - `TraceContext` 用于跨灵元边界传播请求元数据。
- **流量路由**：
  - `LabelMatchRouter` 实现，支持灰度发布和基于标签的流量路由。

#### 仪表盘与运维 (Dashboard & Operations)
- **可视化管理**：提供基于 Web 的仪表盘（预览版），用于监控灵元状态和管理配置。
- **动态控制**：
  - 通过 UI/API 启动/停止灵元。
  - 无需重启 JVM 即可热重载灵元。
  - 运行时调整权限策略。

#### 基础设施 SPI (Infrastructure SPI)
- **代理抽象**：
  - `StorageService` 代理文件操作。
  - `CacheService` 代理缓存（本地/远程）。

### ⚠️ 技术边界与限制
- **仅限单进程**：专为单体应用改造设计，而非分布式微服务框架。
- **兼容性**：基于 JDK 17 (LTS) 和 Spring Boot 3.x 构建。
- **待实现功能** (Phase 3)：熔断 (Circuit Breaking)、限流 (Rate Limiting) 和降级 (Fallback) 机制已定义但尚未完全投入使用。

### 🛠 基础设施
- 建立了标准的 Maven 多灵元项目结构 (`core`, `api`, `dashboard`, `runtime`, `infrastructure`)。
- 集成了 `maven-compiler-Ling` 和 `flatten-maven-Ling` 以标准化构建。
