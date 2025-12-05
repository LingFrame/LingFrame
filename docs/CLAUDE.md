# CLAUDE.md

本文件为 Claude Code（claude.ai/code）在本仓库中工作的指导说明。

## 项目概览

**LingFrame（灵珑）** 是一个基于 JVM 的下一代微内核插件框架，目前处于早期开发阶段（v0.0.1）。项目目标是为现代 Java 应用构建一个可控、可扩展、可演化的运行时系统，具备类似操作系统的插件能力。

**当前状态**：架构设计阶段——已建立基础的 Maven 结构，最小可运行代码。

**愿景**：通过严格的分层实现具备操作系统式插件模型与可控能力的 JVM 应用：核心（仲裁）、基础设施插件（存储/缓存/消息）、业务插件。

## 技术栈

- **Java**：21（必需）
- **构建工具**：Maven（多模块项目）
- **Spring**：规划集成 Spring Boot 3（lingframe-spring-boot3-starter）
- **仓库**：已配置阿里云 Maven 镜像

## 构建命令

### 编译项目
```bash
mvn clean compile
```

### 运行测试
```bash
mvn test
```

### 打包
```bash
mvn clean package
```

### 安装到本地仓库
```bash
mvn clean install
```

### 构建指定模块
```bash
cd lingframe-core
mvn clean install
```

## 项目结构

这是一个 Maven 多模块项目，包含以下关键模块：

### 核心模块
- **lingframe-api**：插件 API 定义与契约，供插件实现
- **lingframe-core**：核心仲裁引擎——生命周期管理、权限治理、能力调度、上下文隔离
- **lingframe-bom**：统一的依赖管理清单（Bill of Materials）
- **lingframe-dependencies**：集中式依赖版本管理

### 运行时集成
- **lingframe-runtime**：运行时集成层
  - **lingframe-spring-boot3-starter**：Spring Boot 3 自动配置与父子上下文集成

### 基础设施插件
- **lingframe-plugins-infra**：基础设施插件的基础实现
  - **lingframe-plugin-storage**：存储能力插件（数据库访问）
  - **lingframe-plugin-cache**：缓存能力插件（Redis/内存）

### 示例
- **lingframe-samples**：示例应用展示框架使用
  - **lingframe-sample-host-app**：加载和管理插件的宿主应用
  - **lingframe-sample-plugin-user**：示例业务插件（用户管理）

## 架构原则

### 三层架构
框架强制分层，严格隔离：

1. **核心层**：充当唯一仲裁者——不提供任何业务能力，仅负责调度、隔离与权限控制
2. **基础设施插件层**：提供底层能力（存储、缓存、消息），由核心层进行中介
3. **业务插件层**：实现领域逻辑——必须通过核心层代理访问基础设施（零信任模型）

### 关键设计约束
- **业务插件零信任**：业务插件不可直接访问基础设施（DB/Redis），所有能力调用必须通过核心层代理并进行鉴权
- **Spring 上下文隔离**：采用 Spring 父子上下文模式实现插件隔离，以支持干净卸载
- **权限治理**：核心层对所有能力调用进行权限校验

## 开发指南

### 模块依赖
新增依赖时：
- 在 `lingframe-dependencies/pom.xml` 中添加版本管理
- 在 `lingframe-bom/pom.xml` 中通过属性引用版本
- 各模块应从 BOM 中导入版本

### 插件开发模式
插件必须：
1. 声明元数据（权限、所需能力）
2. 实现来自 lingframe-api 的插件生命周期接口
3. 仅通过核心层提供的代理访问基础设施
4. 支持热重载与干净卸载

### Spring 集成
- 宿主应用使用 `lingframe-spring-boot3-starter` 进行自动配置
- 每个插件获得独立的子 Spring ApplicationContext
- 核心层管理父上下文，并在插件上下文之间进行访问中介

### 版本管理
当前版本为 `0.0.1`，在根 `pom.xml` 中定义为 `${revision}`。所有模块继承该版本。

## 常见问题

### Java 版本
项目要求 Java 21。确保 `JAVA_HOME` 指向 JDK 21：
```bash
java -version  # 应显示版本 21
```

### Maven 构建失败
如遇到解析问题，项目使用阿里云 Maven 镜像。请验证网络连接，或临时切换到 Maven Central。

### 模块解析
在进行跨模块修改时：
1. 先构建依赖（对 lingframe-api 执行 `mvn install`，再构建 lingframe-core）
2. 或从根目录执行 `mvn clean install`，以自动处理构建顺序

## 贡献者须知

本项目处于**阶段 0（设计与讨论）**：
- 多数模块仅包含占位代码（App.java、AppTest.java）
- 当前关注点是建立架构模式与契约
- 正在进行关于插件元数据格式、权限模型与 Spring 上下文集成的 RFC 讨论
- 规划下一步进行 v0.1.0-alpha 原型开发

实现新特性时优先考虑：
1. 遵循三层架构
2. 保持核心仲裁与能力提供的清晰分离
3. 确保插件隔离与完整生命周期管理
4. 遵循 Spring Boot 3 的约定以开发 starter 模块
