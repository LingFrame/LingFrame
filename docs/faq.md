# Frequently Asked Questions (FAQ)

This document collects common questions and answers about LingFrame.

---

## 1. Basic Concepts

### Q1: What is LingFrame?

**A:** LingFrame is an order framework for JVM single-process long-running systems. It focuses on solving the problem of monolithic systems gradually losing control over time, rather than simply "splitting into microservices."

See [Core Community Consensus](../.arts/core-community-consensus.zh-CN.md).

### Q2: What's the difference between LingFrame and OSGi?

**A:**

| Aspect | LingFrame | OSGi |
|--------|-----------|------|
| Positioning | Runtime governance framework | Modular system |
| Learning curve | Lower | Higher |
| Spring integration | Native support | Requires extra configuration |
| Governance capabilities | Built-in circuit breaker/rate limiter/permissions | Needs extra implementation |
| Hot reload | Supported | Supported |

### Q3: What's the relationship between LingFrame and Spring Boot?

**A:** LingFrame is built on Spring Boot and provides Spring Boot Starters for quick integration. It's not a replacement for Spring Boot, but adds runtime governance capabilities on top of it.

### Q4: What is a "Ling"?

**A:** A Ling (灵元) is a core concept in LingFrame, referring to a business unit that is independently loaded and managed within the host application (LingCore) process. It can be understood as a "governed plugin."

### Q5: What scenarios is LingFrame suitable for?

**A:**

✅ Suitable for:
- Monolithic systems that have been running for years and cannot be easily stopped or rewritten
- Teams that want to gradually introduce isolation, canary releases, rate limiting, circuit breakers, and permission auditing
- Teams that want to establish runtime order without overturning existing systems

❌ Not suitable for:
- Using as a microservices replacement
- Pure frontend plugin markets or low-code platforms
- Expecting automatic elimination of business complexity

---

## 2. Architecture and Design

### Q6: Why adopt a dual-state machine design?

**A:** The dual-state machine (InstanceStatus + RuntimeStatus) design aims to:

1. **Clear state ownership**: Instance state and runtime state are managed by different coordinators
2. **Event-driven linkage**: The two layers are linked through events, not by objects writing to each other's state
3. **Observability**: All state changes have events published for tracking

See [Dual State Machine Architecture](runtime-dual-state-machine-architecture.md).

### Q7: Why can't Shared API be hot-updated?

**A:** Shared API is the process-level public contract boundary. If hot updates were allowed:

1. Different lings would see different versions of contracts
2. The same class loaded by different ClassLoaders would cause ClassCastException
3. The type system would become inconsistent

Therefore, Shared API is preloaded and frozen before ling loading, and changes require process restart.

See [Shared API Guidelines](shared-api-guidelines.md).

### Q8: Why does Pipeline have so many stages?

**A:** Pipeline stage division is for:

1. **Separation of concerns**: Each Filter does one thing
2. **Clear dependencies**: Stage order is validated at startup to avoid runtime issues
3. **Extensibility**: Custom Filters can be inserted at any stage

### Q9: What are the risks of Child-First ClassLoader?

**A:** Child-First means lings preferentially load their own classes, which may cause:

1. **Version conflicts**: Library versions used by ling differ from LingCore
2. **Type incompatibility**: Same class loaded by different ClassLoaders

Solutions:
- Use Shared API to share common classes
- Declare dependencies in `ling.yml`

---

## 3. Usage Questions

### Q10: How to debug ling code?

**A:** Several ways:

1. **Remote debugging**: Add `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005` at startup
2. **Development mode**: Set `dev-mode: true` to enable hot reload
3. **Log debugging**: Adjust log level to DEBUG or TRACE

### Q11: How do lings communicate with each other?

**A:** Lings communicate through service interfaces:

```java
// Use @LingReference to inject services from other lings
// The framework will search for Beans implementing the interface across all installed Lings
@LingReference
private UserService userService;
```

### Q12: How to implement canary release?

**A:** Configure via Dashboard API:

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/lings/order-ling/canary \
  -H "Content-Type: application/json" \
  -d '{"percent": 20, "canaryVersion": "2.0.0"}'
```

See [Dashboard Documentation](dashboard.md).

### Q13: How to handle ling dependencies?

**A:** Declare dependencies in `ling.yml`:

```yaml
dependencies:
  - user-ling
  - common-ling
```

LingFrame ensures dependent lings are loaded first.

### Q14: What Spring features can lings use?

**A:** Lings can use most Spring features:

- ✅ @Component / @Service / @Repository
- ✅ @Autowired dependency injection
- ✅ @Value configuration injection
- ✅ @Transactional transactions
- ⚠️ @Configuration needs attention for ClassLoader isolation
- ❌ @SpringBootApplication not supported (lings are not standalone applications)

---

## 4. Troubleshooting

### Q15: What to do if ling fails to load?

**A:** Check these aspects:

1. **Logs**: Check error messages in logs
2. **Classpath**: Ensure ling JAR contains all necessary classes
3. **Dependencies**: Ensure dependent lings are loaded
4. **Permissions**: Ensure ling has necessary capabilities

See [Troubleshooting Guide](troubleshooting.md) for detailed steps.

### Q16: What to do if memory keeps growing?

**A:** Possible causes:

1. **ClassLoader leak**: Check if ling has uncleared static collections
2. **ThreadLocal leak**: Ensure ThreadLocal is cleared when ling stops
3. **Listener leak**: Use EventBus instead of manually registering listeners

Enable leak detection:
```yaml
lingframe:
  leak-detection:
    enabled: true
    mode: DEVELOPMENT
```

### Q17: What to do if circuit breaker stays open?

**A:**

1. Check if downstream service is healthy
2. Adjust circuit breaker threshold (in governance config)
3. Check circuit breaker status via Dashboard

### Q18: How to check ling status?

**A:** Via Dashboard API:

```bash
# View all lings
curl http://localhost:8888/lingframe/dashboard/lings

# View single ling
curl http://localhost:8888/lingframe/dashboard/lings/{lingId}
```

---

## 5. Dashboard Related

### Q19: How to enable Dashboard?

**A:**

```yaml
lingframe:
  dashboard:
    enabled: true
```

Add dependency:
```xml
<dependency>
    <groupId>com.lingframe</groupId>
    <artifactId>lingframe-dashboard</artifactId>
</dependency>
```

### Q20: Why can't I use the Dashboard install endpoint?

**A:** The install endpoint is disabled by default, enable it explicitly:

```yaml
lingframe:
  dashboard:
    install-enabled: true
```

### Q21: Why does hot reload return 403?

**A:** Hot reload is only available in development mode:

```yaml
lingframe:
  dev-mode: true
```

---

## 6. Other Questions

### Q22: Which JDK versions does LingFrame support?

**A:**

| JDK Version | Support Level |
|-------------|---------------|
| JDK 8 | ✅ Supported (some features limited) |
| JDK 11 | ✅ Supported |
| JDK 17 | ✅ Fully supported (recommended) |
| JDK 21 | ✅ Supported |

### Q23: Which Spring Boot versions does LingFrame support?

**A:**

| Spring Boot Version | Support Level |
|---------------------|---------------|
| 2.7.x | ✅ Supported (some features limited) |
| 3.0.x | ✅ Supported |
| 3.1.x | ✅ Supported |
| 3.2.x | ✅ Fully supported (recommended) |

### Q24: How to participate in LingFrame development?

**A:**

1. Fork the repository
2. Read the contributing guide
3. Submit Pull Request

### Q25: Where can I get help?

**A:**

- **Documentation**: Documents in this directory
- **Issues**: Submit issue reports

### Q26: What is LingFrame's open source license?

**A:** Apache License 2.0, free for commercial use.

---

## 7. Roadmap Related

### Q27: When will Prometheus/Grafana integration be supported?

**A:** Micrometer bridging is already supported. When the host application provides a `MeterRegistry`, LingFrame automatically registers ling health and governance signal gauges. If the host also adds `micrometer-registry-prometheus` and exposes `/actuator/prometheus`, Prometheus can scrape them directly. See `lingframe-example-lingcore-app` for a working sample.

### Q28: When will message brokers (Kafka/RabbitMQ) be supported?

**A:** Planned in Phase 5 ecosystem completion, see [Roadmap](roadmap.md).
