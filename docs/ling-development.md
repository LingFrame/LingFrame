# Ling Development Guide

This guide explains how to build a ling that fits the current public runtime boundary.

It is written for developers who are new to LingFrame, so it starts from the smallest working shape instead of the most feature-rich one.

---

## What A Ling Is

A ling is a business runtime unit that:

- is loaded into LingCore at runtime
- has its own classloader and lifecycle
- exposes services through LingFrame contracts
- runs under governance instead of bypassing the kernel

If you are still mapping the vocabulary, see [Glossary](glossary.md).

---

## The Smallest Useful Ling

Your ling needs three things:

- a Maven module
- an entry class implementing `Ling`
- a `ling.yml` descriptor

### 1. Maven setup

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

### 2. Entry class

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

## Exposing Services

Use `@LingService` on the producer implementation.

LingFrame follows a consumer-driven contract model:

- the consumer defines the interface it needs
- the producer ling implements that interface

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

The final global identifier is `lingId:serviceId`.

---

## Calling Other Lings

In newcomer projects, use these options in this order.

### Option 1. `@LingReference`

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

### Option 2. `LingContext.getService()`

Use this when you want explicit lookup behavior.

### Option 3. `LingContext.invoke()`

Use this only when you intentionally want looser coupling through FQSID strings.

---

## Declaring Governance

Governance should be explicit, not accidental.

### Permission declaration in `ling.yml`

```yaml
governance:
  permissions:
    - methodPattern: "storage:sql"
      permissionId: "READ"
    - methodPattern: "cache:local"
      permissionId: "WRITE"
```

### Annotation-based declaration

```java
@RequiresPermission("user:write")
@Auditable(action = "CREATE_USER", resource = "user")
public UserDTO createUser(CreateUserRequest request) {
    ...
}
```

### Development mode

```yaml
lingframe:
  dev-mode: true
```

---

## Packaging And Loading

### Development path

Point LingCore at source roots and recompile the ling as you work.

### Production path

Package the ling as a jar and place it under `ling-home`.

```bash
mvn clean package
```

---

## What The Kernel Already Gives You

In the current implementation, business lings do not need to implement the kernel itself.

The runtime already gives you:

- unified invocation governance
- runtime lifecycle coordination
- canary routing
- simulation support
- unload cleanup hooks
- leak diagnostics

Your main job is to:

- define a clear contract
- implement business logic
- declare permissions honestly

---

## Best Practices

- keep the first ling small
- keep `Shared API` contract-only
- prefer `@LingReference` for your first call path
- declare permissions in `ling.yml`
- use SLF4J logging instead of `System.out`
- avoid depending on `lingframe-core` from ordinary business lings

If you need the contract boundary next, go to [Shared API Guidelines](shared-api-guidelines.md); if you need infrastructure proxy patterns, go to [Infrastructure Development Guide](infrastructure-development.md).
