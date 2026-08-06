# Shared API Guidelines

This is one of the most important documents for new LingFrame developers.

> `Shared API` is the process-level public contract boundary between LingCore and lings.

---

## What Belongs In Shared API

Keep `Shared API` limited to contract material:

- interfaces
- DTOs
- small enums and value objects used in those contracts

Do not put these in `Shared API`:

- business implementations
- repositories
- Spring services or components
- persistence entities from one side's private implementation

---

## The Consumer-Driven Contract Rule

LingFrame uses a consumer-driven pattern.

- the consumer defines the interface it needs
- the producer implements that interface

```java
public interface UserQueryService {
    Optional<UserDTO> findById(String userId);
}
```

```java
@Component
public class UserQueryServiceImpl implements UserQueryService {
    @LingService(id = "find_user")
    @Override
    public Optional<UserDTO> findById(String userId) {
        return userRepository.findById(userId).map(this::toDTO);
    }
}
```

---

## Classloader Reality In The Current Implementation

The current runtime uses a three-level relationship:

- LingCore classloader
- `SharedApiClassLoader`
- ling implementation classloader

Practical meaning:

- contracts must be visible through the shared layer
- implementations must stay in the ling layer
- the same contract class should not be packaged redundantly into multiple places

---

## Why This Design — Escaping Dependency Hell

Traditional modular frameworks (OSGi being the most notorious example) push dependency resolution to runtime: module A tries to find module B at startup, hangs or fails if B is not ready, and leaves you wrestling with deployment ordering, version conflicts, and circular dependencies. This is the most painful part of modular systems.

LingFrame makes a deliberate choice to cut that path entirely:

- **Contracts are fixed at process startup** (preload → freeze). Every ling that loads afterward sees the same immutable set of interfaces.
- **Consumers do not wait for producers**: `order-ling` holds a reference to `UserQueryService` regardless of whether `user-ling` is loaded. Whether the call succeeds is decided at invocation time, not at startup.
- **Fast-fail, not hang**: if no implementation is available at call time, a `LingNotFoundException` is thrown immediately. The process does not stall waiting for a producer to arrive.
- **Producers plug in transparently**: once `user-ling` is hot-deployed, `SmartServiceProxy` routes calls to the new implementation automatically. Consumers see nothing change.

The result: lings have no startup-order dependencies on each other, no version negotiation, and no circular dependency problem. Each ling is a genuinely independently hot-swappable execution unit.

The trade-off is stated honestly: **an already-loaded shared contract cannot be hot-updated**. Contract changes require a process restart. That constraint is the price of keeping the entire dependency model simple.

---

## Bootstrap Boundary You Must Respect

In the current runtime, `Shared API` bootstrap order is explicit:

1. preload shared jars or classes directories
2. register shared package prefixes
3. freeze the shared boundary
4. then load lings

That is part of the architecture boundary.

### Operational meaning

- a brand-new shared jar can be introduced before freeze
- an already loaded shared contract must not be hot-updated in place
- changing an existing shared contract still requires a process restart

---

## Security Boundary (Not A JVM Sandbox)

`Shared API` and load-time scanners improve **contract isolation** and **deploy-time risk signaling**. They are **not** a full JVM security sandbox.

| Layer | What it does | What it does not do |
| --- | --- | --- |
| Child-first `LingClassLoader` + forced parent packages | Prefer / exclusive parent types for JDK & `com.lingframe.api.*` | Block every reflective escape or native call at runtime |
| `DangerousApiVerifier` (ASM) | Fail or warn on known dangerous bytecode at **install/load** | Intercept every runtime call after the ling is loaded |
| `strictSecurityMode` | Makes more WARN-level findings hard-fail at scan time | Replace a SecurityManager / module deny-list |
| Permission + infra proxies | Govern storage/cache/IPC when traffic goes through proxies | Catch unproxied `DriverManager` / raw sockets created off the path |
| Shared Spring static caches (`AnnotatedElementUtils` / `BridgeMethodResolver.cache` etc.) | At unload, each cleaner under `resource/` **synchronously drains** them (including Soft), ensuring ClassLoader is GC-able | Architecturally guarantee LingCore never holds ling Class references at runtime (physical consequence of shared heap + parent delegation, not implementation laziness) |

### Proxy and CGLIB (ling-side recommendation)

- **Preferred**: expose **interface** contracts so Spring uses **JDK dynamic proxy** (`java.lang.reflect.Proxy`). JDK `WeakCache` is friendlier to ClassLoader unload semantics, reducing dependence on CGLIB / Spring in-house caches.
- **Second-best**: when proxying concrete classes without interfaces, **CGLIB** must be used; unload depends on evidence-driven cleanup like `CglibCacheCleaner`; cost is higher under multi-version hot-swap.
- **Do not** expect "forking a few Spring cache classes into the ling CL" to isolate static — the parent loader still resolves to the LingCore copy for callers using parent delegation.
- The framework does **not** disable CGLIB by default (would break interface-less Beans); this is a contract and implementation recommendation.

Operational guidance:

- Treat untrusted third-party lings as **high risk** even with scanners enabled.
- Prefer `strictSecurityMode=true` in production hardening; use trusted ling IDs / lib prefixes sparingly and auditably.
- Runtime escapes (reflection, process, network) remain possible for code already loaded; mitigate with permissions, proxies, and process-level isolation when needed.
- **Storage**: SQL permission mainly governs the **Spring DataSource Bean proxy chain**; `DriverManager` / non-Bean pools can bypass it (see production hardening checklist section 9).

---

## DTO Design Rules

Good DTOs are boring on purpose.

```java
@Data
public class OrderDTO implements Serializable {
    private Long id;
    private String orderNo;
    private BigDecimal amount;
}
```

Avoid embedding business behavior or private entity models in DTOs.

---

## Evolution Rules

Safe changes:

- add methods
- add optional fields
- add new versioned packages for breaking changes

Unsafe changes:

- changing existing signatures in place
- reusing the same package for incompatible changes
- assuming shared contract hot-update is safe

For incompatible changes, prefer versioned packages.

---

## Typical `preload-api-jars` Examples

```yaml
lingframe:
  preload-api-jars:
    - api/order-api-*.jar
    - api/user-api/
    - lingframe-examples/lingframe-example-order-api
```

---

## Common Errors

### `ClassNotFoundException`

Usually means the shared contract was not preloaded correctly.

### `ClassCastException`

Usually means the same class was loaded by more than one classloader view.

Continue with [Ling Development Guide](ling-development.md) if you want to write a ling against this contract boundary.
