# Frequently Asked Questions (FAQ)

This document collects common questions and answers about LingFrame.

---

## 1. Basic Concepts

### Q1: What is LingFrame?

**A:** LingFrame is an order-keeping architecture for JVM-based, single-process, long-running systems. It focuses on resolving the gradual loss of control in monolithic systems running over long periods, rather than simply "splitting everything into microservices."

### Q2: What is the difference between LingFrame and OSGi?

**A:**

| Comparison Point | LingFrame | OSGi |
|--------|------|------|
| Positioning | Runtime Governance Framework | Modular System |
| Learning Curve | Lower | Higher |
| Spring Integration | Natively Supported | Requires Extra Config |
| Governance | Built-in circuit breakers, rate limits, permissions | Requires custom implementations |
| Hot Updates | Supported | Supported |

### Q3: What is the relationship between LingFrame and Spring Boot?

**A:** LingFrame is not a Spring Boot replacement. The governance kernel (`lingframe-core`) is Spring-agnostic; Spring Boot integration lives in `lingframe-runtime` via a **shared** `lingframe-spring-boot-starter` plus stack-specific `lingframe-spring-boot2-starter` / `lingframe-spring-boot3-starter` (typed `javax` / `jakarta`, no reflective Servlet probing). Dashboard stays a **single GAV** with matrix source sets. Detail: [development-manual.md](development-manual.md) section 5.2.

### Q4: What is a "Ling"?

**A:** A Ling (from the Chinese "灵元") is a core concept in LingFrame. It refers to a business unit that is independently loaded and managed within the LingCore process. Do not reduce it to a generic "plugin" — a Ling is a governed, cleanly unloadable runtime unit.

### Q5: What scenarios is LingFrame suited for?

**A:**

✅ Suitable for:
- Monolithic systems that have been running for years and cannot easily incur downtime or be rewritten.
- Large microservices whose internal code has bloated into a "distributed monolith" and need internal evolution boundaries.
- Teams that want to gradually introduce isolation, gray routing, rate limiting, circuit breaking, and permission auditing.
- Scenarios where you want to establish runtime order without tearing down the existing system.

❌ Not suitable for:
- Being a microservices replacement (they are complementary, not mutually exclusive).
- Purely frontend plugin marketplaces or low-code platforms.
- Expecting automatic elimination of underlying business complexity.

### Q6: What is the relationship between LingFrame and Microservices / Service Mesh?

**A:** They are complementary, not mutually exclusive.

- **Microservices & Service Mesh solve inter-process** partitioning, deployment, networking, and inter-service communication;
- **LingFrame solves intra-process** boundary establishment, runtime governance, and gradual evolution inside a single JVM.

LingFrame does not answer "how many services a system should be split into"; it answers "how each service should continuously evolve internally." If a microservice grows too large over time (becoming a distributed monolith), LingFrame can be introduced inside that service process to isolate new and legacy features into lings, achieving gradual refactoring without embarking on a high-risk, cross-network rewrite.

---

## 2. Architecture and Design

### Q7: Why adopt a dual-layer state machine design?

**A:** The design goals of the dual-layer state machine (`InstanceStatus` + `RuntimeStatus`) are:

1. **Clear State Ownership**: Instance state and runtime state are managed by completely separate coordinators.
2. **Event-Driven Linkage**: The two layers are linked via events, avoiding objects directly writing states backwards and forwards.
3. **Observability**: State mutations are accompanied by published events, which makes tracking easier.

See [Architecture Design](architecture.md) §1 for more details.

### Q8: Why can't the Shared API be hot-updated?

**A:** The Shared API serves as the process-level common contract boundary. If we allowed hot updates, it would cause:

1. Different Lings seeing different contract versions simultaneously.
2. The same class being loaded by different ClassLoaders, resulting in `ClassCastException`s.
3. System-wide structural distortion for strongly typed elements.

Therefore, the Shared API is preloaded and frozen prior to loading Lings. Changing the shared contract still requires restarting the process.

See [Shared API Guidelines](shared-api-guidelines.md) for details.

### Q9: Why is the Pipeline divided into so many phases?

**A:** The Pipeline phases are divided to ensure:

1. **Separation of Concerns**: Each Filter only handles one specific task.
2. **Strict Dependencies**: Start-up checks validate phase sequences to prevent late runtime failures.
3. **Extendability**: Custom Filters can be inserted safely at intentional points.

### Q10: What are the risks of a Child-First ClassLoader?

**A:** Child-First means the Ling prioritizes loading its own classes first, which can cause:

1. **Version Conflicts**: The Ling uses a different library version than the LingCore.
2. **Type Incompatibility**: The same class gets loaded by different ClassLoaders.

Solutions:
- Use the Shared API to share common classes.
- Declare dependencies in the `ling.yml` intelligently.

---

## 3. Usage Issues

### Q11: How do I debug Ling code?

**A:** Several ways:

1. **Remote Debugging**: Append `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005` to the JVM startup arguments.
2. **Dev Mode**: Set `dev-mode: true` to enable streamlined hot updates.
3. **Log Debugging**: Increase the log level for related packages to DEBUG or TRACE.

### Q12: How do Lings communicate with each other?

**A:** Lings communicate through service interfaces:

```java
// Use @LingReference to inject a service provided by another Ling.
// The framework will scan installed Lings to find the Bean implementing this interface.
@LingReference
private UserService userService;
```

### Q13: How do I execute a Gray Release?

**A:** Through the Dashboard contract weight routing API—multiple providers under the same contract split traffic by weight; binary is just the N=2 special case:

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/contract-routing/order-ling/weight \
  -H "Content-Type: application/json" \
  -d '{"providerKey": "order-ling:1.1.0", "weight": 20}'
```

> `providerKey` is the routing key—always `lingId:version` for a Ling (version sourced from the bound instance context), bare `lingcore-app` for LingCore, consistent across registration and routing read-path keying. `weight` is an integer 0-100; once the Dashboard pushes it, the runtime weight in `ProviderWeightRouter` is overridden immediately and takes effect on both IPC and Web governance chains. See [Dashboard Docs](dashboard.md) for details.

### Q14: How are Ling dependencies handled?

**A:** Declare dependencies directly inside `ling.yml`:

```yaml
dependencies:
  - user-ling
  - common-ling
```

LingFrame will sequence the boots so depending Lings are loaded last.

### Q15: Which Spring features can a Ling use?

**A:** Lings can use most Spring features normally:

- ✅ `@Component` / `@Service` / `@Repository`
- ✅ `@Autowired` Dependency Injection
- ✅ `@Value` Configuration properties
- ✅ `@Transactional` Transactions
- ⚠️ `@Configuration` (Requires mindfulness towards ClassLoader isolation)
- ✅ `@SpringBootApplication` is supported (standard entry point for Spring Boot Lings).

---

## 4. Troubleshooting

### Q16: What if my Ling fails to load?

**A:** Check these vectors:

1. **Logs**: Examine the error stack in your logs.
2. **Classpath**: Make sure the Ling's JAR actually contains all necessary classes.
3. **Dependencies**: Verify that the Lings it depends on have already loaded successfully.
4. **Permissions**: Make sure the Ling is granted the necessary capabilities.

For a detailed walkthrough, see the [Troubleshooting Manual](troubleshooting.md).

### Q17: What if memory usage keeps growing?

**A:** Likely causes:

1. **ClassLoader Leaks**: Check if a Ling left static collections uncleaned.
2. **ThreadLocal Leaks**: Ensure the Ling properly cleans up its ThreadLocals when stopped.
3. **Listener Leaks**: Prefer using the provided EventBus instead of manually registering persistent framework listeners.

Leak detection is a built-in runtime capability requiring no separate config toggle. In dev mode (`dev-mode: true`), aggressive diagnostics (`DEV_AGGRESSIVE` / `DEV_BOUNDED`) are automatically enabled. In production mode, the system degrades to passive observation (`PROD_PASSIVE`):
```yaml
lingframe:
  dev-mode: true  # Automatically enables aggressive leak diagnostics
```

### Q18: What if the circuit breaker remains OPEN continuously?

**A:**

1. Check whether downstream services are actually functioning.
2. Tune the circuit breaker thresholds (via governance configuration).
3. Observe the circuit breaker state directly from the Dashboard.

### Q19: How can I check a Ling's status?

**A:** Via the Dashboard APIs:

```bash
# View all Lings
curl http://localhost:8888/lingframe/dashboard/lings

# View a single Ling
curl http://localhost:8888/lingframe/dashboard/lings/{lingId}
```

---

## 5. Dashboard Specifics

### Q20: How do I turn on the Dashboard?

**A:**

```yaml
lingframe:
  dashboard:
    enabled: true
```

Also, add the dependency to your LingCore pom:
```xml
<dependency>
    <groupId>com.lingframe</groupId>
    <artifactId>lingframe-dashboard</artifactId>
</dependency>
```

### Q21: Why can't I use the installation endpoint on the Dashboard?

**A:** The install endpoint is turned off by default for security, requiring explicit enablement:

```yaml
lingframe:
  dashboard:
    install-enabled: true
```

### Q22: Why does the hot-reload endpoint return a 403?

**A:** Hot-reload capabilities are rigidly confined to developer mode:

```yaml
lingframe:
  dev-mode: true
```

---

## 6. Other Questions

### Q23: Which JDK versions does LingFrame support?

**A:** Build/CI matrices in this repo:

| Path | JDK | Notes |
| --- | --- | --- |
| **Primary / examples** | **JDK 8** | Default Maven profile + Spring Boot 2.7 |
| **Support line** | **JDK 17** | Explicit `-Pspring-boot3` (Spring Boot 3.x) |

Other LTS JDKs may work for applications, but published verification is the two matrices above. Always `clean` when switching matrices (SB3 class files fail on JDK 8).

### Q24: Which Spring Boot versions does LingFrame support?

**A:**

| Path | Spring Boot | Starter coordinate |
| --- | --- | --- |
| **Primary** | **2.7.x** | `lingframe-spring-boot2-starter` (+ shared `lingframe-spring-boot-starter`) |
| **Support** | **3.x** (repo BOM tracks a current 3.5.x line) | `lingframe-spring-boot3-starter` (+ shared starter) |

Do not put Servlet types in shared code or invent dual dashboard GAVs. See [production-hardening](production-hardening.md) section 6 and the development manual section 5.2.

### Q25: How can I contribute to LingFrame?

**A:**

1. Fork the repository
2. Read the contributing guidelines
3. Submit a Pull Request

### Q26: Where can I get help?

**A:**

- **Documentation**: All documents in this directory.
- **Issues**: Submit an issue request.

### Q27: What is LingFrame's open source license?

**A:** Apache License 2.0. It can be freely used in commercial projects.

---

## 7. Roadmap Associated

### Q28: When will Prometheus/Grafana integrations be supported?

**A:** We already support Micrometer metric bridging today. If the LingCore application supplies a `MeterRegistry`, LingFrame will automatically register Ling health and governance signal metrics. If LingCore then introduces `micrometer-registry-prometheus` and exposes `/actuator/prometheus`, those metrics can be scraped directly by Prometheus. See the `lingframe-example-lingcore-app` for a demonstration.

### Q29: When will Messaging Proxies (Kafka/RabbitMQ) be supported?

**A:** This is planned during the Phase 5 Ecosystem Expansion phase. See the [Roadmap](roadmap.md) for details.

---

## 8. Glossary — Terms At A Glance

This section is prepared for developers encountering LingFrame for the first time.
If other documents feel too dense, read through this section first. Returning to the other material will feel remarkably easier.

### LingFrame

The overall project name: an order-keeping architecture and JVM runtime governance framework for long-running systems.

### LingCore

The LingCore process: the application side that runs the governance kernel.

### Ling

The isolated business unit that is independently loaded, managed, and governed within the LingCore process.

### Shared API

The process-level shared contract boundary bridging LingCore to Lings, or Lings to Lings.

It is currently utilized to carry:

- Interfaces
- DTOs (Data Transfer Objects)
- Essential annotations
- Essential constants

### Governance Kernel

The core runtime layer responsible for applying unified application governance rules.

### Invocation Pipeline

The strictly ordered primary execution chain running all invocation governance decisions.

### `NORMAL`

Execution mode: exercises all governance components alongside the actual terminal invocation.

### `SIMULATION`

Execution mode: exercises the real governance path logic, but does not produce actual business side-effects.

### `GOVERN_ONLY`

Execution mode: exercises the governance flow, but cuts the Pipeline short, omitting the final terminal invocation entirely.

### `InstanceStatus`

The distinct lifecycle state belonging to a specific Ling instance.

### `RuntimeStatus`

The macro availability state that a Ling as a whole currently exposes from the perspective of LingCore.

### Dashboard

Right now, the Dashboard should be more deeply understood as the runtime governance control surface, rather than merely a frontend webpage.

### Gray

Routing a deliberate subset of incoming traffic to a specific Ling version or instance, instead of dispatching everything to the default path.

### Unload Cleanup

When a Ling is explicitly removed, the runtime will drain requests, evict resources, clear classloader-linked states, and finally execute passive leak diagnostics.
