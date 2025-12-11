# 基础设施插件开发指南

本文档介绍 LingFrame 三层架构中的**基础设施层**及其开发方式。

## 三层架构回顾

```
┌─────────────────────────────────────────────────────────┐
│                    Core（仲裁核心）                      │
│         生命周期管理 · 权限治理 · 能力调度 · 上下文隔离    │
└────────────────────────────┬────────────────────────────┘
                             ▼
┌─────────────────────────────────────────────────────────┐
│           Infrastructure Plugins（基础设施层）           │  ← 本文档
│              存储 · 缓存 · 消息 · 搜索                   │
└────────────────────────────┬────────────────────────────┘
                             ▼
┌─────────────────────────────────────────────────────────┐
│             Business Plugins（业务层）                   │
│              用户中心 · 订单服务 · 支付模块               │
└─────────────────────────────────────────────────────────┘
```

## 基础设施插件的职责

基础设施插件是三层架构的**中间层**，负责：

1. **封装底层能力**：数据库、缓存、消息队列等
2. **细粒度权限拦截**：在 API 层面进行权限检查
3. **审计上报**：将操作记录上报给 Core
4. **对业务插件透明**：业务插件无感知地使用基础设施

## 已实现的基础设施插件

### lingframe-plugin-storage（存储插件）

提供数据库访问能力，通过代理链实现 SQL 级权限控制。

#### 代理链结构

```
业务插件调用 DataSource.getConnection()
    │
    ▼
┌─────────────────────────────────────┐
│ LingDataSourceProxy                 │
│ - 包装原始 DataSource               │
│ - 返回 LingConnectionProxy          │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│ LingConnectionProxy                 │
│ - 包装原始 Connection               │
│ - createStatement() → LingStatementProxy
│ - prepareStatement() → LingPreparedStatementProxy
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│ LingStatementProxy                  │
│ LingPreparedStatementProxy          │
│ - 拦截 execute/executeQuery/executeUpdate
│ - 解析 SQL 类型（SELECT/INSERT/UPDATE/DELETE）
│ - 调用 PermissionService 检查权限   │
│ - 上报审计日志                       │
└─────────────────────────────────────┘
```

#### 核心实现

**DataSourceWrapperProcessor**：通过 BeanPostProcessor 自动包装 DataSource

```java
@Component
public class DataSourceWrapperProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource) {
            PermissionService permissionService = applicationContext.getBean(PermissionService.class);
            return new LingDataSourceProxy((DataSource) bean, permissionService);
        }
        return bean;
    }
}
```

**LingPreparedStatementProxy**：SQL 级权限检查

```java
public class LingPreparedStatementProxy implements PreparedStatement {

    private void checkPermission() throws SQLException {
        // 1. 获取调用方插件ID
        String callerPluginId = PluginContextHolder.get();
        if (callerPluginId == null) return;

        // 2. 解析 SQL 类型
        AccessType accessType = parseSqlForAccessType(sql);

        // 3. 权限检查
        boolean allowed = permissionService.isAllowed(callerPluginId, "storage:sql", accessType);

        // 4. 审计上报
        permissionService.audit(callerPluginId, "storage:sql", sql, allowed);

        if (!allowed) {
            throw new SQLException(new PermissionDeniedException(...));
        }
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        checkPermission();
        return target.executeQuery();
    }
}
```

#### SQL 类型解析

支持两种解析策略：

1. **简单匹配**：短 SQL 直接字符串匹配
2. **JSqlParser**：复杂 SQL 使用语法解析

```java
private AccessType parseSqlForAccessType(String sql) {
    // 简单 SQL 直接匹配
    if (isSimpleSql(sql)) {
        return fallbackParseSql(sql);
    }

    // 复杂 SQL 使用 JSqlParser
    Statement statement = CCJSqlParserUtil.parse(sql);
    if (statement instanceof Select) return AccessType.READ;
    if (statement instanceof Insert || Update || Delete) return AccessType.WRITE;
    return AccessType.EXECUTE;
}
```

### lingframe-plugin-cache（缓存插件）

> ⏳ 待实现

计划提供：

- Redis 访问代理
- Caffeine 本地缓存
- 缓存操作权限控制

## 开发新的基础设施插件

### 1. 创建模块

```xml
<project>
    <parent>
        <groupId>com.lingframe</groupId>
        <artifactId>lingframe-plugins-infra</artifactId>
    </parent>

    <artifactId>lingframe-plugin-xxx</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.lingframe</groupId>
            <artifactId>lingframe-api</artifactId>
        </dependency>
    </dependencies>
</project>
```

### 2. 实现代理模式

基础设施插件的核心是**代理模式**：

```java
public class LingXxxProxy implements XxxInterface {

    private final XxxInterface target;
    private final PermissionService permissionService;

    @Override
    public Result doSomething(Args args) {
        // 1. 获取调用方
        String callerPluginId = PluginContextHolder.get();

        // 2. 权限检查
        if (!permissionService.isAllowed(callerPluginId, "xxx:capability", accessType)) {
            throw new PermissionDeniedException(...);
        }

        // 3. 审计上报
        permissionService.audit(callerPluginId, "xxx:capability", operation, true);

        // 4. 执行真实操作
        return target.doSomething(args);
    }
}
```

### 3. 自动包装 Bean

使用 BeanPostProcessor 自动包装：

```java
@Component
public class XxxWrapperProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof XxxInterface) {
            return new LingXxxProxy((XxxInterface) bean, permissionService);
        }
        return bean;
    }
}
```

### 4. 定义能力标识

基础设施插件需要定义清晰的能力标识（capability）：

| 插件    | 能力标识          | 说明       |
| ------- | ----------------- | ---------- |
| storage | `storage:sql`     | SQL 执行   |
| cache   | `cache:redis`     | Redis 操作 |
| cache   | `cache:local`     | 本地缓存   |
| message | `message:send`    | 发送消息   |
| message | `message:consume` | 消费消息   |

业务插件在 `plugin.yml` 中声明所需能力：

```yaml
plugin:
  id: my-plugin
  permissions:
    - capability: "storage:sql"
      access: "READ"
    - capability: "cache:redis"
      access: "WRITE"
```

## 与业务插件的关系

```
┌─────────────────────────────────────────────────────────┐
│                    业务插件                              │
│  userRepository.findById(id)                            │
│         │                                               │
│         │ (透明调用)                                     │
│         ▼                                               │
│  ┌─────────────────────────────────────────────────┐   │
│  │              MyBatis / JPA                       │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
                         │
                         │ (JDBC)
                         ▼
┌─────────────────────────────────────────────────────────┐
│                 基础设施插件 (Storage)                   │
│  ┌─────────────────────────────────────────────────┐   │
│  │ LingDataSourceProxy → LingConnectionProxy       │   │
│  │     → LingPreparedStatementProxy                │   │
│  │                                                  │   │
│  │ 1. 获取 callerPluginId                          │   │
│  │ 2. 解析 SQL 类型                                 │   │
│  │ 3. 调用 PermissionService.isAllowed()           │   │
│  │ 4. 审计上报                                      │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
                         │
                         │ (权限查询)
                         ▼
┌─────────────────────────────────────────────────────────┐
│                       Core                              │
│  PermissionService.isAllowed(pluginId, capability, type)│
│  AuditManager.asyncRecord(...)                          │
└─────────────────────────────────────────────────────────┘
```

## 最佳实践

1. **代理透明**：业务插件无需感知代理存在
2. **能力标识规范**：使用 `插件:操作` 格式
3. **细粒度控制**：在最接近操作的地方拦截
4. **异步审计**：审计不阻塞业务流程
5. **缓存优化**：SQL 解析结果可缓存
