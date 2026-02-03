# LingFrame

**JVM Runtime Framework providing Plugin Architecture and Zero-Downtime Canary Release for Spring Boot**
*Built-in complete permission control and security audit capabilities*

![Status](https://img.shields.io/badge/Status-Core_Implemented-green)
![License](https://img.shields.io/badge/License-Apache_2.0-blue)
![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.6-brightgreen)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen)](../../pulls)

---

## 🚑 What can LingFrame solve for you immediately?

> **Launch new features safely without changing the overall architecture**

* ✅ **Plugin-based Business Module Splitting**: Isolate unstable features from the core system.
* 🚦 **Zero-Downtime Canary Release**: New features only affect a subset of users.
* 🔁 **Fast Rollback**: Enable/Disable plugins without redeploying.
* 🧵 **Full Tracing & Audit Log**: Traceable issues and accountability.

> LingFrame is not for "elegant design",
> It is for **making the system fail less, be controllable, and survive**.

---

## 🧩 Plugin-based Spring Boot (Core Capability)

LingFrame runs **Complete Spring Boot Context** as a plugin:

* Independent ClassLoader per plugin
* Independent Lifecycle (Load / Start / Stop / Uninstall)
* Enable, Disable, Replace on demand
* No need to split into microservices, no network overhead

**You can understand it as:**

> 👉 "**Hot-Pluggable Spring Boot Modules**"

### Typical Use Cases

* Put **Experimental / High-Risk Features** in plugins via LingFrame.
* Isolate **Third-Party / Secondary Development Code** from the main system.
* Load **Low-Frequency Features** on demand to reduce complexity.

---

## 🚦 Zero-Downtime Canary Release

LingFrame built-in plugin-level traffic control:

* Plugin Instance Pool
* Canary / Grey Release
* Label Routing
* Plugin Version Coexistence

You can achieve:

* New plugin **only affects 5% of users**
* **Rollback immediately** if issues arise
* **No restart required** during the process

> For Ops and Devs, this is a **Life-Saving Capability**.

---

## 🧵 Tracing and Audit (Enabled by Default)

LingFrame records:

* Plugin → Plugin
* Plugin → Infrastructure (DB / Cache / MQ)
* Plugin → Host App

Every cross-module call leaves:

* Caller
* Target
* Execution Time
* Permission Result
* Audit Log

> No more guessing when issues happen.

---

## 🛡️ Advanced Capabilities: Runtime Governance (Long-term Value)

As system scale and complexity rise, LingFrame provides a complete **Governance Kernel**:

* 🔐 **Permission Control**: All cross-module calls must be authorized.
* ⚖️ **Capability Arbitration**: Core acts as the sole proxy, preventing bypass.
* 🧾 **Security Audit**: Meet compliance, risk control, and accountability needs.
* 🔒 **Zero Trust Model**: Plugins are untrusted by default.

> These are not reasons to use it on day one,
> But will **save your life** when the system gets complex.

---

## 🧠 Core Philosophy: Survive First, Then Establish Order

```text
┌───────────────────────────────────────────────┐
│            Core (Governance Kernel)             │
│   Auth · Audit · Arbitration · Tracing          │
└───────────────────────┬───────────────────────┘
                        ▼
┌───────────────────────────────────────────────┐
│          Infrastructure (Infra Proxy)           │
│     DB / Cache / MQ / Search Unified Control    │
└───────────────────────┬───────────────────────┘
                        ▼
┌───────────────────────────────────────────────┐
│           Business Plugins (Business Layer)     │
│      Canary · Rollback · Isolated               │
└───────────────────────────────────────────────┘
```

---

## 🚀 5-Minute Quick Start (Shortest Path)

### Prerequisites

* Java 21+
* Maven 3.8+

### Start Host Application

```bash
git clone https://github.com/lingframe/lingframe.git
cd lingframe
mvn clean install -DskipTests

cd lingframe-examples/lingframe-example-host-app
mvn spring-boot:run
```

### Enable Plugin Mechanism

```yaml
lingframe:
  enabled: true
  dev-mode: true
  plugin-home: "plugins"
  auto-scan: true
```

![LingFrame Dashboard Example](./images/dashboard.png)
*Figure: Plugin Management Panel, showing real-time status, canary traffic and audit logs.*

---

## 🧩 Create Your First Plugin

### Define Interface (Consumer Driven)

```java
public interface UserQueryService {
    Optional<UserDTO> findById(String userId);
}
```

### Plugin Implementation

```java
@SpringBootApplication
public class UserPlugin implements LingPlugin {

    @Override
    public void onStart(PluginContext context) {
        System.out.println("User plugin started");
    }
}

@Component
public class UserQueryServiceImpl implements UserQueryService {

    @LingService(id = "find_user")
    @Override
    public Optional<UserDTO> findById(String userId) {
        return repository.findById(userId);
    }
}
```

### Plugin Metadata

```yaml
id: user-plugin
version: 1.0.0
description: User module
mainClass: com.example.UserPlugin
```

---

## 🔄 Cross-Plugin Call (Auto Governance)

```java
@Component
public class OrderService {

    @LingReference
    private UserQueryService userQueryService;

    public Order create(String userId) {
        return userQueryService.findById(userId)
            .map(Order::new)
            .orElseThrow();
    }
}
```

> All calls automatically pass through:
> Permission Check · Audit · Tracing · Routing Decision

---

## 👤 Who is it for?

* Teams wanting to **Retrofit Monoliths with Plugins**
* Systems needing **Zero-Downtime Release / Canary**
* Platforms with **Secondary Dev / Third-Party Extension** needs
* Systems getting complex but not ready for Microservices

---

## 📦 Project Structure

```text
lingframe/
├── lingframe-api
├── lingframe-core
├── lingframe-runtime
├── lingframe-infrastructure
├── lingframe-examples
├── lingframe-dependencies
└── lingframe-bom
```

---

## 🤝 Contributing

* Feature Development
* Example Improvement
* Documentation
* Architecture Discussion

👉 View [Issues](../../issues) / [Discussions](../../discussions)

---

## 📄 License

Apache License 2.0

---

### Final Words

> **LingFrame does not require you to "govern everything" from the start.**
> **It just gives you one more choice before the system gets out of control.**
