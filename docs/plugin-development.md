# 插件开发指南

本文档介绍如何开发 LingFrame 插件。

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
plugin:
  id: my-plugin
  version: 1.0.0
  permissions:
    - capability: "storage:sql"
      access: "READ"
    - capability: "cache:redis"
      access: "WRITE"
```

## 暴露服务

使用 `@LingService` 注解暴露服务：

```java
@Component
public class UserService {

    @LingService(id = "query_user", desc = "查询用户")
    public User queryUser(String userId) {
        return userRepository.findById(userId).orElse(null);
    }

    @LingService(id = "create_user", desc = "创建用户", timeout = 5000)
    @RequiresPermission("user:write")
    @Auditable(action = "CREATE_USER", resource = "user")
    public User createUser(User user) {
        return userRepository.save(user);
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

例如：`my-plugin:query_user`

## 调用其他插件

### 通过 FQSID 调用

```java
@Component
public class OrderService {

    @Autowired
    private PluginContext context;

    public Order createOrder(String userId, List<Item> items) {
        // 调用 user-plugin 的 query_user 服务
        Optional<User> user = context.invoke("user-plugin:query_user", userId);

        if (user.isEmpty()) {
            throw new RuntimeException("用户不存在");
        }

        // 创建订单...
        return order;
    }
}
```

### 通过接口类型获取

```java
Optional<UserService> userService = context.getService(UserService.class);
userService.ifPresent(service -> {
    User user = service.queryUser("123");
});
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

开发时可开启宽松模式，权限不足仅警告：

```java
LingFrameConfig.setDevMode(true);
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
    // getter...
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

将 JAR 放入宿主应用的 plugins 目录，通过 `PluginManager.install()` 加载。

### 开发模式

直接指向编译输出目录：

```java
pluginManager.installDev("my-plugin", "dev",
    new File("../my-plugin/target/classes"));
```

修改代码后重新编译（Ctrl+F9），框架会自动热重载。

## 最佳实践

1. **依赖最小化**：插件只依赖 `lingframe-api`，不要依赖 `lingframe-core`
2. **权限声明**：在 `plugin.yml` 中声明所需权限
3. **服务粒度**：一个 `@LingService` 对应一个业务操作
4. **异常处理**：使用 `LingException` 及其子类
5. **日志规范**：使用 SLF4J，避免直接 System.out

## 常见问题

### ClassNotFoundException

检查类加载器隔离，确保依赖的类在插件 JAR 中或父加载器可见。

### 权限被拒绝

1. 检查 `plugin.yml` 中的权限声明
2. 开发时开启 `LingFrameConfig.setDevMode(true)`

### 热重载不生效

1. 确保使用 `installDev()` 安装
2. 检查 `HotSwapWatcher` 是否正常监听
3. 重新编译后等待 500ms（防抖延迟）
