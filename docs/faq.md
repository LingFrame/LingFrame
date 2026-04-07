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

**A:** LingFrame is built on top of Spring Boot and provides a Spring Boot Starter for rapid integration. It is not a replacement for Spring Boot, but rather provides runtime governance capabilities on top of it.

### Q4: What is a "Ling"?

**A:** A Ling (from the Chinese "灵元") is a core concept in LingFrame. It refers to a business unit that is independently loaded and managed within the host application (LingCore) process. You can think of it as a "governed plugin."

### Q5: What scenarios is LingFrame suited for?

**A:**

✅ Suitable for:
- Monolithic systems that have been running for years and cannot easily incur downtime or be rewritten.
- Teams that want to gradually introduce isolation, canary routing, rate limiting, circuit breaking, and permission auditing.
- Scenarios where you want to establish runtime order without tearing down the existing system.

❌ Not suitable for:
- Being a microservices replacement.
- Purely frontend plugin marketplaces or low-code platforms.
- Expecting automatic elimination of underlying business complexity.

---

## 2. Architecture and Design

### Q6: Why adopt a dual-layer state machine design?

**A:** The design goals of the dual-layer state machine (`InstanceStatus` + `RuntimeStatus`) are:

1. **Clear State Ownership**: Instance state and runtime state are managed by completely separate coordinators.
2. **Event-Driven Linkage**: The two layers are linked via events, avoiding objects directly writing states backwards and forwards.
3. **Observability**: State mutations are accompanied by published events, which makes tracking easier.

See [Runtime Dual-State Machine Architecture](runtime-dual-state-machine-architecture.md) for more details.

### Q7: Why can't the Shared API be hot-updated?

**A:** The Shared API serves as the process-level common contract boundary. If we allowed hot updates, it would cause:

1. Different Lings seeing different contract versions simultaneously.
2. The same class being loaded by different ClassLoaders, resulting in `ClassCastException`s.
3. System-wide structural distortion for strongly typed elements.

Therefore, the Shared API is preloaded and frozen prior to loading Lings. Changing the shared contract still requires restarting the process.

See [Shared API Guidelines](shared-api-guidelines.md) for details.

### Q8: Why is the Pipeline divided into so many phases?

**A:** The Pipeline phases are divided to ensure:

1. **Separation of Concerns**: Each Filter only handles one specific task.
2. **Strict Dependencies**: Start-up checks validate phase sequences to prevent late runtime failures.
3. **Extendability**: Custom Filters can be inserted safely at intentional points.

### Q9: What are the risks of a Child-First ClassLoader?

**A:** Child-First means the Ling prioritizes loading its own classes first, which can cause:

1. **Version Conflicts**: The Ling uses a different library version than the LingCore.
2. **Type Incompatibility**: The same class gets loaded by different ClassLoaders.

Solutions:
- Use the Shared API to share common classes.
- Declare dependencies in the `ling.yml` intelligently.

---

## 3. Usage Issues

### Q10: How do I debug Ling code?

**A:** Several ways:

1. **Remote Debugging**: Append `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005` to the JVM startup arguments.
2. **Dev Mode**: Set `dev-mode: true` to enable streamlined hot updates.
3. **Log Debugging**: Increase the log level for related packages to DEBUG or TRACE.

### Q11: How do Lings communicate with each other?

**A:** Lings communicate through service interfaces:

```java
// Use @LingReference to inject a service provided by another Ling.
// The framework will scan installed Lings to find the Bean implementing this interface.
@LingReference
private UserService userService;
```

### Q12: How do I execute a Canary Release?

**A:** Through the Dashboard API configuration:

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/lings/order-ling/canary \
  -H "Content-Type: application/json" \
  -d '{"percent": 20, "canaryVersion": "2.0.0"}'
```

See [Dashboard Docs](dashboard.md) for details.

### Q13: How are Ling dependencies handled?

**A:** Declare dependencies directly inside `ling.yml`:

```yaml
dependencies:
  - user-ling
  - common-ling
```

LingFrame will sequence the boots so depending Lings are loaded last.

### Q14: Which Spring features can a Ling use?

**A:** Lings can use most Spring features normally:

- ✅ `@Component` / `@Service` / `@Repository`
- ✅ `@Autowired` Dependency Injection
- ✅ `@Value` Configuration properties
- ✅ `@Transactional` Transactions
- ⚠️ `@Configuration` (Requires mindfulness towards ClassLoader isolation)
- ✅ `@SpringBootApplication` is supported (standard entry point for Spring Boot Lings).

---

## 4. Troubleshooting

### Q15: What if my Ling fails to load?

**A:** Check these vectors:

1. **Logs**: Examine the error stack in your logs.
2. **Classpath**: Make sure the Ling's JAR actually contains all necessary classes.
3. **Dependencies**: Verify that the Lings it depends on have already loaded successfully.
4. **Permissions**: Make sure the Ling is granted the necessary capabilities.

For a detailed walkthrough, see the [Troubleshooting Manual](troubleshooting.md).

### Q16: What if memory usage keeps growing?

**A:** Likely causes:

1. **ClassLoader Leaks**: Check if a Ling left static collections uncleaned.
2. **ThreadLocal Leaks**: Ensure the Ling properly cleans up its ThreadLocals when stopped.
3. **Listener Leaks**: Prefer using the provided EventBus instead of manually registering persistent framework listeners.

Leak detection is a built-in runtime capability requiring no separate config toggle. In dev mode (`dev-mode: true`), aggressive diagnostics (`DEV_AGGRESSIVE` / `DEV_BOUNDED`) are automatically enabled. In production mode, the system degrades to passive observation (`PROD_PASSIVE`):
```yaml
lingframe:
  dev-mode: true  # Automatically enables aggressive leak diagnostics
```

### Q17: What if the circuit breaker remains OPEN continuously?

**A:**

1. Check whether downstream services are actually functioning.
2. Tune the circuit breaker thresholds (via governance configuration).
3. Observe the circuit breaker state directly from the Dashboard.

### Q18: How can I check a Ling's status?

**A:** Via the Dashboard APIs:

```bash
# View all Lings
curl http://localhost:8888/lingframe/dashboard/lings

# View a single Ling
curl http://localhost:8888/lingframe/dashboard/lings/{lingId}
```

---

## 5. Dashboard Specifics

### Q19: How do I turn on the Dashboard?

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

### Q20: Why can't I use the installation endpoint on the Dashboard?

**A:** The install endpoint is turned off by default for security, requiring explicit enablement:

```yaml
lingframe:
  dashboard:
    install-enabled: true
```

### Q21: Why does the hot-reload endpoint return a 403?

**A:** Hot-reload capabilities are rigidly confined to developer mode:

```yaml
lingframe:
  dev-mode: true
```

---

## 6. Other Questions

### Q22: Which JDK versions does LingFrame support?

**A:**

| JDK Version | Support Level |
|----------|----------|
| JDK 8 | ✅ Supported (Some features constrained) |
| JDK 11 | ✅ Supported |
| JDK 17 | ✅ Fully Supported (Recommended) |
| JDK 21 | ✅ Supported |

### Q23: Which Spring Boot versions does LingFrame support?

**A:**

| Spring Boot Version | Support Level |
|------------------|----------|
| 2.7.x | ✅ Supported (Some features constrained) |
| 3.0.x | ✅ Supported |
| 3.1.x | ✅ Supported |
| 3.2.x | ✅ Fully Supported (Recommended) |

### Q24: How can I contribute to LingFrame?

**A:**

1. Fork the repository
2. Read the contributing guidelines
3. Submit a Pull Request

### Q25: Where can I get help?

**A:**

- **Documentation**: All documents in this directory.
- **Issues**: Submit an issue request.

### Q26: What is LingFrame's open source license?

**A:** Apache License 2.0. It can be freely used in commercial projects.

---

## 7. Roadmap Associated

### Q27: When will Prometheus/Grafana integrations be supported?

**A:** We already support Micrometer metric bridging today. If the host application supplies a `MeterRegistry`, LingFrame will automatically register Ling health and governance signal metrics. If the host then introduces `micrometer-registry-prometheus` and exposes `/actuator/prometheus`, those metrics can be scraped directly by Prometheus. See the `lingframe-example-lingcore-app` for a demonstration.

### Q28: When will Messaging Proxies (Kafka/RabbitMQ) be supported?

**A:** This is planned during the Phase 5 Ecosystem Expansion phase. See the [Roadmap](roadmap.md) for details.
