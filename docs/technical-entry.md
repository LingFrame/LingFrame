# LingFrame

**Empower JVM Apps with OS-like Control and Governance**

> 🟢 **Core Framework Implemented** — Permission Governance, Audit Tracing, Capability Arbitration, Unit Isolation, and **Resilience Governance (Retry, Circuit-Breaking)** are available.

---

## 📖 What is LingFrame?

**LingFrame** is a **JVM Runtime Governance Framework**, focused on solving **Permission Control**, **Audit Tracing**, and **Capability Arbitration** issues in cross-unit calls within Java applications.

> ⚠️ We use modular isolation as a technical means for governance, but the core value lies in **Runtime Governance Capabilities** — ensuring every cross-unit call undergoes permission checks and audit recording.

**Core Capabilities**: **Permission Governance** · **Audit Tracing** · **Capability Arbitration** · **Unit Isolation**

---

## ✅ Core Governance Capabilities

| Capability            | Description                               | Core Class                            |
| --------------------- | ----------------------------------------- | ------------------------------------- |
| **Permission Governance** | Smart Inference + `@RequiresPermission`, all calls authorized | `GovernanceKernel`, `GovernanceStrategy` |
| **Audit Tracing**     | `@Auditable` + Async Audit Log, Full Trace | `AuditManager`                        |
| **Capability Arbitration**| Core is sole arbiter, proxying all cross-unit calls | `ServiceRegistry`, `SmartServiceProxy` |
| **Service Routing**   | `@LingService` + `@LingReference`, FQSID Routing | `LingReferenceInjector`, `GlobalServiceRoutingProxy` |
| **Unit Isolation**  | Three-Tier ClassLoader + Spring Parent-Child Context | `SharedApiClassLoader`, `LingClassLoader`, `SpringLingContainer` |
| **Hot Swap**          | Blue-Green Deploy + File Watcher, No Restart | `LingManager`, `InstancePool`, `HotSwapWatcher` |
| **Resilience**        | Circuit Breaking, Retry, Rate Limiting    | `GovernanceKernel`, `SlidingWindowCircuitBreaker`, `TokenBucketRateLimiter` |

---

## 🎯 Problems We Solve

| Pain Point             | Current Dilemma                       | LingFrame Solution         |
| :--------------------- | :------------------------------------ | :------------------------- |
| **Lack of Authorization**| Units call directly, no checks      | All calls proxied by Core for auth |
| **Untraceable Operations**| Hard to trace calls after failure    | Built-in Audit Log, Full Trace |
| **Blurred Boundaries** | Extension logic coupled with kernel   | Three-Tier Architecture + Isolation |
| **No Unified Governance** | Business units access DB/Redis directly | Unified infrastructure access arbitration |

---

## 👤 Applicable Scenarios

| Scenario               | Typical Requirement                             |
| ---------------------- | ----------------------------------------------- |
| **Enterprise App**     | Fine-grained permission control and full audit  |
| **Multi-Unit System**| Unified governance and isolation for calls      |
| **Secondary Dev Platform**| Restrict and audit third-party code          |
| **SaaS Multi-Tenant**  | Isolate and load tenant features on demand      |
| **Monolith Modularization**| Split monolith into independent, clear units |

---

## 💡 Core Philosophy: Governance Architecture

```text
┌─────────────────────────────────────────────────────────┐
│                 Core (Governance Kernel)                 │
│      Auth Arbitration · Audit · Scheduling · Isolation    │
└────────────────────────────────┬────────────────────────┘
                                 ▼
┌─────────────────────────────────────────────────────────┐
│           Infrastructure (Infra Layer)                   │
│    Storage Proxy · Cache Proxy · Message Proxy · Search   │
└────────────────────────────────┬────────────────────────┘
                                 ▼
┌─────────────────────────────────────────────────────────┐
│             Business (Business Layer)                    │
│          User Center · Order Service · Payment            │
└─────────────────────────────────────────────────────────┘
```

**Key Design Principles**:

1. **Core is Sole Arbiter**: Provides no business capability, only Auth, Audit, and Proxy.
2. **Zero Trust Call**: All cross-unit calls must be proxied and authorized by Core, no bypass.
3. **Complete Audit Chain**: Every call is traceable, supporting accountability and compliance.

---

## 🚀 Quick Start

### Prerequisites

- Java 17+
- Maven 3.8+

### Build Project

```bash
# Clone Repository (Choose any)
# GitHub (International, Recommended)
git clone https://github.com/LingFrame/LingFrame.git

# AtomGit (China)
git clone https://atomgit.com/lingframe/LingFrame.git

# Gitee (China Mirror)
git clone https://gitee.com/knight6236/lingframe.git

cd LingFrame

# Build and Install
mvn clean install -DskipTests

# Run Example LINGCORE App
cd lingframe-examples/lingframe-example-lingcore-app
mvn spring-boot:run
```

### LingCore Application Configuration

Configure LingFrame in `application.yaml`:

```yaml
lingframe:
  enabled: true
  dev-mode: true                    # Dev mode, warn only on permission denied
  ling-home: "lings"            # ling JAR directory
  ling-roots:                     # ling Source directory (Dev mode)
    - "../my-ling"
  auto-scan: true
  
  audit:
    enabled: true
    log-console: true
    queue-size: 1000
  
  runtime:
    default-timeout: 3s
    bulkhead-max-concurrent: 10
```

### Create Business Unit

LingFrame uses **Consumer-Driven Contract**: Consumer defines interface, Producer implements it.

```java
// ========== Consumer (Order ling) defines required interface ==========
// Location: order-api/src/main/java/.../UserQueryService.java
public interface UserQueryService {
    Optional<UserDTO> findById(String userId);
}

// ========== Producer (User ling) implements the interface ==========
// Location: user-ling/src/main/java/.../UserQueryServiceImpl.java
@SpringBootApplication
public class UserLing implements Ling {
    @Override
    public void onStart(LingContext context) {
        System.out.println("Ling started: " + context.getLingId());
    }
}

@Component
public class UserQueryServiceImpl implements UserQueryService {
    
    @LingService(id = "find_user", desc = "Query User")
    @Override
    public Optional<UserDTO> findById(String userId) {
        return userRepository.findById(userId).map(this::toDTO);
    }
}
```

Unit Metadata `ling.yml`:

```yaml
id: user-ling
version: 1.0.0
provider: "My Company"
description: "User Unit"
mainClass: "com.example.UserLing"

governance:
  permissions:
    - methodPattern: "storage:sql"
      permissionId: "READ"
```

### Cross-Unit Service Call (Via Kernel Proxy)

```java
// Method 1: @LingReference Injection (Highly Recommended)
// Order ling uses interface defined by itself, implemented by User ling
@Component
public class OrderService {
    
    @LingReference
    private UserQueryService userQueryService;  // Framework auto-routes to User ling implementation
    
    public Order createOrder(String userId) {
        // This call passes through Core Permission Check and Audit
        UserDTO user = userQueryService.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return new Order(user);
    }
}

// Method 2: LingContext.getService()
Optional<UserQueryService> service = context.getService(UserQueryService.class);

// Method 3: FQSID Protocol Call
Optional<UserDTO> user = context.invoke("user-ling:find_user", userId);
```

---

## 📦 Project Structure

```
lingframe/
├── lingframe-api/              # Contract Layer (Interface, Annotation, Exception)
├── lingframe-core/             # Governance Kernel (Auth, Audit, Unit Mgmt)
├── lingframe-runtime/          # Runtime Integration
│   └── lingframe-spring-boot3-starter/  # Spring Boot 3.x Integration
├── lingframe-infrastructure/   # Infrastructure Layer
│   ├── lingframe-infra-storage/   # Storage Proxy, SQL-level Permission
│   └── lingframe-infra-cache/     # Cache Proxy
├── lingframe-examples/         # Examples
│   ├── lingframe-example-lingcore-app/     # LINGCORE App
│   ├── lingframe-example-ling-user/  # User Unit
│   └── lingframe-example-ling-order/ # Order Unit
├── lingframe-dependencies/     # Dependency Management
└── lingframe-bom/              # BOM provided to external
```

---

## 🆚 Why Not Other Solutions?

> LingFrame's core value is not modularity itself, but **Runtime Governance**. Comparison focuses on governance.

| Governance Capability | OSGi     | Java SPI | PF4J       | **LingFrame**     |
| :-------------------- | :------- | :------- | :--------- | :---------------- |
| **Fine-grained Auth** | Complex  | None     | None       | ✅ Core Feature   |
| **Audit Tracing**     | Extension| None     | None       | ✅ Built-in       |
| **Capability Arbitration**| Service Registry| None | Extension Point | ✅ Core Forced Proxy |
| **Spring Native**     | Adapter  | Manual   | Extra Work | ✅ Parent-Child Context |
| **Positioning**       | Modularity| Extension| ling Sys | **Runtime Governance** |

---

## 📍 Roadmap

| Phase       | Goal                                                | Status        |
| :---------- | :-------------------------------------------------- | :------------ |
| **Phase 1** | Core Governance: Auth, Audit, Isolation             | ✅ **Done**   |
| **Phase 2** | Visualization: Dashboard Governance Center          | ✅ **Basic Done**|
| **Phase 3** | Elastic Governance: Circuit Break, Degrade, Retry, Rate Limit | ✅ **Done**    |
| **Phase 4** | Observability: Metrics, Trace Visualization         | ⏳ Planned    |
| **Phase 5** | Infra Extension: Message Proxy, Search Proxy        | ⏳ Planned    |

---

## 📚 Documentation

- [Quick Start](getting-started.md) - 5 Minute Start
- [Unit Development Guide](ling-development.md) - Develop Business Units
- [Shared API Guidelines](shared-api-guidelines.md) - API Design Best Practices
- [Infrastructure Development](infrastructure-development.md) - Develop Infra Proxies
- [Dashboard](dashboard.md) - Visual Governance Center
- [Architecture Design](architecture.md) - Deep Dive
- [Roadmap](roadmap.md) - Evolution Plan

---

## 👥 Contributing

We welcome community participation:

1. **Feature Dev**: Check [Issues](../../issues)
2. **Architecture Discussion**: [Discussions](../../discussions)
3. **Doc Improvement**: Help improve docs/tutorials
4. **Test**: Add unit tests

See [Contributing Guide](../CONTRIBUTING.md)

⭐ **Star** this repo to follow our growth.

---

## 📄 License

 **Apache License 2.0**
