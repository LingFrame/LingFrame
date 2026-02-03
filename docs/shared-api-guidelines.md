# Shared API Design Guidelines

## Architecture Overview

```
Host ClassLoader (AppClassLoader)
    ↓ parent
SharedApiClassLoader (Shared API Layer)
    ↓ parent
PluginClassLoader (Plugin Implementation Layer)
```

## Core Design Principles

### 1. API Provided by Consumer (Consumer-Driven Contract)

```
┌─────────────────────────────────────────────────────────────┐
│                 Consumer-Driven Contract Pattern             │
└─────────────────────────────────────────────────────────────┘

Scenario: Order Plugin needs to query user info

┌─────────────┐     Needs Capability     ┌─────────────┐
│ Order Plugin│ ───────────────▶ │ User Plugin  │
│ (Consumer)  │                  │ (Producer)   │
└─────────────┘                  └─────────────┘
       │                               ▲
       │ 1. Define required interface     │ 2. Implement interface defined by Consumer
       ▼                               │
┌─────────────────────────────────────────────────────────────┐
│                    order-api Module                         │
│       (Defined and maintained by Consumer Order Plugin)     │
│                                                              │
│   public interface UserQueryService {                       │
│       Optional<UserDTO> findById(String userId);            │
│   }                                                          │
└─────────────────────────────────────────────────────────────┘
```

**Core Principles**:
- API interface is defined and maintained by **Consumer** (Who needs the capability, defines the interface).
- Producer **implements** the interface defined by Consumer (Who has the capability, provides implementation).
- Consumer knows best what functionality it needs, so interface design fits actual needs better.

**Why this design?**
- Traditional Pattern: User Plugin defines `UserService`, all consumers adapt to Producer's interface.
- Consumer-Driven: Order Plugin defines `UserQueryService` (containing only methods it needs), User Plugin adapts to Consumer's need.
- Advantage: Decoupling is more thorough, Consumer does not depend on Producer's full interface, allowing independent evolution.

---

## API Module Structure

### 2. API Module Only Contains Interfaces and DTOs

Consumer (Order Plugin) defines interfaces it needs, Producer (User Plugin) implements them:

```
order-api/                              # API Module of Consumer Order Plugin
├── src/main/java/com/example/order/
│   ├── api/
│   │   ├── UserQueryService.java      # User query capability needed by Order (Implemented by User Plugin)
│   │   └── PaymentService.java        # Payment capability needed by Order (Implemented by Payment Plugin)
│   └── dto/
│       ├── UserDTO.java               # User Data Transfer Object
│       └── PaymentResultDTO.java
└── pom.xml
```

**Should NOT Contain**:
- ❌ Business Logic Implementation
- ❌ Database Access Code
- ❌ Spring Components (@Service, @Repository, etc.)
- ❌ Governance Logic (Circuit Breaking, Retry, etc.)

### 3. DTO Design Guidelines

```java
// ✅ Correct: Simple POJO, Serializable
@Data
public class OrderDTO implements Serializable {
    private Long id;
    private String orderNo;
    private BigDecimal amount;
    private LocalDateTime createTime;
}

// ❌ Incorrect: Contains business logic or complex dependencies
public class OrderDTO {
    private Order order;  // Do not reference entity class
    public void process() { ... }  // No business methods
}
```

### 3. Avoid Heavy Dependencies

API module dependencies should be minimal:

```xml
<!-- ✅ Recommended Dependencies -->
<dependencies>
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <scope>provided</scope>
    </dependency>
</dependencies>

<!-- ❌ Avoid Dependencies -->
<!-- Do not introduce Spring, DB drivers, etc. -->
```

## API Evolution Principles

### 4. Backward Compatibility (Highly Recommended)

```java
// ✅ Correct: Add only, do not modify
interface OrderService {
    Order getOrder(Long id);           // v1 Kept
    List<Order> batchGet(List<Long> ids); // v2 Added
}

// ❌ Incorrect: Modify existing method signature
interface OrderService {
    OrderDTO getOrder(String orderId); // Breaks compatibility!
}
```

### 5. Use Versioned Package Names for Breaking Changes

```java
// Version 1
package com.example.order.api.v1;
public interface OrderService { ... }

// Version 2 (Incompatible)
package com.example.order.api.v2;
public interface OrderService { ... }
```

Both versions can coexist in SharedApiClassLoader.

## Canary Release Support

| Scenario | Supported | Handling Method |
| -------- | --------- | --------------- |
| Add API Method | ✅ | Incrementally add JAR |
| Breaking Change | ✅ | Versioned Package Name |
| Coexistence of Old/New Plugins | ✅ | API Backward Compatibility |

### Canary Flow Example

```
T0: PluginA-v1 + API-v1
T1: Add API-v2, Deploy PluginA-v2 (v1/v2 Coexist)
T2: Verify pass, Uninstall PluginA-v1
```

## Configuration Example

```yaml
lingframe:
  preload-api-jars:
    - api/order-api-*.jar      # Wildcard load multiple versions
    - api/user-api/            # Directory auto scan
    - lingframe-examples/lingframe-example-order-api  # Maven Module (Dev Mode)
```

## FAQ

### Q: ClassNotFoundException / NoClassDefFoundError

**Cause**: API not correctly loaded into SharedApiClassLoader

**Check**:
1. Confirm `preload-api-jars` configuration is correct.
2. Confirm JAR/Directory path exists.
3. Check startup logs for `📦 [SharedApi]` output.

### Q: ClassCastException

**Cause**: Same class loaded by different ClassLoaders

**Solution**: Ensure API classes are ONLY loaded in SharedApiClassLoader, do not package them repeatedly in Plugin JARs.
