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

## Classloader Reality In 0.3.0

The current runtime uses a three-level relationship:

- LingCore classloader
- `SharedApiClassLoader`
- ling implementation classloader

Practical meaning:

- contracts must be visible through the shared layer
- implementations must stay in the ling layer
- the same contract class should not be packaged redundantly into multiple places

---

## Bootstrap Boundary You Must Respect

In `0.3.0`, `Shared API` bootstrap order is explicit:

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
