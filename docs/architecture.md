# Architecture Design

This document describes the core architecture design and implementation principles of LingFrame.

## Design Philosophy

LingFrame draws inspiration from operating system design principles:

- **Microkernel**: Core is responsible only for scheduling and arbitration, containing no business logic.
- **Zero Trust**: Business units cannot directly access infrastructure; they must go through the Core proxy.
- **Capability Isolation**: Each ling runs in an independent ClassLoader and Spring Context.

## Three-Tier Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      LingCore Application                        │
│                                                              │
│  ┌───────────────────────────────────────────────────────┐  │
│  │                 Core (Governance Kernel)                │  │
│  │                                                        │  │
│  │   LingManager · PermissionService · EventBus        │  │
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
│  │  ling     │   │   ling    │   │  ling     │       │
│  │             │   │             │   │             │       │
│  │ Business Layer│   │ Business Layer│   │ Business Layer│       │
│  └─────────────┘   └─────────────┘   └─────────────┘       │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

### Layer 1: Core (Governance Kernel)

**Unit**: `lingframe-core`

**Responsibilities**:

- ling Cycle Management (Install, Uninstall, Hot Swap)
- Governance (Permission Check, Authorization, Audit)
- Service Routing (FQSID Routing Table)
- Context Isolation (ClassLoader, Spring Context)

**Key Principles**:

- Core is the sole arbiter.
- Provides no business capabilities, only scheduling and control.
- All cross-ling calls must pass through Core.

**Core Components**:

| Component                 | Responsibility             |
| ------------------------- | -------------------------- |
| `LingManager`           | ling Install/Uninstall/Routing |
| `LingRuntime`           | ling Runtime Environment |
| `InstancePool`            | Blue-Green Deployment & Versioning |
| `ServiceRegistry`         | Service Registry           |
| `InvocationExecutor`      | Invocation Executor        |
| `LingLifecycleManager`  | Lifecycle Management       |
| `PermissionService`       | Permission Check & Authorization |
| `AuditManager`            | Audit Logging              |
| `EventBus`                | Event Publish/Subscribe    |
| `GovernanceKernel`        | Governance Kernel          |

### Layer 2: Infrastructure (Infrastructure Layer)

**Unit**: `lingframe-infrastructure/*`

**Responsibilities**:

- Encapsulate underlying capabilities (Database, Cache, Message Queue).
- Fine-grained permission interception.
- Audit reporting.

**Implemented**:

| Unit                       | Description                | Capability ID |
| ---------------------------- | -------------------------- | ------------- |
| `lingframe-infra-storage`    | DB Access, SQL-level ACL   | `storage:sql` |
| `lingframe-infra-cache`      | Cache Access (TODO)        | `cache:redis` |

**Working Principle**:

Infrastructure Lings intercept underlying calls via a **proxy chain**:

```
Business ling calls userRepository.findById()
    │
    ▼ (Transparent, via MyBatis/JPA)
┌─────────────────────────────────────┐
│ Storage ling (Infrastructure)      │
│                                      │
│ LingDataSourceProxy                  │
│   └→ LingConnectionProxy             │
│       ├→ LingStatementProxy          │  ← Normal Statement
│       └→ LingPreparedStatementProxy  │  ← PreparedStatement
│                                      │
│ Interception: execute/executeQuery/Update  │
│ 1. LingContextHolder.get() Get Caller
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

### Layer 3: Business Lings (Business Layer)

**Unit**: User-developed Lings

**Responsibilities**:

- Implement business logic.
- Access infrastructure via `LingContext`.
- Expose services via `@LingService`.

**Key Principles**:

- **Zero Trust**: Cannot directly access Database, Cache, etc.
- All capability calls must pass through Core proxy and authorization.
- Declare required permissions in `ling.yml`.

## Data Flow

### Business Unit Calls Infrastructure

```
┌─────────────────────────────────────────────────────────────┐
│ Business ling (User ling)                                │
│                                                              │
│   userRepository.findById(id)                               │
│         │                                                    │
└─────────┼────────────────────────────────────────────────────┘
          │ JDBC Call
          ▼
┌─────────────────────────────────────────────────────────────┐
│ Infrastructure ling (Storage)                              │
│                                                              │
│   LingPreparedStatementProxy.executeQuery()                 │
│         │                                                    │
│         ├─→ checkPermission()                               │
│         │     │                                              │
│         │     ├─→ LingContextHolder.get() → "user-ling" │
│         │     │                                              │
│         │     ├─→ preParsedAccessType (Parsed at construction)│
│         │     │                                              │
│         │     ├─→ permissionService.isAllowed(              │
│         │     │       "user-ling", "storage:sql", READ)   │
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

### Cross-Unit Calls (Method 1: @LingReference Injection - Recommended)

**Consumer-Driven Contract**: Order ling (Consumer) defines `UserQueryService` interface, User ling (Producer) implements it.

```
┌─────────────────────────────────────────────────────────────┐
│ Order ling (Consumer)                                      │
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
│         ├─→ resolveTargetLingId() Resolve Target ling   │
│         │     ├─→ Check lingId in annotation              │
│         │     └─→ Iterate all Lings for implementation (Cached)│
│         │                                                    │
│         ├─→ LingManager.getRuntime(lingId)              │
│         │                                                    │
│         ▼                                                    │
│   SmartServiceProxy.invoke() (Delegate to Smart Proxy)      │
│         │                                                    │
│         ├─→ LingContextHolder.set(callerLingId)         │
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
│ User ling (Producer)                                       │
│                                                              │
│   // Implements the interface defined by Consumer            │
│   public class UserQueryServiceImpl implements UserQueryService {
│       @LingService(id = "find_user", desc = "Query User")   │
│       public UserDTO findById(String userId) { ... }        │
│   }                                                          │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### Cross-Unit Calls (Method 2: FQSID Protocol)

```
┌─────────────────────────────────────────────────────────────┐
│ Order ling (Consumer)                                      │
│                                                              │
│   context.invoke("user-ling:find_user", userId)           │
│         │                                                    │
└─────────┼────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────┐
│ Core                                                         │
│                                                              │
│   CoreLingContext.invoke()                                │
│         │                                                    │
│         ├─→ GovernanceStrategy.inferAccessType() → EXECUTE  │
│         ├─→ permissionService.isAllowed() Permission Check   │
│         │                                                    │
│         ▼                                                    │
│   LingManager.invokeService()                             │
│         │                                                    │
│         ├─→ protocolServiceRegistry.get(fqsid) Find Route    │
│         │                                                    │
│         ▼                                                    │
│   LingRuntime.invokeService()                             │
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
│ User ling (Producer)                                       │
│                                                              │
│   @LingService(id = "find_user", desc = "Query User")       │
│   public UserDTO findById(String userId) { ... }            │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### Cross-Unit Calls (Method 3: Interface Proxy)

**Consumer-Driven Contract**: Order defines `UserQueryService`, gets implementation via `getService()`.

```
┌─────────────────────────────────────────────────────────────┐
│ Order ling (Consumer)                                      │
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
│         ├─→ LingContextHolder.set(callerLingId)         │
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
│ User ling (Producer)                                       │
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
LINGCORE App Start
    │
    ▼
LingReferenceInjector (BeanPostProcessor)
    │
    ├─→ Scan all Beans for @LingReference fields
    │
    ├─→ Call LingManager.getGlobalServiceProxy()
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
    ├─→ resolveTargetLingId() Dynamic Resolve
    │     ├─→ Check annotation lingId
    │     ├─→ Query Route Cache (ROUTE_CACHE)
    │     └─→ Iterate Lings for implementation
    │
    ├─→ LingManager.getRuntime(lingId) Get Runtime
    │
    └─→ Delegate to SmartServiceProxy for governance
```

#### Example

```java
// Order ling (Consumer) defines the interface it needs (in order-api unit)
// Path: order-api/src/main/java/com/example/order/api/UserQueryService.java
public interface UserQueryService {
    Optional<UserDTO> findById(String userId);
}

// User ling (Producer) implements the interface
// Path: user-ling/src/main/java/com/example/user/service/UserQueryServiceImpl.java
@Component
public class UserQueryServiceImpl implements UserQueryService {
    @LingService(id = "find_user_by_id", desc = "Query User by ID")
    @Override
    public Optional<UserDTO> findById(String userId) {
        return userRepository.findById(userId).map(this::toDTO);
    }
}

// Usage in Order ling
@RestController
public class OrderController {
    
    // Inject the interface defined by Consumer, implemented by User ling
    @LingReference
    private UserQueryService userQueryService;
    
    @GetMapping("/orders/{userId}")
    public List<Order> getUserOrders(@PathVariable String userId) {
        // Direct call, framework routes to User ling implementation
        UserDTO user = userQueryService.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        return orderService.findByUser(user);
    }
}
```

#### Configuration

| Property   | Description                             | Default |
| ---------- | --------------------------------------- | ------- |
| `lingId` | Target ling ID. Auto-discover if empty| Empty   |
| `timeout`  | Timeout (ms)                            | 3000    |

#### Advantages

1. **Lazy Binding**: Proxy created effectively even if ling is not started; routes dynamically at runtime.
2. **Smart Routing**: Auto-routes to latest ling version; supports Blue-Green.
3. **Cache Optimization**: Interface-to-ling mapping is cached.
4. **Fault Isolation**: Explicit exception if ling is offline.
5. **Dev Friendly**: Closest to Spring native experience.

### FQSID Protocol Call

Suitable for loose coupling, no interface dependency:

```java
@Service
public class OrderService {
    @Autowired
    private LingContext context;
    
    public Order createOrder(String userId) {
        // Call Service via FQSID directly, returns Optional
        Optional<UserDTO> user = context.invoke("user-ling:find_user", userId);
        
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
    private LingContext context;
    
    public Order createOrder(String userId) {
        // Get interface implementation (Provided by User ling)
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
| LINGCORE calls ling        | @LingReference     | Simple, Lazy Binding           |
| ling calls ling (Strong)| @LingReference  | Type-safe, IDE friendly        |
| ling calls ling (Loose)| FQSID Protocol     | No interface dependency        |
| Explicit Error Handling  | Interface Proxy    | Handle unavailability gracefully|
| Dynamic Discovery        | Interface Proxy    | Get available services at runtime|
| Optional call            | @LingReference     | Supports null check (Optional) |

## Isolation Mechanism

### ClassLoader Isolation

LingFrame uses a three-tier ClassLoader architecture to solve type consistency for shared APIs:

```
┌─────────────────────────────────────────────────────────────┐
│                    AppClassLoader                            │
│                    (LINGCORE App)                                │
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
│LingCL A  │   │LingCL B  │   │LingCL C  │
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
    - lingframe-examples/order-api    # Maven Unit Dir
    - libs/*-api.jar                  # Wildcard
```

**Whitelist Delegation** (Force Parent Load):

- `java.*`, `javax.*`, `jdk.*`, `sun.*`
- `com.lingframe.api.*` (Framework Contract)
- `org.slf4j.*` (Logging Facade)
- **All classes in SharedApiClassLoader** (Auto-detected)

> See [Shared API Guidelines](shared-api-guidelines.md)

### Spring Context Isolation

Each ling runs in a **completely isolated** Spring ApplicationContext:

```
┌─────────────────────────────────────────────────────────────┐
│              LINGCORE Context (LINGCORE App)                         │
│                                                              │
│   LingManager, ContainerFactory, PermissionService        │
│   Common Beans...                                            │
│                                                              │
└─────────────────────────────────────────────────────────────┘

┌─────────────┐   ┌─────────────┐   ┌─────────────┐
│  Context A  │   │  Context B  │   │  Context C  │
│ (ling A)  │   │ (ling B)  │   │ (ling C)  │
│             │   │             │   │             │
│ Indep Beans │   │ Indep Beans │   │ Indep Beans │
│ Indep Config│   │ Indep Config│   │ Indep Config│
└─────────────┘   └─────────────┘   └─────────────┘
```

> **Design Note**: ling contexts are NOT child contexts of the LINGCORE.
> This is intentional for:
> 1. **Zero Trust**: Lings cannot directly access LingCore beans via `@Autowired`
> 2. **Clean Unload**: No parent-child references that could cause ClassLoader leaks
> 3. **True Isolation**: Each ling is a self-contained Spring Boot application
>
> Core beans (`LingManager`, `LingContext`) are manually injected via `registerBeans()`.

## Lifecycle

### ling Installation Flow

```
LingManager.install(lingId, version, jarFile)
    │
    ├─→ Security Verify (DangerousApiVerifier)
    │
    ├─→ createLingClassLoader(file)     // Child-First CL
    │
    ├─→ containerFactory.create()          // SPI Create Container
    │
    ├─→ Create LingInstance
    │
    ├─→ Get or Create LingRuntime
    │
    ├─→ runtime.addInstance(instance, context, isDefault)  // Blue-Green
    │       │
    │       ├─→ instancePool.add(instance)     // Add to Pool
    │       ├─→ container.start(context)       // Start Spring Child Ctx
    │       ├─→ serviceRegistry.register()     // Register @LingService
    │       ├─→ ling.onStart(context)        // Lifecycle Callback
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
│ - ling.onStop()                                           │
│ - Spring Context.close()                                    │
│ - ClassLoader Release                                       │
└─────────────────────────────────────────────────────────────┘
```

## Unit Mapping

| Layer          | Maven Unit                     | Description          |
| -------------- | -------------------------------- | -------------------- |
| Core           | `lingframe-core`                 | Governance Kernel    |
| Core           | `lingframe-api`                  | Contract (Interface) |
| Core           | `lingframe-spring-boot3-starter` | Spring Boot Integration|
| Infrastructure | `lingframe-infra-storage`      | Storage Proxy        |
| Infrastructure | `lingframe-infra-cache`        | Cache Proxy          |
| Business       | User Lings                     | Business Logic       |
