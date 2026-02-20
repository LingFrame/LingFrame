# Infrastructure ling Development Guide

This document introduces the **Infrastructure Layer** in LingFrame's three-tier architecture and its development methods.

## Three-Tier Architecture Review

```
┌─────────────────────────────────────────────────────────┐
│                 Core (Governance Kernel)                 │
│      Auth Arbitration · Audit · Scheduling · Isolation    │
└────────────────────────────┬────────────────────────────┘
                             ▼
┌─────────────────────────────────────────────────────────┐
│           Infrastructure (Infra Layer)           │  ← This Document
│            Storage · Cache · Message · Search             │
└────────────────────────────┬────────────────────────────┘
                             ▼
┌─────────────────────────────────────────────────────────┐
│             Business Lings (Business Layer)            │
│          User Center · Order Service · Payment            │
└─────────────────────────────────────────────────────────┘
```

## Infrastructure Proxy Responsibilities

The Infrastructure Proxy is the **Middle Layer** of the three-tier architecture, responsible for:

1. **Encapsulate Bottom Capabilities**: Database, Cache, Message Queue, etc.
2. **Fine-grained Permission Interception**: Perform permission checks at the API level.
3. **Audit Reporting**: Report operation records to Core.
4. **Transparent to Business Units**: Business units use infrastructure imperceptibly.

## Implemented Infrastructure Proxies

### lingframe-infra-storage (Storage Proxy)

Provides database access capabilities, implementing SQL-level permission control via a proxy chain.

#### Proxy Chain Structure

```
Business Unit calls DataSource.getConnection()
    │
    ▼
┌─────────────────────────────────────┐
│ LingDataSourceProxy                 │
│ - Wraps original DataSource         │
│ - Returns LingConnectionProxy       │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│ LingConnectionProxy                 │
│ - Wraps original Connection         │
│ - createStatement() → LingStatementProxy
│ - prepareStatement() → LingPreparedStatementProxy
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│ LingStatementProxy                  │
│ LingPreparedStatementProxy          │
│ - Intercept execute/executeQuery/executeUpdate
│ - Parse SQL Type (SELECT/INSERT/UPDATE/DELETE)
│ - Call PermissionService to check permission
│ - Report Audit Log                  │
└─────────────────────────────────────┘
```

#### Core Implementation

**DataSourceWrapperProcessor**: Auto-wrap DataSource via BeanPostProcessor

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

**LingPreparedStatementProxy**: SQL-level Permission Check

```java
public class LingPreparedStatementProxy implements PreparedStatement {

    private void checkPermission() throws SQLException {
        // 1. Get Caller ling ID
        String callerLingId = LingContextHolder.get();
        if (callerLingId == null) return;

        // 2. Parse SQL Type
        AccessType accessType = parseSqlForAccessType(sql);

        // 3. Permission Check
        boolean allowed = permissionService.isAllowed(callerLingId, "storage:sql", accessType);

        // 4. Audit Report
        permissionService.audit(callerLingId, "storage:sql", sql, allowed);

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

#### SQL Type Parsing

Supports two parsing strategies:

1. **Simple Match**: Short SQL string matching
2. **JSqlParser**: Complex SQL syntax parsing

```java
private AccessType parseSqlForAccessType(String sql) {
    // Simple SQL direct matching
    if (isSimpleSql(sql)) {
        return fallbackParseSql(sql);
    }

    // Complex SQL using JSqlParser
    Statement statement = CCJSqlParserUtil.parse(sql);
    if (statement instanceof Select) return AccessType.READ;
    if (statement instanceof Insert || Update || Delete) return AccessType.WRITE;
    return AccessType.EXECUTE;
}
```

### lingframe-infra-cache (Cache Proxy)

Provides cache access capabilities, supporting Spring Cache, Caffeine, and Redis, implementing permission control via proxies and interceptors.

#### Proxy Chain Structure

```
Business Unit calls cacheManager.getCache("users")
    │
    ▼
┌─────────────────────────────────────┐
│ LingCacheManagerProxy               │
│ - Wraps CacheManager                │
│ - Returns LingSpringCacheProxy      │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│ LingSpringCacheProxy                │
│ - Intercept get/put/evict/clear     │
│ - Permission Check + Audit Report    │
└─────────────────────────────────────┘

Business Unit calls redisTemplate.opsForValue().set(...)
    │
    ▼
┌─────────────────────────────────────┐
│ RedisPermissionInterceptor          │
│ - AOP Intercept RedisTemplate methods│
│ - Infer Operation Type (READ/WRITE)  │
│ - Permission Check + Audit Report    │
└─────────────────────────────────────┘
```

#### Core Implementation

**RedisPermissionInterceptor**: Redis Operation Permission Interception

```java
@Override
public Object invoke(MethodInvocation invocation) throws Throwable {
    String callerLingId = LingContextHolder.get();
    String methodName = invocation.getMethod().getName();
    
    // Infer Operation Type
    AccessType accessType = inferAccessType(methodName);
    
    // Permission Check
    boolean allowed = permissionService.isAllowed(callerLingId, "cache:redis", accessType);
    
    // Audit Report
    permissionService.audit(callerLingId, "cache:redis", methodName, allowed);
    
    if (!allowed) {
        throw new PermissionDeniedException(...);
    }
    return invocation.proceed();
}

private AccessType inferAccessType(String methodName) {
    if (methodName.startsWith("get") || methodName.startsWith("has")) {
        return AccessType.READ;
    }
    if (methodName.startsWith("set") || methodName.startsWith("delete")) {
        return AccessType.WRITE;
    }
    return AccessType.EXECUTE;
}
```

**LingSpringCacheProxy**: Spring Cache Common Proxy

```java
@Override
public void put(@NonNull Object key, Object value) {
    checkPermission(AccessType.WRITE);
    target.put(key, value);
}

@Override
public ValueWrapper get(@NonNull Object key) {
    checkPermission(AccessType.READ);
    return target.get(key);
}
```

#### Supported Cache Types

| Type | Capability ID | Description |
| ---- | ------------- | ----------- |
| Spring Cache | `cache:local` | Unified Abstraction Layer |
| Redis | `cache:redis` | RedisTemplate Interception |
| Caffeine | `cache:local` | Local Cache |

## Developing New Infrastructure Proxies

### 1. Create Unit

```xml
<project>
    <parent>
        <groupId>com.lingframe</groupId>
        <artifactId>lingframe-infrastructure</artifactId>
    </parent>

    <artifactId>lingframe-infra-xxx</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.lingframe</groupId>
            <artifactId>lingframe-api</artifactId>
        </dependency>
    </dependencies>
</project>
```

### 2. Implement Proxy Pattern

The core of an infrastructure proxy is the **Proxy Pattern**:

```java
public class LingXxxProxy implements XxxInterface {

    private final XxxInterface target;
    private final PermissionService permissionService;

    @Override
    public Result doSomething(Args args) {
        // 1. Get Caller
        String callerLingId = LingContextHolder.get();

        // 2. Permission Check
        if (!permissionService.isAllowed(callerLingId, "xxx:capability", accessType)) {
            throw new PermissionDeniedException(...);
        }

        // 3. Audit Report
        permissionService.audit(callerLingId, "xxx:capability", operation, true);

        // 4. Execute Real Operation
        return target.doSomething(args);
    }
}
```

### 3. Auto-Wrap Bean

Use BeanPostProcessor for automatic wrapping:

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

### 4. Define Capability ID

Infrastructure proxies need to define clear capability identifiers (capability):

| ling  | Capability ID     | Description |
| ------- | ----------------- | ----------- |
| storage | `storage:sql`     | SQL Execution |
| cache   | `cache:redis`     | Redis Operation |
| cache   | `cache:local`     | Local Cache |
| message | `message:send`    | Send Message |
| message | `message:consume` | Consume Message |

Business units declare required capabilities in `ling.yml`:

```yaml
id: my-ling
version: 1.0.0
mainClass: "com.example.MyLing"

governance:
  permissions:
    - methodPattern: "storage:sql"
      permissionId: "READ"
    - methodPattern: "cache:redis"
      permissionId: "WRITE"
```

## Relationship with Business Units

```
┌─────────────────────────────────────────────────────────┐
│                    Business Unit                       │
│  userRepository.findById(id)                            │
│         │                                               │
│         │ (Transparent Call)                             │
│         ▼                                               │
│  ┌─────────────────────────────────────────────────┐   │
│  │              MyBatis / JPA                       │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
                         │
                         │ (JDBC)
                         ▼
┌─────────────────────────────────────────────────────────┐
│                 Infrastructure Proxy (Storage)           │
│  ┌─────────────────────────────────────────────────┐   │
│  │ LingDataSourceProxy → LingConnectionProxy       │   │
│  │     → LingPreparedStatementProxy                │   │
│  │                                                  │   │
│  │ 1. Get callerLingId                           │   │
│  │ 2. Parse SQL Type                                │   │
│  │ 3. Call PermissionService.isAllowed()           │   │
│  │ 4. Audit Reporting                               │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
                         │
                         │ (Permission Query)
                         ▼
┌─────────────────────────────────────────────────────────┐
│                       Core                              │
│  PermissionService.isAllowed(lingId, capability, type)│
│  AuditManager.asyncRecord(...)                          │
└─────────────────────────────────────────────────────────┘
```

## Best Practices

1. **Proxy Transparency**: Business Lings should not be aware of the proxy's existence.
2. **Capability ID Standardization**: Use `ling:Operation` format.
3. **Fine-grained Control**: Intercept at the closest point to the operation.
4. **Async Audit**: Audit should not block business flow.
5. **Cache Optimization**: SQL parsing results can be cached.
