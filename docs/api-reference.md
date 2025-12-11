# API 参考

本文档提供 LingFrame API 的完整参考。

## 核心接口

### LingPlugin

插件生命周期接口，所有插件入口类必须实现。

```java
package com.lingframe.api.plugin;

public interface LingPlugin {

    /**
     * 插件启动回调
     * @param context 插件上下文
     */
    default void onStart(PluginContext context) {}

    /**
     * 插件停止回调
     * @param context 插件上下文
     */
    default void onStop(PluginContext context) {}
}
```

### PluginContext

插件上下文，与 Core 交互的唯一桥梁。

```java
package com.lingframe.api.context;

public interface PluginContext {

    /**
     * 获取当前插件ID
     */
    String getPluginId();

    /**
     * 获取配置属性
     */
    Optional<String> getProperty(String key);

    /**
     * 通过 FQSID 调用服务
     * @param serviceId 格式: pluginId:serviceId
     * @param args 参数列表
     */
    <T> Optional<T> invoke(String serviceId, Object... args);

    /**
     * 按接口类型获取服务
     */
    <T> Optional<T> getService(Class<T> serviceClass);

    /**
     * 获取权限服务
     */
    PermissionService getPermissionService();

    /**
     * 发布事件
     */
    void publishEvent(LingEvent event);
}
```

### PermissionService

权限服务接口。

```java
package com.lingframe.api.security;

public interface PermissionService {

    /**
     * 检查权限
     * @param pluginId 插件ID
     * @param capability 能力标识
     * @param accessType 访问类型
     */
    boolean isAllowed(String pluginId, String capability, AccessType accessType);

    /**
     * 授予权限
     */
    void grant(String pluginId, String capability, AccessType accessType);

    /**
     * 获取权限配置
     */
    Object getPermission(String pluginId, String capability);

    /**
     * 记录审计日志
     */
    void audit(String pluginId, String capability, String operation, boolean allowed);
}
```

### AccessType

访问类型枚举。

```java
package com.lingframe.api.security;

public enum AccessType {
    READ,    // 读操作
    WRITE,   // 写操作
    EXECUTE  // 执行操作
}
```

## 注解

### @LingService

声明对外暴露的服务方法。

```java
package com.lingframe.api.annotation;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface LingService {

    /**
     * 服务短ID（必填）
     * 与插件ID组合为 FQSID: pluginId:id
     */
    String id();

    /**
     * 服务描述
     */
    String desc() default "";

    /**
     * 超时时间（毫秒）
     */
    long timeout() default 3000;
}
```

**示例**：

```java
@LingService(id = "query_user", desc = "查询用户", timeout = 5000)
public User queryUser(String userId) {
    return userRepository.findById(userId).orElse(null);
}
```

### @RequiresPermission

声明方法所需权限。

```java
package com.lingframe.api.annotation;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresPermission {

    /**
     * 权限标识符
     */
    String value();

    /**
     * 描述信息
     */
    String description() default "";
}
```

**示例**：

```java
@RequiresPermission(value = "user:export", description = "导出用户数据")
public void exportUsers() { ... }
```

### @Auditable

声明需要审计的方法。

```java
package com.lingframe.api.annotation;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Auditable {

    /**
     * 操作动作描述
     */
    String action();

    /**
     * 资源标识
     */
    String resource() default "";
}
```

**示例**：

```java
@Auditable(action = "DELETE_USER", resource = "user")
public void deleteUser(String userId) { ... }
```

## 事件

### LingEvent

事件标记接口。

```java
package com.lingframe.api.event;

public interface LingEvent {
    // 标记接口
}
```

### LingEventListener

事件监听器接口。

```java
package com.lingframe.api.event;

public interface LingEventListener<E extends LingEvent> {

    /**
     * 处理事件
     */
    void onEvent(E event);
}
```

**示例**：

```java
// 定义事件
public class UserCreatedEvent implements LingEvent {
    private final String userId;
    public UserCreatedEvent(String userId) { this.userId = userId; }
    public String getUserId() { return userId; }
}

// 发布事件
context.publishEvent(new UserCreatedEvent("123"));

// 监听事件
@Component
public class UserEventListener implements LingEventListener<UserCreatedEvent> {
    @Override
    public void onEvent(UserCreatedEvent event) {
        System.out.println("用户创建: " + event.getUserId());
    }
}
```

## 异常

### LingException

框架基础异常。

```java
package com.lingframe.api.exception;

public class LingException extends RuntimeException {
    public LingException(String message) { super(message); }
    public LingException(String message, Throwable cause) { super(message, cause); }
}
```

### PermissionDeniedException

权限拒绝异常。

```java
package com.lingframe.api.exception;

public class PermissionDeniedException extends LingException {
    public PermissionDeniedException(String message) { super(message); }
}
```

## 上下文持有者

### PluginContextHolder

ThreadLocal 存储当前调用链的插件 ID。

```java
package com.lingframe.api.context;

public class PluginContextHolder {

    public static void set(String pluginId);
    public static String get();
    public static void clear();
}
```

**使用场景**：在基础设施插件中获取调用方插件 ID。

```java
// 在 SQL 代理中
String callerPluginId = PluginContextHolder.get();
if (callerPluginId != null) {
    permissionService.isAllowed(callerPluginId, "storage:sql", accessType);
}
```

## 配置

### LingFrameConfig

全局配置。

```java
package com.lingframe.core.config;

public class LingFrameConfig {

    /**
     * 是否开发模式
     * 开发模式下权限不足仅警告，不抛异常
     */
    public static void setDevMode(boolean devMode);
    public static boolean isDevMode();
}
```

## 插件元数据

### plugin.yml

```yaml
plugin:
  # 插件ID（必填）
  id: my-plugin

  # 版本号
  version: 1.0.0

  # 权限声明
  permissions:
    - capability: "storage:sql"
      access: "READ"
    - capability: "cache:redis"
      access: "WRITE"

  # 依赖声明
  dependencies:
    - id: "base-plugin"
      minVersion: "1.0.0"
```

## 智能推导规则

### 权限推导

方法名前缀 → AccessType：

| 前缀                                                                             | AccessType |
| -------------------------------------------------------------------------------- | ---------- |
| `get`, `find`, `query`, `list`, `select`, `count`, `check`, `is`, `has`          | READ       |
| `create`, `save`, `insert`, `update`, `modify`, `delete`, `remove`, `add`, `set` | WRITE      |
| 其他                                                                             | EXECUTE    |

权限标识格式：`ClassName:AccessType`

### 审计推导

- 显式 `@Auditable` 注解：强制审计
- WRITE 操作：自动审计
- EXECUTE 操作：自动审计
- READ 操作：不自动审计
