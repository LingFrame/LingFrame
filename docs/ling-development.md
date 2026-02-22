# Business Unit Development Guide

This document introduces how to develop LingFrame business units (Lings).

## Create ling Project

### 1. Maven Configuration

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

### 2. ling Entry Class

```java
package com.example.myling;

import com.lingframe.api.context.LingContext;
import com.lingframe.api.ling.Ling;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MyLing implements Ling {

    @Override
    public void onStart(LingContext context) {
        System.out.println("Ling Started: " + context.getLingId());
    }

    @Override
    public void onStop(LingContext context) {
        System.out.println("Ling Stopped: " + context.getLingId());
    }
}
```

### 3. ling Metadata

Create `src/main/resources/ling.yml`:

```yaml
# Basic Metadata
id: my-ling
version: 1.0.0
provider: "My Company"
description: "My ling"
mainClass: "com.example.myling.MyLing"

# Dependencies (Optional)
dependencies:
  - id: "base-ling"
    minVersion: "1.0.0"

# Governance Configuration
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

# Custom Properties (Optional)
properties:
  custom-config: "value"
  timeout: 5000
```

## Expose Service

Use `@LingService` annotation to expose services.

**Consumer-Driven Contract**: Interfaces are defined by consumers and implemented by producers. For example, Order unit defines `UserQueryService`, and User unit implements it:

```java
// ========== Consumer Defines Interface (in order-api unit) ==========
public interface UserQueryService {
    Optional<UserDTO> findById(String userId);
}

// ========== Producer Implements Interface (in user-ling unit) ==========
package com.example.user.service;

import com.lingframe.api.annotation.Auditable;
import com.lingframe.api.annotation.LingService;
import com.lingframe.api.annotation.RequiresPermission;
import org.springframework.stereotype.Component;

@Component
public class UserQueryServiceImpl implements UserQueryService {

    @LingService(id = "find_user", desc = "Query user by ID")
    @Override
    public Optional<UserDTO> findById(String userId) {
        return userRepository.findById(userId).map(this::toDTO);
    }
}

// Another example: With permission and audit
@Component  
public class UserAdminServiceImpl implements UserAdminService {

    @LingService(id = "create_user", desc = "Create new user", timeout = 5000)
    @RequiresPermission("user:write")
    @Auditable(action = "CREATE_USER", resource = "user")
    @Override
    public UserDTO createUser(CreateUserRequest request) {
        return toDTO(userRepository.save(toEntity(request)));
    }
}
```

### @LingService Properties

| Property | Description | Default |
| -------- | ----------- | ------- |
| `id` | Service Short ID, forms FQSID | Required |
| `desc` | Service Description | Empty |
| `timeout` | Timeout (ms) | 3000 |

### FQSID Format

Global Unique Service Identifier: `lingId:serviceId`

Example: `user-ling:find_user`

## Call Other ling Services

LingFrame provides three invocation methods, with the following recommended priority:

### Method 1: @LingReference Injection (Highly Recommended)

This is the closest to native Spring experience.

**Consumer-Driven Contract**: Order ling (Consumer) defines the interface it needs, User/Payment ling (Producer) implements it:

```java
// ========== Step 1: Consumer Defines Interface (in order-api unit) ==========
// Location: order-api/src/main/java/com/example/order/api/

// Order ling defines the user query capability it needs
public interface UserQueryService {
    Optional<UserDTO> findById(String userId);
}

// Order ling defines the payment capability it needs
public interface PaymentService {
    PaymentResult processPayment(String userId, BigDecimal amount);
}

// ========== Step 2: Producer Implements Interface (in respective Lings) ==========
// User ling implements UserQueryService defined by Order
// Location: user-ling/src/main/java/.../UserQueryServiceImpl.java
@Component
public class UserQueryServiceImpl implements UserQueryService {
    @LingService(id = "find_user", desc = "Query User")
    @Override
    public Optional<UserDTO> findById(String userId) {
        return userRepository.findById(userId).map(this::toDTO);
    }
}

// Payment ling implements PaymentService defined by Order
// Location: payment-ling/src/main/java/.../PaymentServiceImpl.java
@Component
public class PaymentServiceImpl implements PaymentService {
    @LingService(id = "process_payment", desc = "Process Payment", timeout = 5000)
    @Override
    public PaymentResult processPayment(String userId, BigDecimal amount) {
        return paymentGateway.charge(userId, amount);
    }
}

// ========== Step 3: Consumer Uses Interface Defined by Itself ==========
// Location: order-ling/src/main/java/.../OrderService.java
@Component
public class OrderService {

    @LingReference
    private UserQueryService userQueryService;  // Auto injected, implemented by User ling

    @LingReference
    private PaymentService paymentService;  // Auto injected, implemented by Payment ling

    public Order createOrder(String userId, List<Item> items) {
        // Direct call, framework auto-routes to producer implementation
        UserDTO user = userQueryService.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        BigDecimal total = calculateTotal(items);
        PaymentResult result = paymentService.processPayment(userId, total);
        
        return new Order(user, items, result);
    }
}
```

**@LingReference Pros:**
- Cleanest code, closest to Spring native experience.
- Supports lazy binding, proxy created even if ling not started.
- Auto-routes to latest ling version.
- Supports optional lingId specification and timeout configuration.
- Smart routing via GlobalServiceRoutingProxy.

### Method 2: LingContext.getService()

Suitable where explicit error handling is needed:

```java
@Component
public class OrderService {

    @Autowired
    private LingContext context;

    public Order createOrder(String userId, List<Item> items) {
        // Get implementation of the interface defined by Consumer (Provided by User ling)
        Optional<UserQueryService> userQueryService = context.getService(UserQueryService.class);
        
        if (userQueryService.isEmpty()) {
            throw new RuntimeException("User Query Service Unavailable");
        }
        
        UserDTO user = userQueryService.get().findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return new Order(user, items);
    }
}
```

### Method 3: LingContext.invoke() FQSID Call

Suitable for loose coupling, no interface dependency:

```java
@Component
public class OrderService {

    @Autowired
    private LingContext context;

    public Order createOrder(String userId, List<Item> items) {
        // Direct call to producer service via FQSID
        Optional<UserDTO> user = context.invoke("user-ling:find_user", userId);
        
        if (user.isEmpty()) {
            throw new RuntimeException("User not found");
        }
        
        return new Order(user.get(), items);
    }
}
```

## Permission Declaration

### Explicit Declaration

```java
@RequiresPermission("user:export")
public void exportUsers() { ... }
```

### Smart Inference

Framework automatically infers permission based on method name prefix:

| Prefix | AccessType |
| ------ | ---------- |
| `get`, `find`, `query`, `list`, `select` | READ |
| `create`, `save`, `insert`, `update`, `delete` | WRITE |
| Others | EXECUTE |

### Development Mode

Enable loose mode in configuration during development, permission denied only warns:

```yaml
lingframe:
  dev-mode: true
```

## Audit Log

### Explicit Declaration

```java
@Auditable(action = "EXPORT_DATA", resource = "user")
public void exportUsers() { ... }
```

### Auto Audit

Write operations (WRITE) and Execute operations (EXECUTE) are automatically audited.

## Event Communication

### Publish Event

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

// Publish
context.publishEvent(new UserCreatedEvent("123"));
```

### Listen Event

```java
@Component
public class UserEventListener implements LingEventListener<UserCreatedEvent> {

    @Override
    public void onEvent(UserCreatedEvent event) {
        System.out.println("User Created: " + event.getUserId());
    }
}
```

## Package & Deploy

### Production Mode

```bash
mvn clean package
# Generates target/my-ling.jar
```

Place JAR in LingCore application's `Lings` directory, framework will auto scan and load.

### Development Mode

Point to compilation output directory in LINGCORE app configuration:

```yaml
lingframe:
  dev-mode: true
  Ling-roots:
    - "../my-ling"
```

After modifying code and recompiling, HotSwapWatcher auto-detects changes in `target/classes` and hot reloads.

## Framework Governance Capabilities

The following capabilities are provided by **LingFrame Core**, business units do not need to implement them:

| Capability | Description | What ling Needs To Do |
| ---------- | ----------- | ----------------------- |
| Circuit Breaking | Auto break when service unavailable | Nothing |
| Degrade | Return fallback response after break | Optional: Provide `@Fallback` method |
| Retry | Auto retry on failure | Nothing |
| Timeout | Call timeout control | Configurable in `@LingService` |
| Rate Limiting | Request frequency limit | Nothing |
| Monitoring | Tracing | Nothing |

**Benefit**: Business units only focus on business logic, governance policies can be dynamically adjusted via configuration.

## Best Practices

1. **Consumer-Driven Contract**: Consumer defines interface, producer implements (See [Shared API Guidelines](shared-api-guidelines.md)).
2. **Invocation Priority**: @LingReference > getService() > invoke().
3. **Minimize Dependencies**: Lings should only depend on `lingframe-api`, not `lingframe-core`.
4. **Permission Declaration**: Declare required permissions in `ling.yml`.
5. **Service Granularity**: One `@LingService` per business operation.
6. **Exception Handling**: Use `LingException` and its subclasses.
7. **Logging Standard**: Use SLF4J, avoid System.out.
8. **Interface Design**: Consumer defines lean interfaces containing only required methods.

## FAQ

### ClassNotFoundException

Check ClassLoader isolation, ensure dependent classes are in ling JAR or visible to parent loader.

### Permission Denied

1. Check permission declaration in `ling.yml`.
2. Enable `lingframe.dev-mode: true` during development.

### @LingReference Injection Failed

1. Ensure target ling is started and registered corresponding Service Bean.
2. Check interface type match.
3. If lingId is specified, ensure it is correct.

### Hot Swap Not Working

1. Ensure `lingframe.dev-mode: true` is configured.
2. Ensure `Ling-roots` points to ling's `target/classes` directory.
3. Wait 500ms after recompilation (Debounce delay).
