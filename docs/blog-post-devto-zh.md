---
title: "给遗留 Java 单体应用装上「护甲」：我的开源零信任治理框架 LingFrame"
published: false
description: "如何在不重写代码的情况下，为你那跑了十年的 Spring Boot 应用引入零信任、插件隔离和可观测性。"
tags: java, opensource, security, zerotrust, architecture, jvm, springboot
cover_image: https://your-cover-image-url.com/cover.png
---

## 一个没人想承认的问题 🦖

说实话。

你手上有一个 Java 巨石单体。它已经跑了好几年了。它在赚钱。它全靠祈祷和胶带维系着。

每当你需要接入一个新团队或集成一个第三方脚本时，你都会想：*"万一这段代码调用了它不该调用的东西怎么办？万一这个 DTO 把敏感数据泄露了怎么办？万一我想灰度测试这个新模块，又不想全量重启怎么办？"*

通常的建议是：「重构成微服务吧！」对啊，让我暂停业务 18 个月，把 50 万行代码拆掉重写就好了嘛。🙃

**这就是我的痛点。** 我相信一定有一种中间路线——一种在不推翻一切的前提下，将 **运行时治理** 植入现有 JVM 进程的方法。

所以，我造了 **[LingFrame（灵珑）](https://github.com/LingFrame/LingFrame)**。

---

## LingFrame 是什么？🛡️

LingFrame 是一个面向长期运行 Java 应用的 **JVM 运行时治理框架**。

你可以把它想象成在你的进程 *内部* 装上护栏。它不会取代你的架构，而是让你现有的单体变得 *可治理*。

### 核心能力（你应该关心的点）：

| 能力                        | 对你的意义                                                               |
| :-------------------------- | :----------------------------------------------------------------------- |
| **三层 ClassLoader 隔离**   | 每个业务模块都有自己的沙箱，彻底告别「Jar Hell」依赖冲突。               |
| **基于能力（Capability）的安全模型** | 插件必须 *声明* 它需要什么（`storage:sql`, `cache:local`），其他一切默认拒绝。 |
| **`@LingService` 和 `@LingReference`** | 通过简单注解，跨插件边界暴露和消费服务。                                 |
| **审计与追踪 (`@Auditable`)**  | 每个敏感操作都会被记录。谁、在什么时候、对什么资源、做了什么。            |
| **金丝雀（Canary）发布**    | 在 *不重启 JVM* 的情况下，将新版本插件的流量灰度到部分用户。             |

---

## 给我看代码（5 分钟演示）⏱️

### 第一步：添加 Starter 依赖

在你的 **宿主应用** `pom.xml` 中：

```xml
<dependency>
    <groupId>com.lingframe</groupId>
    <artifactId>lingframe-spring-boot3-starter</artifactId>
    <version>0.1.0-Preview</version>
</dependency>
```

### 第二步：配置 LingFrame

在 `application.yaml` 中：

```yaml
lingframe:
  enabled: true
  dev-mode: true  # 开发模式下仅告警，不阻断

  # 生产环境插件 JAR 包目录
  plugin-home: plugins

  # 开发环境可以直接指向源码目录
  plugin-roots:
    - ../my-user-plugin
    - ../my-order-plugin

  # 共享 API (宿主与插件之间的接口层)
  preload-api-jars:
    - shared-api/order-api.jar

  # 启用可视化 Dashboard
  dashboard:
    enabled: true
```

### 第三步：在 `plugin.yml` 中声明插件能力

每个插件都必须 *声明* 它打算访问什么。这是「零信任」的核心。

```yaml
# 位于插件的 resources/plugin.yml
id: user-plugin
version: 1.0.0
mainClass: com.example.user.UserPluginApplication

governance:
  capabilities:
    - capability: "storage:sql"
      accessType: "WRITE"       # 我需要对数据库进行读写
    - capability: "cache:local"
      accessType: "WRITE"       # 我需要使用本地缓存
    - capability: "ipc:order-plugin"
      accessType: "EXECUTE"     # 我需要调用订单插件的服务
```

### 第四步：用注解标记你的服务

你的插件代码长这样：

```java
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final JdbcTemplate jdbcTemplate;

    @LingService(id = "query_user", desc = "根据ID查询用户")
    @RequiresPermission(Capabilities.STORAGE_SQL) // 声明需要数据库访问权限
    @Cacheable(value = "users", key = "#userId")   // 同时使用缓存
    @Auditable(action = "QUERY_USER", resource = "user") // 审计这个操作
    @Override
    public Optional<UserDTO> queryUser(String userId) {
        log.info("缓存未命中，查询用户 {}", userId);
        String sql = "SELECT * FROM t_user WHERE id = ?";
        // ... jdbcTemplate 查询 ...
        return Optional.ofNullable(user);
    }

    @LingService(id = "create_user", desc = "创建新用户")
    @RequiresPermission(Capabilities.STORAGE_SQL)
    @Auditable(action = "CREATE_USER", resource = "user")
    @Override
    public UserDTO createUser(String name, String email) {
        // ... INSERT SQL ...
    }
}
```

### 第五步：用 `@LingReference` 实现跨插件调用

需要在 `order-plugin` 里调用 `user-plugin` 的服务？别用 `@Autowired`，用 `@LingReference`：

```java
@Service
public class OrderServiceImpl implements OrderService {

    @LingReference // LingFrame 会自动路由到 'user-plugin'
    private UserQueryService userQueryService;

    @LingService(id = "get_order", desc = "根据ID查询订单")
    public OrderDTO getOrderById(Long orderId) {
        // ... 从数据库获取订单 ...
        
        // 跨插件调用 (IPC)
        userQueryService.findById(userId).ifPresent(
            user -> order.setUserName(user.getName())
        );
        return order;
    }
}
```

---

## 见证治理的力量 🔥

### 场景：未授权的写入尝试

假设一个插件在 `plugin.yml` 中只声明了 `READ` 权限，却试图执行一条 `INSERT` 语句。

```bash
curl -X POST "http://localhost:8888/user/createUser?name=Hacker&email=h@ck.er"
```

**结果**：LingFrame 将其阻断。

```text
c.l.core.exception.PermissionDeniedException: 
Plugin [user-plugin] requires [storage:sql] with [WRITE] access, 
but only allowed: [READ]
```

插件被安全地阻断，宿主应用继续运行，事件已被审计记录。

### 可视化 Dashboard

LingFrame 内置了 Dashboard。你可以看到：
- 实时插件状态（RUNNING, STOPPED, CANARY）
- 金丝雀发布的流量切分比例
- 实时审计日志流

![LingFrame Dashboard](https://your-image-url.com/dashboard.png)

---

## 为什么不直接用 [OSGi / Spring Cloud / 其他]？

| 框架 | 代价 |
|------|------|
| **OSGi** | 功能强大，但学习曲线 *极其陡峭*，会彻底改变你的开发流程。 |
| **Spring Cloud / K8s** | 通过拆分 *进程* 来解决隔离。这意味着网络跳转、序列化开销和基础设施复杂性。 |
| **LingFrame** | 进程内隔离。微服务般的边界，但享有本地方法调用的性能。 |

LingFrame 适合那些 **现在无法** 进行全面微服务拆分，但 **今天就需要** 更好治理的团队。

---

## 当前状态与路线图 🗺️

LingFrame 目前处于 **v0.1.x 预览版**。方向已定，核心循环仍在验证中。

**已完成：**
- [x] 三层 ClassLoader 隔离
- [x] 基于能力的权限模型
- [x] Spring Boot 3 集成
- [x] 内置可视化 Dashboard
- [x] 金丝雀发布支持

**下一步：**
- [ ] JDK 8 / Spring Boot 2.x 兼容层
- [ ] 弹性治理（熔断、限流、重试）
- [ ] 可观测性（指标、分布式链路追踪导出）

---

## 来试试吧 🚀

如果你正在和一个老化的单体搏斗，梦想着更好的治理，看看 LingFrame 吧。

👉 **[GitHub: LingFrame/LingFrame](https://github.com/LingFrame/LingFrame)** ⭐

**快速开始：**
```bash
git clone https://github.com/LingFrame/LingFrame.git
cd LingFrame
mvn clean install -DskipTests
cd lingframe-examples/lingframe-example-host-app
mvn spring-boot:run
```
然后访问 `http://localhost:8888/dashboard.html` 查看 Dashboard。

---

**如果你觉得这个想法有意思，请 Star 支持一下！** ⭐

在评论区聊聊：你遇到过的最混乱的遗留系统是什么样的？你是怎么处理不可信代码的？

*(这是我「JVM 运行时治理」系列的第一篇。下一篇预告：深入剖析我们的三层 ClassLoader 架构！)*

---
系列: #lingframe-series
#java #opensource #security #zerotrust #springboot #architecture
