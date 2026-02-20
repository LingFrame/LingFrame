# AI Assistant Guide

> This file helps AI assistants quickly understand the LingFrame project.

## Project Positioning

**LingFrame** = JVM Runtime Governance Framework

Core Capabilities: ling Isolation + Permission Governance + Audit Tracing + Hot Swap

## Tech Stack

- Java 17+
- Spring Boot 3.5.6
- Maven 3.8+

## Architecture Principles (Must Follow)

### Core Design
1. **Zero Trust**: Business units cannot access DB/Redis directly, must go through Core proxy.
2. **Microkernel**: Core only handles scheduling and arbitration, no business logic.
3. **Contract First**: All interactions via `lingframe-api` interfaces.
4. **Context Isolation**: Independent ClassLoader + Spring Child Context per unit.
5. **FQSID Routing**: Services identified globally by `lingId:serviceId`.

### Unit Responsibilities
- **lingframe-core**: Pure Java implementation, **No Framework Dependency** (No Spring, No ORM).
- **lingframe-runtime**: Ecosystem adapter layer, e.g., `spring-boot3-starter` adapts Spring.
- **lingframe-api**: Contract layer, depended on by both Core and Lings.

### Design Principles
- **Single Responsibility (SRP)**: Each class does one thing.
- **Dependency Inversion (DIP)**: Core depends on abstractions, not implementations.
- **Open/Closed (OCP)**: Add features via extension points, do not modify core code.
- **Interface Segregation (ISP)**: Small and focused interfaces.

## Coding Standards

### Modifying Core Unit
- **FORBIDDEN** to introduce Spring, Hibernate, MyBatis dependencies.
- **FORBIDDEN** to directly `new` concrete implementations, use factory or injection.
- Public APIs must be defined in `lingframe-api`.

### Modifying Runtime Unit
- Adapter layer bridges Core and specific frameworks.
- Use `@Configuration` to assemble Core components.

### Unit Development
- Depend only on `lingframe-api`, **FORBIDDEN** to depend on `lingframe-core`.
- Use `@LingReference` to inject other unit services, **NOT** `@Autowired`.
- Use `@LingService` to expose services.

### Permission Declaration
- Add `@RequiresPermission` for sensitive operations.
- Add `@Auditable` for operations needing audit.
- Framework infers: get/find → READ, save/delete → WRITE.

### Shared API Design
- Only interfaces and DTOs, **NO** implementation.
- Interfaces defined on Consumer side (Consumer-Driven Contract).
- DTOs must be Serializable, **NO** business logic.

### Naming Conventions
| Type | Rule | Example |
|------|------|---------|
| Interface | Descriptive Name | `UserService` |
| Implementation | `Default` or `Core` Prefix | `DefaultPermissionService` |
| Proxy | `Proxy` Suffix | `SmartServiceProxy` |
| Configuration | `Properties` Suffix | `LingFrameProperties` |
| Factory | `Factory` Suffix | `SpringContainerFactory` |

## Core Classes Cheatsheet

| Class | Responsibility |
|-------|----------------|
| `LingManager` | ling Install/Uninstall/Routing |
| `LingRuntime` | Runtime environment for a single ling |
| `InstancePool` | Blue-Green deployment, version switching |
| `SharedApiClassLoader` | Loads shared APIs between Lings |
| `LingClassLoader` | ling ClassLoader (Child-First) |
| `ServiceRegistry` | Service Registry |
| `InvocationExecutor` | Invocation Executor |
| `GovernanceKernel` | Governance Kernel |
| `SmartServiceProxy` | Smart Service Proxy |
| `GlobalServiceRoutingProxy` | Proxy implementation for @LingReference |

## Three-Tier ClassLoader

```
AppClassLoader (LINGCORE)
    ↓ parent
SharedApiClassLoader (Shared API)
    ↓ parent
LingClassLoader (ling, Child-First)
```

## Key Configuration Formats

### LingCore Application (application.yaml)

```yaml
lingframe:
  enabled: true
  dev-mode: true                    # Dev mode, loose permissions
  Ling-home: "Lings"            # JAR directory
  Ling-roots:                     # ling root directory (Dev)
    - "../my-ling"
  preload-api-jars:                 # Shared API JARs
    - "shared-api/*.jar"
```

### ling Metadata (ling.yml)

```yaml
id: my-ling                       # No ling: root node!
version: 1.0.0
mainClass: "com.example.MyLing"
governance:
  permissions:
    - methodPattern: "storage:sql"  # Not capability
      permissionId: "READ"          # Not access
```

## ⚠️ Common Mistakes

| Mistake | Correct |
|---------|---------|
| `devMode: true` | `dev-mode: true` (kebab-case) |
| `ling.yml` has `ling:` root | Direct properties, no root node |
| ling depends on `lingframe-core` | Depend only on `lingframe-api` |
| Use `@Autowired` for other Lings | Use `@LingReference` |
| Looking for `LingSlot` class | Does not exist, use `LingRuntime` |

## Unit Structure

```
lingframe/
├── lingframe-api/              # Contract Layer
├── lingframe-core/             # Governance Kernel
├── lingframe-runtime/
│   └── lingframe-spring-boot3-starter/
├── lingframe-infrastructure/
│   ├── lingframe-infra-storage/
│   └── lingframe-infra-cache/
├── lingframe-dashboard/        # Visual Governance
└── lingframe-examples/
```

## Common Commands

```bash
mvn clean install -DskipTests          # Build
mvn spring-boot:run -pl lingframe-examples/lingframe-example-lingcore-app  # Run Example
```

## Documentation Index

| Document | Purpose |
|----------|---------|
| [getting-started.md](docs/getting-started.md) | 5-Minute Quick Start |
| [ling-development.md](docs/ling-development.md) | Unit Development |
| [shared-api-guidelines.md](docs/shared-api-guidelines.md) | API Design Guidelines |
| [architecture.md](docs/architecture.md) | Architecture Details |
| [infrastructure-development.md](docs/infrastructure-development.md) | Infrastructure Proxy |
| [dashboard.md](docs/dashboard.md) | Dashboard |
| [roadmap.md](docs/roadmap.md) | Roadmap |
