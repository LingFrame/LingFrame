# Architecture Design

This document describes the core architecture design and implementation principles of LingFrame.

## Design Philosophy

LingFrame draws inspiration from operating system design principles:

- **Microkernel**: Core is responsible only for scheduling and arbitration, containing no business logic.
- **Zero Trust**: Business modules cannot directly access infrastructure; they must go through the Core proxy.
- **Capability Isolation**: Each plugin runs in an independent ClassLoader and Spring Context.

## Three-Tier Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      Host Application                        │
│                                                              │
│  ┌───────────────────────────────────────────────────────┐  │
│  │                 Core (Governance Kernel)                │  │
│  │                                                        │  │
│  │   PluginManager · PermissionService · EventBus        │  │
│  │   AuditManager · TraceContext · GovernanceStrategy    │  │
│  │                                                        │  │
│  │   Resp: Lifecycle Mgmt · Auth Gov · Capability Sched · Context Isolation │  │
│  └────────────────────────┬──────────────────────────────┘  │
│                           │                                  │
│         ┌─────────────────┼─────────────────┐               │
│         ▼                 ▼                 ▼               │
│  ┌─────────────┐   ┌─────────────┐   ┌─────────────┐       │
│  │  Storage    │   │   Cache     │   │  Message    │       │
│  │  Proxy     │   │   Proxy    │   │  Proxy     │       │
│  │             │   │             │   │             │       │
│  │ Infra Layer  │   │ Infra Layer  │   │ Infra Layer  │       │
│  └──────┬──────┘   └──────┬──────┘   └──────┬──────┘       │
│         │                 │                 │               │
│         └─────────────────┼─────────────────┘               │
│                           │                                  │
│         ┌─────────────────┼─────────────────┐               │
│         ▼                 ▼                 ▼               │
│  ┌─────────────┐   ┌─────────────┐   ┌─────────────┐       │
│  │   User      │   │   Order     │   │  Payment    │       │
│  │  Plugin     │   │   Plugin    │   │  Plugin     │       │
│  │             │   │             │   │             │       │
│  │ Business Layer│   │ Business Layer│   │ Business Layer│       │
│  └─────────────┘   └─────────────┘   └─────────────┘       │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

### Layer 1: Core (Governance Kernel)

**Module**: `lingframe-core`

**Responsibilities**:

- Plugin Cycle Management (Install, Uninstall, Hot Swap)
- Governance (Permission Check, Authorization, Audit)
- Service Routing (FQSID Routing Table)
- Context Isolation (ClassLoader, Spring Context)

**Key Principles**:

- Core is the sole arbiter.
- Provides no business capabilities, only scheduling and control.
- All cross-plugin calls must pass through Core.

**Core Components**:

| Component                 | Responsibility             |
| ------------------------- | -------------------------- |
| `PluginManager`           | Plugin Install/Uninstall/Routing |
| `PluginRuntime`           | Plugin Runtime Environment |
| `InstancePool`            | Blue-Green Deployment & Versioning |
| `ServiceRegistry`         | Service Registry           |
| `InvocationExecutor`      | Invocation Executor        |
| `PluginLifecycleManager`  | Lifecycle Management       |
| `PermissionService`       | Permission Check & Authorization |
| `AuditManager`            | Audit Logging              |
| `EventBus`                | Event Publish/Subscribe    |
| `GovernanceKernel`        | Governance Kernel          |

### Layer 2: Infrastructure (Infrastructure Layer)

**Module**: `lingframe-infrastructure/*`

**Responsibilities**:

- Encapsulate underlying capabilities (Database, Cache, Message Queue).
- Fine-grained permission interception.
- Audit reporting.

**Implemented**:

| Module                       | Description                | Capability ID |
| ---------------------------- | -------------------------- | ------------- |
| `lingframe-infra-storage`    | DB Access, SQL-level ACL   | `storage:sql` |
| `lingframe-infra-cache`      | Cache Access (TODO)        | `cache:redis` |

**Working Principle**:

Infrastructure plugins intercept underlying calls via a **proxy chain**:

```
Business Plugin calls userRepository.findById()
    │
    ▼ (Transparent, via MyBatis/JPA)
┌─────────────────────────────────────┐
│ Storage Plugin (Infrastructure)      │
│                                      │
│ LingDataSourceProxy                  │
│   └→ LingConnectionProxy             │
│       ├→ LingStatementProxy          │  ← Normal Statement
│       └→ LingPreparedStatementProxy  │  ← PreparedStatement
│                                      │
│ Interception: execute/executeQuery/Update  │
│ 1. PluginContextHolder.get() Get Caller
│ 2. Parse SQL Type (SELECT/INSERT...) │
│ 3. permissionService.isAllowed() Auth│
│ 4. permissionService.audit() Audit   │
└─────────────────────────────────────┘
    │
    ▼ (Permission Query)
┌─────────────────────────────────────┐
│ Core                                 │
│ DefaultPermissionService.isAllowed() │
└─────────────────────────────────────┘
```

> See [Infrastructure Proxy Development](infrastructure-development.md) for details.

### Layer 3: Business Plugins (Business Layer)

**Module**: User-developed plugins

**Responsibilities**:

- Implement business logic.
- Access infrastructure via `PluginContext`.
- Expose services via `@LingService`.

**Key Principles**:

- **Zero Trust**: Cannot directly access Database, Cache, etc.
- All capability calls must pass through Core proxy and authorization.
- Declare required permissions in `plugin.yml`.

## Data Flow

### Business Module Calls Infrastructure

```
┌─────────────────────────────────────────────────────────────┐
│ Business Plugin (User Plugin)                                │
│                                                              │
│   userRepository.findById(id)                               │
│         │                                                    │
└─────────┼────────────────────────────────────────────────────┘
          │ JDBC Call
          ▼
┌─────────────────────────────────────────────────────────────┐
│ Infrastructure Plugin (Storage)                              │
│                                                              │
│   LingPreparedStatementProxy.executeQuery()                 │
│         │                                                    │
│         ├─→ checkPermission()                               │
│         │     │                                              │
│         │     ├─→ PluginContextHolder.get() → "user-plugin" │
│         │     │                                              │
│         │     ├─→ preParsedAccessType (Parsed at construction)│
│         │     │                                              │
│         │     ├─→ permissionService.isAllowed(              │
│         │     │       "user-plugin", "storage:sql", READ)   │
│         │     │                                              │
│         │     └─→ permissionService.audit(...)              │
│         │                                                    │
│         └─→ target.executeQuery()                           │
│                                                              │
└─────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────┐
│ Core                                                         │
│                                                              │
│   DefaultPermissionService.isAllowed()                      │
│         │                                                    │
│         ├─→ Check Whitelist                                  │
│         ├─→ Query Permission Table                           │
│         └─→ Dev Mode Fallback                                │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

> Note: `LingPreparedStatementProxy` pre-parses SQL type at construction and caches it. `LingStatementProxy` parses SQL at each execution.

### Cross-Module Calls (Method 1: @LingReference Injection - Recommended)

**Consumer-Driven Contract**: Order Plugin (Consumer) defines `UserQueryService` interface, User Plugin (Producer) implements it.

```
┌─────────────────────────────────────────────────────────────┐
│ Order Plugin (Consumer)                                      │
│                                                              │
│   // Order defines the interface it needs (in order-api)     │
│   interface UserQueryService { UserDTO findById(userId); }  │
│                                                              │
│   @LingReference                                             │
│   private UserQueryService userQueryService;                │
│                                                              │
│   userQueryService.findById(userId);  // Direct Call        │
│         │                                                    │
└─────────┼────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────┐
│ Core                                                         │
│                                                              │
│   GlobalServiceRoutingProxy.invoke() (JDK Dynamic Proxy)    │
│         │                                                    │
│         ├─→ resolveTargetPluginId() Resolve Target Plugin   │
│         │     ├─→ Check pluginId in annotation              │
│         │     └─→ Iterate all plugins for implementation (Cached)│
│         │                                                    │
│         ├─→ pluginManager.getRuntime(pluginId)              │
│         │                                                    │
│         ▼                                                    │
│   SmartServiceProxy.invoke() (Delegate to Smart Proxy)      │
│         │                                                    │
│         ├─→ PluginContextHolder.set(callerPluginId)         │
│         ├─→ TraceContext.start() Start Tracing              │
│         ├─→ checkPermissionSmartly() Permission Check       │
│         │     ├─→ @RequiresPermission Explicit Declaration  │
│         │     └─→ GovernanceStrategy.inferPermission() Infer│
│         │                                                    │
│         ├─→ activeInstanceRef.get() Get Active Instance     │
│         ├─→ instance.enter() (Ref Count +1)                  │
│         ├─→ TCCL Hijack                                      │
│         ├─→ method.invoke(realBean, args)                   │
│         ├─→ TCCL Restore                                     │
│         ├─→ instance.exit() (Ref Count -1)                   │
│         └─→ recordAuditSmartly() Smart Audit                │
│                                                              │
└─────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────┐
│ User Plugin (Producer)                                       │
│                                                              │
│   // Implements the interface defined by Consumer            │
│   public class UserQueryServiceImpl implements UserQueryService {
│       @LingService(id = "find_user", desc = "Query User")   │
│       public UserDTO findById(String userId) { ... }        │
│   }                                                          │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### Cross-Module Calls (Method 2: FQSID Protocol)

```
┌─────────────────────────────────────────────────────────────┐
│ Order Plugin (Consumer)                                      │
│                                                              │
│   context.invoke("user-plugin:find_user", userId)           │
│         │                                                    │
└─────────┼────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────┐
│ Core                                                         │
│                                                              │
│   CorePluginContext.invoke()                                │
│         │                                                    │
│         ├─→ GovernanceStrategy.inferAccessType() → EXECUTE  │
│         ├─→ permissionService.isAllowed() Permission Check   │
│         │                                                    │
│         ▼                                                    │
│   PluginManager.invokeService()                             │
│         │                                                    │
│         ├─→ protocolServiceRegistry.get(fqsid) Find Route    │
│         │                                                    │
│         ▼                                                    │
│   PluginRuntime.invokeService()                             │
│         │                                                    │
│         ├─→ instance.enter() (Ref Count +1)                  │
│         ├─→ TCCL Hijack                                      │
│         ├─→ serviceMethodCache.get(fqsid) Get Method         │
│         ├─→ method.invoke() Reflection Invoke                │
│         ├─→ TCCL Restore                                     │
│         └─→ instance.exit() (Ref Count -1)                   │
│                                                              │
└─────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────┐
│ User Plugin (Producer)                                       │
│                                                              │
│   @LingService(id = "find_user", desc = "Query User")       │
│   public UserDTO findById(String userId) { ... }            │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### Cross-Module Calls (Method 3: Interface Proxy)

**Consumer-Driven Contract**: Order defines `UserQueryService`, gets implementation via `getService()`.

```
┌─────────────────────────────────────────────────────────────┐
│ Order Plugin (Consumer)                                      │
│                                                              │
│   // Get implementation of the interface defined by Consumer │
│   UserQueryService userService = context.getService(UserQueryService.class).get();
│   userService.findById(userId);                             │
│         │                                                    │
└─────────┼────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────┐
│ Core                                                         │
│                                                              │
│   SmartServiceProxy.invoke() (JDK Dynamic Proxy)            │
│         │                                                    │
│         ├─→ PluginContextHolder.set(callerPluginId)         │
│         ├─→ TraceContext.start() Start Tracing              │
│         ├─→ checkPermissionSmartly() Permission Check       │
│         │     ├─→ @RequiresPermission Explicit Declaration  │
│         │     └─→ GovernanceStrategy.inferPermission() Infer│
│         │                                                    │
│         ├─→ activeInstanceRef.get() Get Active Instance     │
│         ├─→ instance.enter() (Ref Count +1)                  │
│         ├─→ TCCL Hijack                                      │
│         ├─→ method.invoke(realBean, args)                   │
│         ├─→ TCCL Restore                                     │
│         ├─→ instance.exit() (Ref Count -1)                   │
│         └─→ recordAuditSmartly() Smart Audit                │
│                                                              │
└─────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────┐
│ User Plugin (Producer)                                       │
│                                                              │
│   // Implements the interface defined by Consumer            │
│   public class UserQueryServiceImpl implements UserQueryService {
│       public UserDTO findById(String userId) { ... }        │
│   }                                                          │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

> Differences:
>
> - **@LingReference Injection** (Recommended): Auto-injected by `LingReferenceInjector`, uses `GlobalServiceRoutingProxy` for lazy binding and smart routing.
> - **invoke(fqsid)**: Inspects `@LingService` methods via FQSID string.
> - **getService(Class)**: Gets dynamic proxy of interface, auto-routes to implementation.

## Service Invocation Details

### @LingReference Mechanism (Recommended)

`@LingReference` is the recommended way, providing the closest experience to native Spring:

#### Working Principle

```
Host App Start
    │
    ▼
LingReferenceInjector (BeanPostProcessor)
    │
    ├─→ Scan all Beans for @LingReference fields
    │
    ├─→ Call PluginManager.getGlobalServiceProxy()
    │     │
    │     └─→ Create GlobalServiceRoutingProxy
    │
    └─→ Inject proxy object into field via reflection
```

#### Lazy Binding

```
@LingReference Field Call
    │
    ▼
GlobalServiceRoutingProxy.invoke()
    │
    ├─→ resolveTargetPluginId() Dynamic Resolve
    │     ├─→ Check annotation pluginId
    │     ├─→ Query Route Cache (ROUTE_CACHE)
    │     └─→ Iterate plugins for implementation
    │
    ├─→ pluginManager.getRuntime(pluginId) Get Runtime
    │
    └─→ Delegate to SmartServiceProxy for governance
```

#### Example

```java
// Order Plugin (Consumer) defines the interface it needs (in order-api module)
// Path: order-api/src/main/java/com/example/order/api/UserQueryService.java
public interface UserQueryService {
    Optional<UserDTO> findById(String userId);
}

// User Plugin (Producer) implements the interface
// Path: user-plugin/src/main/java/com/example/user/service/UserQueryServiceImpl.java
@Component
public class UserQueryServiceImpl implements UserQueryService {
    @LingService(id = "find_user_by_id", desc = "Query User by ID")
    @Override
    public Optional<UserDTO> findById(String userId) {
        return userRepository.findById(userId).map(this::toDTO);
    }
}

// Usage in Order Plugin
@RestController
public class OrderController {
    
    // Inject the interface defined by Consumer, implemented by User Plugin
    @LingReference
    private UserQueryService userQueryService;
    
    @GetMapping("/orders/{userId}")
    public List<Order> getUserOrders(@PathVariable String userId) {
        // Direct call, framework routes to User Plugin implementation
        UserDTO user = userQueryService.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        return orderService.findByUser(user);
    }
}
```

#### Configuration

| Property   | Description                             | Default |
| ---------- | --------------------------------------- | ------- |
| `pluginId` | Target plugin ID. Auto-discover if empty| Empty   |
| `timeout`  | Timeout (ms)                            | 3000    |

#### Advantages

1. **Lazy Binding**: Proxy created effectively even if plugin is not started; routes dynamically at runtime.
2. **Smart Routing**: Auto-routes to latest plugin version; supports Blue-Green.
3. **Cache Optimization**: Interface-to-plugin mapping is cached.
4. **Fault Isolation**: Explicit exception if plugin is offline.
5. **Dev Friendly**: Closest to Spring native experience.

### FQSID Protocol Call

Suitable for loose coupling, no interface dependency:

```java
@Service
public class OrderService {
    @Autowired
    private PluginContext context;
    
    public Order createOrder(String userId) {
        // Call Service via FQSID directly, returns Optional
        Optional<UserDTO> user = context.invoke("user-plugin:find_user", userId);
        
        if (user.isEmpty()) {
            throw new BusinessException("User not found");
        }
        
        return new Order(user.get());
    }
}
```

### Interface Proxy Call

Suitable where explicit error handling is needed:

```java
@Service
public class OrderService {
    @Autowired
    private PluginContext context;
    
    public Order createOrder(String userId) {
        // Get interface implementation (Provided by User Plugin)
        Optional<UserQueryService> userQueryService = context.getService(UserQueryService.class);
        
        if (userQueryService.isEmpty()) {
            throw new ServiceUnavailableException("User Query Service unavailable");
        }
        
        UserDTO user = userQueryService.get().findById(userId)
                .orElseThrow(() -> new BusinessException("User not found"));
        return new Order(user);
    }
}
```

### Invocation Guide

| Scenario                 | Recommended        | Reason                         |
| ------------------------ | ------------------ | ------------------------------ |
| Host calls Plugin        | @LingReference     | Simple, Lazy Binding           |
| Plugin calls Plugin (Strong)| @LingReference  | Type-safe, IDE friendly        |
| Plugin calls Plugin (Loose)| FQSID Protocol     | No interface dependency        |
| Explicit Error Handling  | Interface Proxy    | Handle unavailability gracefully|
| Dynamic Discovery        | Interface Proxy    | Get available services at runtime|
| Optional call            | @LingReference     | Supports null check (Optional) |

## Isolation Mechanism

### ClassLoader Isolation

LingFrame uses a three-tier ClassLoader architecture to solve type consistency for shared APIs:

```
┌─────────────────────────────────────────────────────────────┐
│                    AppClassLoader                            │
│                    (Host App)                                │
│                                                              │
│   lingframe-api (Contract)                                   │
│   lingframe-core                                             │
│   Spring Boot                                                │
│                                                              │
└────────────────────────┬────────────────────────────────────┘
                         │ parent
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                 SharedApiClassLoader                         │
│                 (Shared API Layer)                           │
│                                                              │
│   order-api.jar (Shared Interface & DTO)                     │
│   user-api.jar                                               │
│   ...                                                        │
│                                                              │
└────────────────────────┬────────────────────────────────────┘
                         │ parent
         ┌───────────────┼───────────────┐
         ▼               ▼               ▼
┌─────────────┐   ┌─────────────┐   ┌─────────────┐
│PluginCL A  │   │PluginCL B  │   │PluginCL C  │
│             │   │             │   │             │
│ Child-First │   │ Child-First │   │ Child-First │
│ Load Self   │   │ Load Self   │   │ Load Self   │
└─────────────┘   └─────────────┘   └─────────────┘
```

**Shared API Configuration** (`application.yaml`):

```yaml
lingframe:
  preload-api-jars:
    - libs/order-api.jar              # JAR File
    - lingframe-examples/order-api    # Maven Module Dir
    - libs/*-api.jar                  # Wildcard
```

**Whitelist Delegation** (Force Parent Load):

- `java.*`, `javax.*`, `jdk.*`, `sun.*`
- `com.lingframe.api.*` (Framework Contract)
- `org.slf4j.*` (Logging Facade)
- **All classes in SharedApiClassLoader** (Auto-detected)

> See [Shared API Guidelines](shared-api-guidelines.md)

### Spring Context Isolation

Each plugin runs in a **completely isolated** Spring ApplicationContext:

```
┌─────────────────────────────────────────────────────────────┐
│              Host Context (Host App)                         │
│                                                              │
│   PluginManager, ContainerFactory, PermissionService        │
│   Common Beans...                                            │
│                                                              │
└─────────────────────────────────────────────────────────────┘

┌─────────────┐   ┌─────────────┐   ┌─────────────┐
│  Context A  │   │  Context B  │   │  Context C  │
│ (Plugin A)  │   │ (Plugin B)  │   │ (Plugin C)  │
│             │   │             │   │             │
│ Indep Beans │   │ Indep Beans │   │ Indep Beans │
│ Indep Config│   │ Indep Config│   │ Indep Config│
└─────────────┘   └─────────────┘   └─────────────┘
```

> **Design Note**: Plugin contexts are NOT child contexts of the host.
> This is intentional for:
> 1. **Zero Trust**: Plugins cannot directly access host beans via `@Autowired`
> 2. **Clean Unload**: No parent-child references that could cause ClassLoader leaks
> 3. **True Isolation**: Each plugin is a self-contained Spring Boot application
>
> Core beans (`PluginManager`, `PluginContext`) are manually injected via `registerBeans()`.

## Lifecycle

### Plugin Installation Flow

```
PluginManager.install(pluginId, version, jarFile)
    │
    ├─→ Security Verify (DangerousApiVerifier)
    │
    ├─→ createPluginClassLoader(file)     // Child-First CL
    │
    ├─→ containerFactory.create()          // SPI Create Container
    │
    ├─→ Create PluginInstance
    │
    ├─→ Get or Create PluginRuntime
    │
    ├─→ runtime.addInstance(instance, context, isDefault)  // Blue-Green
    │       │
    │       ├─→ instancePool.add(instance)     // Add to Pool
    │       ├─→ container.start(context)       // Start Spring Child Ctx
    │       ├─→ serviceRegistry.register()     // Register @LingService
    │       ├─→ plugin.onStart(context)        // Lifecycle Callback
    │       └─→ instancePool.setDefault(instance)  // Set as Default
    │
    └─→ Old version enters dying queue, destroy after ref count zero
```

### Blue-Green Deployment

```
Timeline ─────────────────────────────────────────────────────→

v1.0 Running
    │
    │  New Version Install Request
    ▼
┌─────────────────────────────────────────────────────────────┐
│ v1.0 (active)                                                │
│ Processing Requests                                           │
│                                                              │
│                    v2.0 Starting...                          │
│                    ┌─────────────────────────────────────┐  │
│                    │ Create ClassLoader                   │  │
│                    │ Start Spring Context                 │  │
│                    │ Register @LingService                │  │
│                    └─────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
    │
    │  Atomic Switch
    ▼
┌─────────────────────────────────────────────────────────────┐
│ v2.0 (active)                                                │
│ Accepting New Requests                                        │
│                                                              │
│ v1.0 (dying)                                                 │
│ Draining Requests, Ref Count Decreasing                       │
└─────────────────────────────────────────────────────────────┘
    │
    │  Ref Count Zero
    ▼
┌─────────────────────────────────────────────────────────────┐
│ v2.0 (active)                                                │
│                                                              │
│ v1.0 Destroy                                                 │
│ - plugin.onStop()                                           │
│ - Spring Context.close()                                    │
│ - ClassLoader Release                                       │
└─────────────────────────────────────────────────────────────┘
```

## Module Mapping

| Layer          | Maven Module                     | Description          |
| -------------- | -------------------------------- | -------------------- |
| Core           | `lingframe-core`                 | Governance Kernel    |
| Core           | `lingframe-api`                  | Contract (Interface) |
| Core           | `lingframe-spring-boot3-starter` | Spring Boot Integration|
| Infrastructure | `lingframe-infra-storage`      | Storage Proxy        |
| Infrastructure | `lingframe-infra-cache`        | Cache Proxy          |
| Business       | User Plugins                     | Business Logic       |
