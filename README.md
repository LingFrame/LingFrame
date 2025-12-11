# 灵珑 (LingFrame)

![Status](https://img.shields.io/badge/Status-Core_Implemented-green)
![License](https://img.shields.io/badge/License-Apache_2.0-blue)
![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.6-brightgreen)
[![Help Wanted](https://img.shields.io/badge/PRs-welcome-brightgreen)](../../pulls)

> 🟢 **项目状态：核心框架已实现**
>
> 插件管理、热重载、权限治理、Spring Boot 3 集成等核心功能已可用。
> 部分基础设施插件和单元测试仍在完善中。

---

## 📖 愿景

**LingFrame** 是一个基于 JVM 的新一代微内核插件化框架。
我们致力于为现代 Java 应用构建一个**可控、可扩展、可演进**的运行时体系。

> **一句话愿景**：让 JVM 应用具备如同操作系统般的插件模型和可控能力。

---

## ✅ 已实现的核心能力

| 能力                  | 说明                                      | 核心类                        |
| --------------------- | ----------------------------------------- | ----------------------------- |
| **插件生命周期**      | 安装、卸载、热重载，支持蓝绿部署          | `PluginManager`, `PluginSlot` |
| **类隔离**            | Child-First 类加载器 + 白名单委派         | `PluginClassLoader`           |
| **Spring 上下文隔离** | 每个插件独立的父子 ApplicationContext     | `SpringPluginContainer`       |
| **服务扩展**          | `@LingService` 注解实现 FQSID 路由        | `SmartServiceProxy`           |
| **权限治理**          | 智能推导 + `@RequiresPermission` 显式声明 | `GovernanceStrategy`          |
| **审计追踪**          | `@Auditable` 注解 + 异步审计日志          | `AuditManager`                |
| **开发模式**          | 文件监听 + 防抖热重载                     | `HotSwapWatcher`              |

---

## 🎯 我们要解决什么问题

| 痛点                 | 现状困境                    | LingFrame 方案         |
| :------------------- | :-------------------------- | :--------------------- |
| **扩展能力缺乏边界** | 扩展逻辑与内核高度耦合      | 三层架构 + 上下文隔离  |
| **缺乏安全仲裁**     | 业务模块可直接操作 DB/Redis | 零信任 + Core 代理鉴权 |
| **动态加载能力不足** | 只能启动时加载              | 蓝绿部署 + 热重载      |

---

## 👤 目标用户

| 场景                   | 典型需求                                        |
| ---------------------- | ----------------------------------------------- |
| **可扩展产品**         | 核心产品 + 功能插件模式（IDE、CMS、低代码平台） |
| **二次开发平台**       | 提供标准内核，允许第三方开发者扩展功能          |
| **SaaS 多租户定制**    | 不同租户启用不同功能模块，按需加载              |
| **插件市场**           | 用户可自行安装、卸载、升级功能模块              |
| **大型系统模块化改造** | 将单体应用拆分为可独立演进的插件                |

---

## 💡 核心理念：三层架构

```text
┌─────────────────────────────────────────────────────────┐
│                    Core（仲裁核心）                      │
│         生命周期管理 · 权限治理 · 能力调度 · 上下文隔离    │
└────────────────────────────┬────────────────────────────┘
                             ▼
┌─────────────────────────────────────────────────────────┐
│           Infrastructure Plugins（基础设施层）           │
│              存储 · 缓存 · 消息 · 搜索                   │
└────────────────────────────┬────────────────────────────┘
                             ▼
┌─────────────────────────────────────────────────────────┐
│             Business Plugins（业务层）                   │
│              用户中心 · 订单服务 · 支付模块               │
└─────────────────────────────────────────────────────────┘
```

**关键设计原则**：

1. **Core 是唯一仲裁者**：不提供业务能力，只负责调度、隔离与权限控制
2. **业务插件"零信任"**：所有能力调用必须经过 Core 代理与鉴权
3. **Spring 上下文隔离**：基于父子上下文实现插件隔离，确保卸载后无残留

---

## 🚀 快速开始

### 环境要求

- Java 21+
- Maven 3.8+

### 构建项目

```bash
# 克隆仓库
git clone https://github.com/lingframe/lingframe.git
cd lingframe

# 编译安装
mvn clean install -DskipTests

# 运行示例宿主应用
cd lingframe-samples/lingframe-sample-host-app
mvn spring-boot:run
```

### 创建插件

1. 创建 Maven 模块，依赖 `lingframe-api`
2. 实现 `LingPlugin` 接口
3. 使用 `@LingService` 暴露服务

```java
// 插件入口
@SpringBootApplication
public class MyPlugin implements LingPlugin {
    @Override
    public void onStart(PluginContext context) {
        System.out.println("Plugin started: " + context.getPluginId());
    }
}

// 暴露服务
@Component
public class UserService {
    @LingService(id = "query_user", desc = "查询用户")
    public User queryUser(String userId) {
        return userRepository.findById(userId);
    }
}
```

### 调用其他插件服务

```java
// 通过 FQSID 调用
Optional<User> user = context.invoke("user-plugin:query_user", userId);

// 通过接口类型获取
Optional<UserService> service = context.getService(UserService.class);
```

---

## 📦 模块结构

```
lingframe/
├── lingframe-api/          # 核心契约层（接口、注解）
├── lingframe-core/         # 框架实现（插件管理、权限、代理）
├── lingframe-bom/          # 依赖版本清单
├── lingframe-dependencies/ # 版本管理父 POM
├── lingframe-runtime/
│   └── lingframe-spring-boot3-starter/  # Spring Boot 3 集成
├── lingframe-plugins-infra/             # 基础设施插件（三层架构中间层）
│   ├── lingframe-plugin-storage/  # 存储插件：SQL 级权限控制
│   └── lingframe-plugin-cache/    # 缓存插件（待实现）
└── lingframe-samples/
    ├── lingframe-sample-host-app/    # 宿主应用示例
    └── lingframe-sample-plugin-user/ # 示例业务插件
```

### 三层架构对应

| 层级               | 模块                        | 职责                             |
| ------------------ | --------------------------- | -------------------------------- |
| **Core**           | `lingframe-core`            | 仲裁核心：生命周期、权限、调度   |
| **Infrastructure** | `lingframe-plugins-infra/*` | 基础设施：存储、缓存、消息       |
| **Business**       | 用户插件                    | 业务逻辑：通过 Core 访问基础设施 |

---

## 🆚 与现有方案的差异

| 特性            | OSGi     | Java SPI | PF4J       | LingFrame         |
| :-------------- | :------- | :------- | :--------- | :---------------- |
| **权限治理**    | 有但复杂 | 无       | 无         | ✅ 核心特性       |
| **能力仲裁**    | 服务注册 | 无       | 扩展点     | ✅ Core 代理      |
| **Spring 集成** | 需适配   | 手动     | 需额外工作 | ✅ 原生父子上下文 |
| **热插拔**      | ✅       | ❌       | ✅         | ✅ 蓝绿部署       |
| **调用链审计**  | 需扩展   | 无       | 无         | ✅ 内置支持       |
| **学习曲线**    | 陡峭     | 平缓     | 中等       | 中等              |

---

## 📍 路线图

| 阶段        | 目标                                                | 状态          |
| :---------- | :-------------------------------------------------- | :------------ |
| **Phase 1** | 核心框架：插件生命周期、权限治理、Spring 集成       | ✅ **已完成** |
| **Phase 2** | 能力增强：策略引擎、状态句柄化、plugin.yml 配置加载 | 🔄 进行中     |
| **Phase 3** | 生态完善：缓存/消息插件、单元测试、文档完善         | ⏳ 计划中     |
| **Phase 4** | 平台化：字节码治理、行为沙箱、可观测性              | ⏳ 远期规划   |

---

## 📚 文档

- [快速入门](docs/getting-started.md) - 5 分钟上手
- [插件开发指南](docs/plugin-development.md) - 开发业务插件
- [基础设施插件](docs/infrastructure-plugins.md) - 开发基础设施插件
- [架构设计](docs/architecture.md) - 深入了解框架原理
- [API 参考](docs/api-reference.md) - 完整 API 文档
- [路线图](docs/roadmap.md) - 长期愿景与演进计划

---

## 👥 参与贡献

我们非常欢迎社区参与：

1. **功能开发**：查看 [Issues](../../issues) 认领任务
2. **架构讨论**：在 [Discussions](../../discussions) 发起话题
3. **文档完善**：帮助改进文档、编写教程
4. **测试补充**：为核心模块补充单元测试

### 贡献步骤

1. Fork 本仓库
2. 创建特性分支：`git checkout -b feature/xxx`
3. 提交代码：`git commit -m 'Add xxx'`
4. 推送分支：`git push origin feature/xxx`
5. 提交 Pull Request

⭐ **Star** 本仓库，关注我们的每一步成长。

---

## 📄 许可证

本项目采用 **Apache License 2.0** 授权协议。

**灵珑 (LingFrame) —— 让 JVM 应用具备操作系统般的插件能力。**
