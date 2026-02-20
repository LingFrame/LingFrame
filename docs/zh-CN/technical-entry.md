# 灵珑 (LingFrame)

**让 JVM 应用具备操作系统般的控制和治理能力**

> 🟢 **核心框架已实现** — 权限治理、审计追踪、能力仲裁、单元隔离等核心功能已可用。

---

## 📖 什么是 LingFrame？

**LingFrame（灵珑）** 是一个 **JVM 运行时治理框架**，专注于解决 Java 应用中单元间调用的**权限控制**、**审计追踪**和**能力仲裁**问题。

> ⚠️ 我们使用单元化隔离作为治理的技术手段，核心价值在于**运行时治理能力**——确保每一次跨单元调用都经过权限校验和审计记录。

**核心能力**：**权限治理** · **审计追踪** · **能力仲裁** · **单元隔离**

---

## ✅ 核心治理能力

| 能力                  | 说明                                      | 核心类                                |
| --------------------- | ----------------------------------------- | ------------------------------------- |
| **权限治理**          | 智能推导 + `@RequiresPermission` 显式声明，所有调用必须经过鉴权 | `GovernanceKernel`, `GovernanceStrategy` |
| **审计追踪**          | `@Auditable` 注解 + 异步审计日志，完整调用链记录 | `AuditManager`                        |
| **能力仲裁**          | Core 作为唯一仲裁者，代理所有跨单元调用   | `ServiceRegistry`, `SmartServiceProxy` |
| **服务路由**          | `@LingService` + `@LingReference` 实现 FQSID 路由 | `LingReferenceInjector`, `GlobalServiceRoutingProxy` |
| **单元隔离**          | 三层 ClassLoader + Spring 父子上下文      | `SharedApiClassLoader`, `LingClassLoader`, `SpringLingContainer` |
| **热重载**            | 蓝绿部署 + 文件监听，无需重启应用         | `LingManager`, `InstancePool`, `HotSwapWatcher` |

---

## 🎯 我们要解决什么问题

| 痛点                   | 现状困境                              | LingFrame 方案             |
| :--------------------- | :------------------------------------ | :------------------------- |
| **调用缺乏鉴权**       | 单元间直接调用，无权限校验            | 所有调用经 Core 代理鉴权   |
| **操作无法追溯**       | 出问题后难以定位调用链                | 内置审计日志，完整调用追踪 |
| **单元边界模糊**       | 扩展逻辑与内核高度耦合                | 三层架构 + 上下文隔离      |
| **缺乏统一治理点**     | 业务单元可直接操作 DB/Redis 等资源    | 基础设施访问统一仲裁       |

---

## 👤 适用场景

| 场景                   | 典型需求                                        |
| ---------------------- | ----------------------------------------------- |
| **企业级应用**         | 需要细粒度权限控制和完整审计追踪                |
| **多单元协作系统**     | 单元间调用需要统一治理和边界隔离                |
| **二次开发平台**       | 需要对第三方代码进行权限限制和行为审计          |
| **SaaS 多租户系统**    | 不同租户的功能单元需要隔离和按需加载            |
| **大型系统单元化改造** | 将单体应用拆分为可独立演进、边界清晰的单元      |

---

## 💡 核心理念：治理架构

```text
┌─────────────────────────────────────────────────────────┐
│                    Core（治理内核）                      │
│        权限仲裁 · 审计记录 · 能力调度 · 上下文隔离        │
└────────────────────────────────┬────────────────────────┘
                                 ▼
┌─────────────────────────────────────────────────────────┐
│               Infrastructure（基础设施层）               │
│    存储代理 · 缓存代理 · 消息代理 · 搜索代理             │
└────────────────────────────────┬────────────────────────┘
                                 ▼
┌─────────────────────────────────────────────────────────┐
│                  Business（业务单元层）                  │
│              用户中心 · 订单服务 · 支付单元               │
└─────────────────────────────────────────────────────────┘
```

**关键设计原则**：

1. **Core 是唯一仲裁者**：不提供业务能力，只负责权限校验、审计记录与调用代理
2. **零信任调用**：所有跨单元调用必须经过 Core 代理与鉴权，无法绕过
3. **完整审计链**：每一次调用都有迹可循，支持问题追溯和合规审计

---

## 🚀 快速开始

### 环境要求

- Java 17+
- Maven 3.8+

### 构建项目

```bash
# 克隆仓库（选择任意仓库）
# AtomGit（推荐）
git clone https://atomgit.com/lingframe/LingFrame.git

# Gitee（国内镜像）
git clone https://gitee.com/knight6236/lingframe.git

# GitHub（国际）
git clone https://github.com/LingFrame/LingFrame.git

cd LingFrame

# 编译安装
mvn clean install -DskipTests

# 运行示例灵核应用
cd lingframe-examples/lingframe-example-lingcore-app
mvn spring-boot:run
```

### 灵核应用配置

在 `application.yaml` 中配置 LingFrame：

```yaml
lingframe:
  enabled: true
  dev-mode: true                    # 开发模式，权限不足时仅警告
  ling-home: "lings"            # 单元 JAR 包目录
  ling-roots:                     # 单元源码目录（开发模式）
    - "../my-ling"
  auto-scan: true
  
  audit:
    enabled: true
    log-console: true
    queue-size: 1000
  
  runtime:
    default-timeout: 3s
    bulkhead-max-concurrent: 10
```

### 创建业务单元

LingFrame 采用**消费者驱动契约**：消费者定义接口，生产者实现接口。

```java
// ========== 消费者（Order 单元）定义它需要的接口 ==========
// 位置：order-api/src/main/java/.../UserQueryService.java
public interface UserQueryService {
    Optional<UserDTO> findById(String userId);
}

// ========== 生产者（User 单元）实现消费者定义的接口 ==========
// 位置：user-ling/src/main/java/.../UserQueryServiceImpl.java
@SpringBootApplication
public class UserLing implements Ling {
    @Override
    public void onStart(LingContext context) {
        System.out.println("Ling started: " + context.getLingId());
    }
}

@Component
public class UserQueryServiceImpl implements UserQueryService {
    
    @LingService(id = "find_user", desc = "查询用户")
    @Override
    public Optional<UserDTO> findById(String userId) {
        return userRepository.findById(userId).map(this::toDTO);
    }
}
```

单元元数据 `ling.yml`：

```yaml
id: user-ling
version: 1.0.0
provider: "My Company"
description: "用户单元"
mainClass: "com.example.UserLing"

governance:
  permissions:
    - methodPattern: "storage:sql"
      permissionId: "READ"
```

### 跨单元服务调用（经治理内核代理）

```java
// 方式一：@LingReference 注入（强烈推荐）
// Order 单元使用自己定义的接口，由 User 单元实现
@Component
public class OrderService {
    
    @LingReference
    private UserQueryService userQueryService;  // 框架自动路由到 User 单元的实现
    
    public Order createOrder(String userId) {
        // 此调用会经过 Core 权限校验和审计记录
        UserDTO user = userQueryService.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        return new Order(user);
    }
}

// 方式二：LingContext.getService()
Optional<UserQueryService> service = context.getService(UserQueryService.class);

// 方式三：FQSID 协议调用
Optional<UserDTO> user = context.invoke("user-ling:find_user", userId);
```

---

## 📦 项目结构

```
lingframe/
├── lingframe-api/              # 契约层（接口、注解、异常）
├── lingframe-core/             # 治理内核（权限、审计、单元管理）
├── lingframe-runtime/          # 运行时集成
│   └── lingframe-spring-boot3-starter/  # Spring Boot 3.x 集成
├── lingframe-infrastructure/   # 基础设施层
│   ├── lingframe-infra-storage/   # 存储代理，SQL 级权限
│   └── lingframe-infra-cache/     # 缓存代理
├── lingframe-examples/         # 示例
│   ├── lingframe-example-lingcore-app/     # 灵核应用
│   ├── lingframe-example-ling-user/  # 用户单元
│   └── lingframe-example-ling-order/ # 订单单元
├── lingframe-dependencies/     # 依赖版本管理
└── lingframe-bom/              # 对外提供的 BOM
```

---

## 🆚 为什么不是其他方案？

> LingFrame 的核心价值不是单元化本身，而是**运行时治理**。以下对比聚焦于治理能力。

| 治理能力        | OSGi     | Java SPI | PF4J       | **LingFrame**     |
| :-------------- | :------- | :------- | :--------- | :---------------- |
| **细粒度权限**  | 有但复杂 | 无       | 无         | ✅ 核心特性       |
| **调用链审计**  | 需扩展   | 无       | 无         | ✅ 内置支持       |
| **能力仲裁**    | 服务注册 | 无       | 扩展点     | ✅ Core 强制代理  |
| **Spring 原生** | 需适配   | 手动     | 需额外工作 | ✅ 父子上下文     |
| **定位**        | 单元化   | 扩展点   | 单元系统   | **运行时治理**    |

---

## 📍 路线图

| 阶段        | 目标                                                | 状态          |
| :---------- | :-------------------------------------------------- | :------------ |
| **Phase 1** | 核心治理：权限、审计、单元隔离                      | ✅ **已完成** |
| **Phase 2** | 可视化：Dashboard 治理中心                          | ✅ **基本完成** |
| **Phase 3** | 弹性治理：熔断、降级、重试、限流                    | 🔄 进行中     |
| **Phase 4** | 可观测性：指标采集、调用链可视化                    | ⏳ 计划中     |
| **Phase 5** | 基础设施扩展：消息代理、搜索代理                    | ⏳ 计划中     |

---

## 📚 文档

- [快速入门](getting-started.md) - 5 分钟上手
- [单元开发指南](ling-development.md) - 开发业务单元
- [共享 API 设计规范](shared-api-guidelines.md) - API 设计最佳实践
- [基础设施层开发](infrastructure-development.md) - 开发基础设施代理
- [Dashboard](dashboard.md) - 可视化治理中心
- [架构设计](architecture.md) - 深入了解治理原理
- [路线图](roadmap.md) - 演进计划

---

## 👥 参与贡献

我们非常欢迎社区参与：

1. **功能开发**：查看 [Issues](../../issues) 认领任务
2. **架构讨论**：在 [Discussions](../../discussions) 发起话题
3. **文档完善**：帮助改进文档、编写教程
4. **测试补充**：为核心单元补充单元测试

详见 [贡献指南](../../CONTRIBUTING.md)

⭐ **Star** 本仓库，关注我们的每一步成长。

---

## 📄 许可证

本项目采用 **Apache License 2.0** 授权协议。
