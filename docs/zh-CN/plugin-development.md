# 业务模块开发指南

本文档介绍如何开发 LingFrame 业务模块。

## 创建插件项目

### 1. Maven 配置

```xml
<project>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.5.6</version>
    </parent>

    <dependencies>
        <!-- LingFrame API -->
        <dependency>
            <groupId>com.lingframe</groupId>
            <artifactId>lingframe-api</artifactId>
            <version>${lingframe.version}</version>
        </dependency>

        <!-- Spring Boot -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>
    </dependencies>
</project>
```

### 2. 插件入口类

```java
package com.example.myplugin;

import com.lingframe.api.context.PluginContext;
import com.lingframe.api.plugin.LingPlugin;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MyPlugin implements LingPlugin {

    @Override
    public void onStart(PluginContext context) {
        System.out.println("插件启动: " + context.getPluginId());
    }

    @Override
    public void onStop(PluginContext context) {
        System.out.println("插件停止: " + context.getPluginId());
    }
}
```

### 3. 插件元数据

创建 `src/main/resources/plugin.yml`：

```yaml
# 基础元数据
id: my-plugin
version: 1.0.0
provider: "My Company"
description: "我的插件"
mainClass: "com.example.myplugin.MyPlugin"

# 依赖声明（可选）
dependencies:
  - id: "base-plugin"
    minVersion: "1.0.0"

# 治理配置
governance:
  permissions:
    - methodPattern: "storage:sql"
      permissionId: "READ"
    - methodPattern: "cache:redis"
      permissionId: "WRITE"
  
  audits:
    - methodPattern: "com.example.*Service#delete*"
      action: "DELETE_OPERATION"
      enabled: true

# 自定义属性（可选）
properties:
  custom-config: "value"
  timeout: 5000
```

## 暴露服务

使用 `@LingService` 注解暴露服务。

**消费者驱动契约**：接口由消费者定义，生产者实现。例如 Order 模块定义 `UserQueryService`，User 模块实现它：

```java
// ========== 消费者定义接口（在 order-api 模块中）==========
public interface UserQueryService {
    Optional<UserDTO> findById(String userId);
}

// ========== 生产者实现接口（在 user-plugin 模块中）==========
package com.example.user.service;

import com.lingframe.api.annotation.Auditable;
import com.lingframe.api.annotation.LingService;
import com.lingframe.api.annotation.RequiresPermission;
import org.springframework.stereotype.Component;

@Component
public class UserQueryServiceImpl implements UserQueryService {

    @LingService(id = "find_user", desc = "根据ID查询用户")
    @Override
    public Optional<UserDTO> findById(String userId) {
        return userRepository.findById(userId).map(this::toDTO);
    }
}

// 另一个服务示例：带权限和审计
@Component  
public class UserAdminServiceImpl implements UserAdminService {

    @LingService(id = "create_user", desc = "创建新用户", timeout = 5000)
    @RequiresPermission("user:write")
    @Auditable(action = "CREATE_USER", resource = "user")
    @Override
    public UserDTO createUser(CreateUserRequest request) {
        return toDTO(userRepository.save(toEntity(request)));
    }
}
```

### @LingService 属性

| 属性      | 说明                  | 默认值 |
| --------- | --------------------- | ------ |
| `id`      | 服务短 ID，组成 FQSID | 必填   |
| `desc`    | 服务描述              | 空     |
| `timeout` | 超时时间（毫秒）      | 3000   |

### FQSID 格式

服务的全局唯一标识：`pluginId:serviceId`

例如：`user-plugin:find_user`

## 调用其他插件服务

LingFrame 提供三种服务调用方式，推荐优先级如下：

### 方式一：@LingReference 注入（强烈推荐）

这是最接近 Spring 原生体验的调用方式。

**消费者驱动契约**：Order 插件（消费者）定义它需要的接口，User/Payment 插件（生产者）实现：

```java
// ========== 第1步：消费者定义接口（在 order-api 模块中）==========
// 位置：order-api/src/main/java/com/example/order/api/

// Order 插件定义它需要的用户查询能力
public interface UserQueryService {
    Optional<UserDTO> findById(String userId);
}

// Order 插件定义它需要的支付能力
public interface PaymentService {
    PaymentResult processPayment(String userId, BigDecimal amount);
}

// ========== 第2步：生产者实现接口（在各自插件中）==========
// User 插件实现 Order 定义的 UserQueryService
// 位置：user-plugin/src/main/java/.../UserQueryServiceImpl.java
@Component
public class UserQueryServiceImpl implements UserQueryService {
    @LingService(id = "find_user", desc = "查询用户")
    @Override
    public Optional<UserDTO> findById(String userId) {
        return userRepository.findById(userId).map(this::toDTO);
    }
}

// Payment 插件实现 Order 定义的 PaymentService
// 位置：payment-plugin/src/main/java/.../PaymentServiceImpl.java
@Component
public class PaymentServiceImpl implements PaymentService {
    @LingService(id = "process_payment", desc = "处理支付", timeout = 5000)
    @Override
    public PaymentResult processPayment(String userId, BigDecimal amount) {
        return paymentGateway.charge(userId, amount);
    }
}

// ========== 第3步：消费者使用自己定义的接口 ==========
// 位置：order-plugin/src/main/java/.../OrderService.java
@Component
public class OrderService {

    @LingReference
    private UserQueryService userQueryService;  // 自动注入，由 User 插件实现

    @LingReference
    private PaymentService paymentService;  // 自动注入，由 Payment 插件实现

    public Order createOrder(String userId, List<Item> items) {
        // 直接调用，框架自动路由到生产者的实现
        UserDTO user = userQueryService.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        BigDecimal total = calculateTotal(items);
        PaymentResult result = paymentService.processPayment(userId, total);
        
        return new Order(user, items, result);
    }
}
```

**@LingReference 优点：**
- 代码最简洁，接近 Spring 原生体验
- 支持延迟绑定，插件未启动时也能创建代理
- 自动路由到最新版本插件
- 支持可选的 pluginId 指定和超时配置
- 通过 GlobalServiceRoutingProxy 实现智能路由

### 方式二：PluginContext.getService()

适合需要显式错误处理的场景：

```java
@Component
public class OrderService {

    @Autowired
    private PluginContext context;

    public Order createOrder(String userId, List<Item> items) {
        // 获取消费者定义的接口的实现（由 User 插件提供）
        Optional<UserQueryService> userQueryService = context.getService(UserQueryService.class);
        
        if (userQueryService.isEmpty()) {
            throw new RuntimeException("用户查询服务不可用");
        }
        
        UserDTO user = userQueryService.get().findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        return new Order(user, items);
    }
}
```

### 方式三：PluginContext.invoke() FQSID 调用

适合松耦合场景，不需要接口依赖：

```java
@Component
public class OrderService {

    @Autowired
    private PluginContext context;

    public Order createOrder(String userId, List<Item> items) {
        // 通过 FQSID 直接调用生产者的服务
        Optional<UserDTO> user = context.invoke("user-plugin:find_user", userId);
        
        if (user.isEmpty()) {
            throw new RuntimeException("用户不存在");
        }
        
        return new Order(user.get(), items);
    }
}
```

## 权限声明

### 显式声明

```java
@RequiresPermission("user:export")
public void exportUsers() { ... }
```

### 智能推导

框架会根据方法名前缀自动推导权限：

| 前缀                                           | AccessType |
| ---------------------------------------------- | ---------- |
| `get`, `find`, `query`, `list`, `select`       | READ       |
| `create`, `save`, `insert`, `update`, `delete` | WRITE      |
| 其他                                           | EXECUTE    |

### 开发模式

开发时可在配置文件中开启宽松模式，权限不足仅警告：

```yaml
lingframe:
  dev-mode: true
```

## 审计日志

### 显式声明

```java
@Auditable(action = "EXPORT_DATA", resource = "user")
public void exportUsers() { ... }
```

### 自动审计

写操作（WRITE）和执行操作（EXECUTE）会自动记录审计日志。

## 事件通信

### 发布事件

```java
public class UserCreatedEvent implements LingEvent {
    private final String userId;
    
    public UserCreatedEvent(String userId) {
        this.userId = userId;
    }
    
    public String getUserId() {
        return userId;
    }
}

// 发布
context.publishEvent(new UserCreatedEvent("123"));
```

### 监听事件

```java
@Component
public class UserEventListener implements LingEventListener<UserCreatedEvent> {

    @Override
    public void onEvent(UserCreatedEvent event) {
        System.out.println("用户创建: " + event.getUserId());
    }
}
```

## 打包部署

### 生产模式

```bash
mvn clean package
# 生成 target/my-plugin.jar
```

将 JAR 放入宿主应用的 plugins 目录，框架会自动扫描并加载。

### 开发模式

在宿主应用的配置文件中指向编译输出目录：

```yaml
lingframe:
  dev-mode: true
  plugin-roots:
    - "../my-plugin"
```

修改代码后重新编译，HotSwapWatcher 会自动检测 target/classes 中的变化并热重载。

## 框架治理能力

以下能力由 **LingFrame 核心**提供，业务模块无需自行实现：

| 能力 | 说明 | 插件需要做什么 |
|------|------|----------------|
| 熔断 | 服务不可用时自动熔断 | 无需处理 |
| 降级 | 熔断后返回降级响应 | 可选：提供 `@Fallback` 方法 |
| 重试 | 失败自动重试 | 无需处理 |
| 超时 | 调用超时控制 | 可在 `@LingService` 中配置 |
| 限流 | 请求频率限制 | 无需处理 |
| 监控 | 调用链路追踪 | 无需处理 |

**好处**：业务模块只需关注业务逻辑，治理策略可通过配置动态调整。

## 最佳实践

1. **消费者驱动契约**：消费者定义接口，生产者实现（详见 [共享 API 设计规范](shared-api-guidelines.md)）
2. **服务调用优先级**：@LingReference > getService() > invoke()
3. **依赖最小化**：插件只依赖 `lingframe-api`，不要依赖 `lingframe-core`
4. **权限声明**：在 `plugin.yml` 中声明所需权限
5. **服务粒度**：一个 `@LingService` 对应一个业务操作
6. **异常处理**：使用 `LingException` 及其子类
7. **日志规范**：使用 SLF4J，避免直接 System.out
8. **接口设计**：消费者定义精简的接口，只包含自己需要的方法

## 常见问题

### ClassNotFoundException

检查类加载器隔离，确保依赖的类在插件 JAR 中或父加载器可见。

### 权限被拒绝

1. 检查 `plugin.yml` 中的权限声明
2. 开发时开启 `lingframe.dev-mode: true`

### @LingReference 注入失败

1. 确保目标插件已启动并注册了对应的服务 Bean
2. 检查接口类型是否匹配
3. 如果指定了 pluginId，确保插件ID正确

### 热重载不生效

1. 确保配置了 `lingframe.dev-mode: true`
2. 确保在 `plugin-roots` 中配置了插件的 target/classes 目录
3. 重新编译后等待 500ms（防抖延迟）
