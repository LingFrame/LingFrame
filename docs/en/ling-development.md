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

---

## Constraints & Limitations (stated explicitly to avoid pitfalls)

LingFrame is low-intrusion, but it still has boundaries. This section states them plainly — understanding these limits earns more trust than believing in "absolute isolation."

### The isolation boundary is "orchestration isolation / type isolation," not "absolute isolation"

Under a single JVM + shared LingCore Spring context, **"absolute isolation" is physically impossible** — process-level static caches (e.g. `AnnotatedElementUtils`, `BridgeMethodResolver.cache`) hold references to Ling classes.

The honest statements LingFrame can make:
- **Type isolation**: each Ling has its own `LingClassLoader` (Child-First); the same class name seen by two Lings is a different `Class` object
- **Orchestration isolation**: on unload, `LingUnloadCoordinator` drains requests, evicts resources, cleans cache references; after unload, GC is provable
- **BeanFactory-level isolation**: the Ling's Spring context is separated from the LingCore's

What LingFrame cannot and does not promise:
- "Lings never reference each other" — if your Ling code holds another Ling's object, the framework does not intercept it
- "ClassLoader is GC'd immediately after unload" — we only promise "provably GC after orchestration + resource cleanup"; if Ling code leaks via static collections/threads, the framework's diagnostics can report it but cannot clean it on the code's behalf

### Once Shared API is frozen, breaking changes require a process restart

Shared API is the process-level public contract boundary. After preloading and freezing before Lings load:
- **A brand-new Shared API JAR can be hot-loaded** (additive)
- **A JAR already inside the shared boundary cannot be hot-updated or hot-unloaded** (no modify, no delete)

Forcing a change causes the same class to be loaded by different ClassLoaders, leading to `ClassCastException` and overall type-system corruption. **The correct path for breaking changes is to restart the process.**

### A Ling can use AOP / standalone threads / static variables, but they are not auto-reclaimed on unload

Ling code may use Spring AOP, spawn standalone threads, and hold static variables — the framework does not intercept. But **none of these vanish automatically on Ling unload**:

| Resource | Default behavior on unload | What you must do |
| --- | --- | --- |
| Ling `@Component` / Beans | Closed by `SpringLingContainer.stop()` | Usually no manual action |
| Standalone thread pools / schedulers | **Do not stop automatically** — daemon threads hold Ling Class → ClassLoader reference chain | Implement `DisposableBean.destroy()` or `@PreDestroy`, shutdown the scheduler and clear tasks |
| Static collections | **Not cleared automatically** — static references hold Ling class objects | Actively clear them in the unload hook |
| ThreadLocal | **Not removed automatically** | Call `remove()` in the stop callback |

**Positive example**: `lingframe-example-saas-mall`'s `InventoryHoldServiceImpl` explicitly implements `DisposableBean.destroy()` to shut down the TTL scheduler and clear hold records — this is the required posture when a Ling holds thread resources.

### DB governance boundary: covers the Spring DataSource Bean proxy path, not a full sandbox

LingFrame's storage-permission governance **primarily covers the Spring `DataSource` Bean proxy path** — i.e. DataSource calls obtained via the LingCore Spring container are governed.

**What can bypass it**:
- `DriverManager.getConnection()` hand-rolled connections
- Non-Bean database connection pools
- An independent DataSource introduced by the Ling itself

This is a **model boundary**, not a full-path sandbox — the docs do not advertise it as a "full-path sandbox." If your Ling needs strict storage governance, access the database via the LingCore's Spring DataSource path.

### Ling dependency discipline: `provided` dependency on LingCore interfaces; mis-declaring it as `compile` causes Class identity corruption

When a Ling `implements` a LingCore-native interface (e.g. in the saas-mall example, a Ling implements ling-mall's `UserService`), the pom must depend on the LingCore module with `<scope>provided</scope>`:

```xml
<!-- Correct: provided; resolved at runtime by the LingCore ClassLoader via parent-first fallback -->
<dependency>
    <groupId>com.lingframe</groupId>
    <artifactId>lingframe-example-ling-mall</artifactId>
    <scope>provided</scope>
</dependency>
```

At runtime, the Ling's `LingClassLoader` falls back to the LingCore ClassLoader to resolve these interfaces; **the LingCore and the Ling see the same `Class` object, with consistent identity**.

If mistakenly declared as `compile` (default scope), the Ling JAR bundles a copy of the LingCore interface, and Child-First loading produces **a second Class** — leading to `ClassCastException: com.example.UserService cannot be cast to com.example.UserService` (same name, different ClassLoader).

### A Ling must not directly depend on `lingframe-core`

A Ling may only depend on `lingframe-api` (the contract layer: interfaces, annotations, exceptions, security abstractions). **Directly depending on `lingframe-core` is a boundary violation** — it pulls governance-kernel implementation classes into the Ling's ClassLoader, forming an unresolved reference chain on unload.

 An ordinary `@Component` business Ling neither needs nor should touch any class in `lingframe-core`.
