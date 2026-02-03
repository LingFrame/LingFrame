# 快速入门

本文档帮助你快速了解和使用 LingFrame。

## 环境准备

- JDK 21+
- Maven 3.8+

## 5 分钟 Hello World

### 1. 构建项目

```bash
git clone https://github.com/lingframe/lingframe.git
cd lingframe
mvn clean install -DskipTests
```

### 2. 启动示例应用

```bash
cd lingframe-examples/lingframe-example-host-app
mvn spring-boot:run
```

### 3. 测试插件服务

```bash
# 查询用户列表（user-plugin 提供的服务）
curl http://localhost:8888/user/listUsers

# 查询单个用户
curl "http://localhost:8888/user/queryUser?userId=1"
```

恭喜！你已经成功运行了第一个 LingFrame 应用！

---

## 核心概念

### 三层架构

```
┌─────────────────────────────────────────┐
│           Core（治理内核）               │
│   权限校验 · 审计记录 · 上下文隔离        │
└─────────────────┬───────────────────────┘
                  ▼
┌─────────────────────────────────────────┐
│      Infrastructure（基础设施层）        │
│          存储 · 缓存 · 消息              │
└─────────────────┬───────────────────────┘
                  ▼
┌─────────────────────────────────────────┐
│         Business（业务模块层）           │
│      用户中心 · 订单服务 · 支付模块       │
└─────────────────────────────────────────┘
```

### 关键原则

1. **零信任**：业务模块只能通过 Core 访问基础设施
2. **上下文隔离**：每个模块独立的 Spring 子上下文
3. **FQSID 路由**：服务通过 `pluginId:serviceId` 全局唯一标识

---

## 创建宿主应用

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.lingframe</groupId>
    <artifactId>lingframe-spring-boot3-starter</artifactId>
    <version>${lingframe.version}</version>
</dependency>
```

### 2. 启动类

```java
@SpringBootApplication
public class HostApplication {
    public static void main(String[] args) {
        SpringApplication.run(HostApplication.class, args);
    }
}
```

### 3. 配置文件

参考示例应用 `application.yaml`：

```yaml
server:
  port: 8888

lingframe:
  enabled: true
  dev-mode: true                    # 开发模式，权限不足时仅警告
  
  # 共享 API 预加载（支持目录/JAR/通配符）
  preload-api-jars:
    - lingframe-examples/lingframe-example-order-api
  
  # 插件目录
  plugin-home: plugins              # 生产模式：JAR 包目录
  plugin-roots:                     # 开发模式：源码目录
    - lingframe-examples/lingframe-example-plugin-order
    - lingframe-examples/lingframe-example-plugin-user
  
  # 宿主治理（可选）
  host-governance:
    enabled: false

# 高阶功能：可视化仪表盘（可选，默认关闭）
# dashboard:
#   enabled: true
```

### 4. 在宿主中调用插件服务

使用 `@LingReference` 自动注入插件服务。LingFrame 采用**消费者驱动契约**：

```java
// 宿主应用（消费者）定义它需要的接口
// 位置：host-api/.../OrderQueryService.java
public interface OrderQueryService {
    List<OrderDTO> findByUserId(Long userId);
}

// Order 插件（生产者）实现宿主定义的接口
// 位置：order-plugin/.../OrderQueryServiceImpl.java
@Component
public class OrderQueryServiceImpl implements OrderQueryService {
    @LingService(id = "find_orders_by_user", desc = "查询用户订单")
    @Override
    public List<OrderDTO> findByUserId(Long userId) {
        return orderRepository.findByUserId(userId);
    }
}

// 宿主应用使用自己定义的接口
@RestController
@RequiredArgsConstructor
public class OrderController {

    @LingReference
    private OrderQueryService orderQueryService;  // 框架自动路由到 Order 插件

    @GetMapping("/orders/user/{userId}")
    public List<OrderDTO> getUserOrders(@PathVariable Long userId) {
        return orderQueryService.findByUserId(userId);
    }
}
```

---

## 示例项目结构

```
lingframe-examples/
├── lingframe-example-host-app        # 宿主应用
├── lingframe-example-order-api       # 共享 API（消费者定义的接口）
├── lingframe-example-plugin-order    # 订单模块
└── lingframe-example-plugin-user     # 用户模块（提供 /user/* 接口）
```

## 创建业务模块

详见 [模块开发指南](plugin-development.md)

## 安全治理演示（Killer Feature）

示例工程已内置了治理策略，你可以通过以下步骤体验 LingFrame 核心的**零信任治理**能力。

### 1. 尝试非法写入（SQL 拦截）

User 插件在配置文件 `plugin.yml` 中声明了对数据库的 `READ` 权限，但没有声明 `WRITE` 权限。

调用创建用户接口（执行 INSERT SQL）：

```bash
curl -X POST "http://localhost:8888/user/createUser?name=Attacker&email=hacker@test.com"
```

**预期结果**：
- HTTP 500 Internal Server Error
- 观察控制台日志，可见核心拦截异常：

```text
c.l.core.exception.PermissionDeniedException: Plugin [user-plugin] requires [storage:sql] with [WRITE] access, but only allowed: [READ]
```

### 2. 体验缓存加速（Cache 代理）

User 插件声明了 `cache:local` 的 `WRITE` 权限。

**第一次查询**（触发 SQL 查询并写入缓存）：

```bash
curl "http://localhost:8888/user/queryUser?userId=1"
```

观察日志：
```text
Executing SQL: SELECT * FROM t_user WHERE id = ?
Cache PUT: users::1
Audit: Plugin [user-plugin] accessed [storage:sql] (ALLOWED)
```

**第二次查询**（命中缓存，无 SQL）：

```bash
curl "http://localhost:8888/user/queryUser?userId=1"
```

观察日志：
```text
Cache HIT: users::1
Audit: Plugin [user-plugin] accessed [cache:local] (ALLOWED)
```

### 3. 【真实案例】连宿主初始化都会被拦截？

在早期的版本中，LingFrame 甚至因为安全策略过于严格，拦截了 Spring Boot 启动时的 SQL 初始化脚本（`schema.sql`），抛出了 `Security Alert: SQL execution without PluginContext. SQL`。这从侧面证明了治理内核的**无死角覆盖**——即使是框架启动阶段的 I/O 操作也逃不过它的法眼。
![alt text](../images/initDB.png)
> 现在的版本已针对宿主是否开启治理进行判断，确保开发者体验。

---

## 下一步

- [模块开发指南](plugin-development.md) - 学习如何开发业务模块
- [共享 API 设计规范](shared-api-guidelines.md) - API 设计最佳实践
- [Dashboard 可视化治理](dashboard.md) - 高阶可选功能
- [架构设计](architecture.md) - 深入了解治理原理
