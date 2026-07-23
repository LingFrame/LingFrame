# Business Ling Development Guide

This guide explains how to write a business ling that respects the current public runtime boundaries.

---

## What Exactly Is a Ling?

A ling is a business unit that:

- Runs inside the LingCore process.
- Owns its own classloader and lifecycle.
- Exposes services through LingFrame contracts.
- Runs under the governance kernel.

If you are unfamiliar with the terminology, please review the [Glossary & FAQ](faq.md) first.

---

## What Does a Minimum Viable Ling Need?

- A Maven module
- An entry class implementing `Ling`
- A `ling.yml` descriptor file

### 1. Maven Dependencies

```xml
<dependencies>
    <dependency>
        <groupId>com.lingframe</groupId>
        <artifactId>lingframe-api</artifactId>
        <version>${lingframe.version}</version>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter</artifactId>
    </dependency>
</dependencies>
```

### 2. Entry Class

```java
@SpringBootApplication
public class MyLing implements Ling {

    @Override
    public void onStart(LingContext context) {
        System.out.println("Ling started: " + context.getLingId());
    }

    @Override
    public void onStop(LingContext context) {
        System.out.println("Ling stopped: " + context.getLingId());
    }
}
```

### 3. `ling.yml`

```yaml
id: my-ling
version: 1.0.0
description: My first ling
mainClass: com.example.myling.MyLing
```

---

## How to Expose Services

Use `@LingService` on the producer implementation class.

LingFrame follows the "consumer-driven contract" model:

- The consumer defines the interface it needs.
- The producer ling implements that interface.

```java
public interface UserQueryService {
    Optional<UserDTO> findById(String userId);
}
```

```java
@Component
public class UserQueryServiceImpl implements UserQueryService {

    @LingService(id = "find_user", desc = "Query user by ID")
    @Override
    public Optional<UserDTO> findById(String userId) {
        return userRepository.findById(userId).map(this::toDTO);
    }
}
```

The final service identity format is: `lingId:serviceId`.

---

## How to Invoke Other Lings

The recommended order of approach is:

### Method 1: `@LingReference`

```java
@Component
public class OrderService {

    @LingReference
    private UserQueryService userQueryService;

    public Order createOrder(String userId) {
        UserDTO user = userQueryService.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return new Order(user);
    }
}
```

### Method 2: `LingContext.getService()`

Use this when you want to explicitly handle the "service existence" check.

### Method 3: `LingContext.invoke()`

Use this only when you specifically want tighter decoupling via the FQSID (Fully Qualified Service ID).

---

## How to Declare Governance Requirements

### Declare Permissions in `ling.yml`

```yaml
governance:
  permissions:
    - methodPattern: "storage:sql"
      permissionId: "READ"
    - methodPattern: "cache:local"
      permissionId: "WRITE"
```

### Supplement Semantics via Annotations

```java
@RequiresPermission("user:write")
@Auditable(action = "CREATE_USER", resource = "user")
public UserDTO createUser(CreateUserRequest request) {
    ...
}
```

### Dev Mode

```yaml
lingframe:
  dev-mode: true
```

---

## How to Package and Load

### Development Path

Point LingCore to the source code root and recompile the ling while developing.

### Production Path

Package the ling into a jar and place it in the `ling-home` directory.

```bash
mvn clean package
```

---

## What the Runtime Has Already Done For You

In the current implementation, a business ling does not need to implement the governance kernel itself.

The runtime already provides:

- Unified invocation governance
- Lifecycle coordination
- Canary routing
- Simulation support
- Unload cleanup hooks
- Leak diagnostics

Your primary responsibilities are:

- Write the contract clearly.
- Write the business implementation cleanly.
- Declare permissions honestly.

---

## Best Practices

- Keep your first batch of lings as small as possible.
- Put only contracts in the `Shared API`.
- Prefer `@LingReference` for your first invocation path.
- Explicitly declare permissions in `ling.yml`.
- Use SLF4J logging.
- Do not let ordinary business lings directly depend on `lingframe-core`.

Next steps: if you want to understand the contract boundary, head to [Shared API Guidelines](shared-api-guidelines.md); if you want to understand the infrastructure proxy model, head to [Infrastructure Development Guide](infrastructure-development.md).
