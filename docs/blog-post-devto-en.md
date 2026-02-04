---
title: "Securing Legacy Java Monoliths with Runtime Zero-Trust: My Open-Source Framework LingFrame"
published: false
description: "Introduce Zero-Trust governance, Plugin Isolation, and Observability to your aging Spring Boot app without a painful rewrite."
tags: java, opensource, security, zerotrust, architecture, jvm, springboot
cover_image: https://your-cover-image-url.com/cover.png
---

## The Problem No One Wants to Talk About 🦖

Let's be honest.

You have a giant Java monolith. It's been running for years. It makes money. And it's held together by duct tape and prayers.

Every time you onboard a new team or integrate a vendor script, you think: *"What if this code accidentally calls something it shouldn't? What if a junior dev's DTO leaks sensitive data? What if we need to A/B test this new module without a full restart?"*

The usual advice is: "Just rewrite it as microservices!" Right. Let me just pause business for 18 months to refactor 500k LOC. 🙃

**This was my frustration.** I believed there had to be a middle ground—a way to introduce **runtime governance** into an existing JVM process, without tearing everything down.

So, I built **[LingFrame](https://github.com/LingFrame/LingFrame)**.

---

## What is LingFrame? 🛡️

LingFrame is a **JVM runtime governance framework** for long-running Java applications.

Think of it as putting guardrails *inside* your process. It doesn't replace your architecture; it lets your existing monolith become *governable*.

### Core Features (The "Why You Should Care" List):

| Feature                 | What it Means for You                                                       |
| :---------------------- | :-------------------------------------------------------------------------- |
| **Three-Tier ClassLoader** | Isolated plugins. No more "Jar Hell". Each business module gets its own sandbox. |
| **Capability-Based Security** | Plugins must *declare* what they need (`storage:sql`, `cache:local`). Everything else is denied. |
| **`@LingService` & `@LingReference`** | Expose and consume services across plugin boundaries with simple annotations. |
| **Audit & Trace (`@Auditable`)** | Every sensitive operation is logged. Who did what, when, and to what resource. |
| **Canary Deployments**  | Run a new version of a plugin on a subset of traffic *without restarting the JVM*. |

---

## Show Me The Code (5-Minute Demo) ⏱️

### Step 1: Add the Starter

In your **Host Application's** `pom.xml`:

```xml
<dependency>
    <groupId>com.lingframe</groupId>
    <artifactId>lingframe-spring-boot3-starter</artifactId>
    <version>0.1.0-Preview</version>
</dependency>
```

### Step 2: Configure LingFrame

In `application.yaml`:

```yaml
lingframe:
  enabled: true
  dev-mode: true  # Logs warnings instead of blocking (for development)

  # Where your plugin JARs live
  plugin-home: plugins

  # For local development, point directly to source code
  plugin-roots:
    - ../my-user-plugin
    - ../my-order-plugin

  # Shared API (interfaces between host and plugins)
  preload-api-jars:
    - shared-api/order-api.jar

  # Enable the visual dashboard
  dashboard:
    enabled: true
```

### Step 3: Declare Plugin Capabilities (`plugin.yml`)

Each plugin must *declare* what it intends to access. This is the core of "Zero-Trust".

```yaml
# In your plugin's resources/plugin.yml
id: user-plugin
version: 1.0.0
mainClass: com.example.user.UserPluginApplication

governance:
  capabilities:
    - capability: "storage:sql"
      accessType: "WRITE"       # We need to read/write to DB
    - capability: "cache:local"
      accessType: "WRITE"       # We need to use the local cache
    - capability: "ipc:order-plugin"
      accessType: "EXECUTE"     # We want to call the order service
```

### Step 4: Annotate Your Services

This is what your plugin code looks like:

```java
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final JdbcTemplate jdbcTemplate;

    @LingService(id = "query_user", desc = "Query user by ID")
    @RequiresPermission(Capabilities.STORAGE_SQL) // Declare DB access
    @Cacheable(value = "users", key = "#userId")   // Also uses cache
    @Auditable(action = "QUERY_USER", resource = "user") // Audit this
    @Override
    public Optional<UserDTO> queryUser(String userId) {
        log.info("Cache miss for user {}", userId);
        String sql = "SELECT * FROM t_user WHERE id = ?";
        // ... jdbcTemplate query ...
        return Optional.ofNullable(user);
    }

    @LingService(id = "create_user", desc = "Create a new user")
    @RequiresPermission(Capabilities.STORAGE_SQL)
    @Auditable(action = "CREATE_USER", resource = "user")
    @Override
    public UserDTO createUser(String name, String email) {
        // ... INSERT SQL ...
    }
}
```

### Step 5: Call Across Plugins with `@LingReference`

Need to call `user-plugin` from `order-plugin`? Don't use `@Autowired`. Use `@LingReference`:

```java
@Service
public class OrderServiceImpl implements OrderService {

    @LingReference // LingFrame routes this to the 'user-plugin'
    private UserQueryService userQueryService;

    @LingService(id = "get_order", desc = "Get order by ID")
    public OrderDTO getOrderById(Long orderId) {
        // ... get order from DB ...
        
        // Cross-plugin call (IPC)
        userQueryService.findById(userId).ifPresent(
            user -> order.setUserName(user.getName())
        );
        return order;
    }
}
```

---

## See The Governance in Action 🔥

### Scenario: Unauthorized Write Attempt

Imagine a plugin declares only `READ` access to `storage:sql`, but then tries to run an `INSERT`.

```bash
curl -X POST "http://localhost:8888/user/createUser?name=Hacker&email=h@ck.er"
```

**Result:** LingFrame blocks it.

```text
c.l.core.exception.PermissionDeniedException: 
Plugin [user-plugin] requires [storage:sql] with [WRITE] access, 
but only allowed: [READ]
```

The plugin fails gracefully. The host app stays alive. The event is audited.

### Visual Dashboard

LingFrame includes a built-in dashboard. You can see:
- Real-time plugin status (RUNNING, STOPPED, CANARY)
- Traffic split for Canary deployments
- Live audit logs

![LingFrame Dashboard](https://your-image-url.com/dashboard.png)

---

## Why Not Just Use [OSGi / Spring Cloud / etc.]?

| Framework | Trade-off |
|-----------|-----------|
| **OSGi** | Very powerful, but has a *brutal* learning curve and changes your entire dev lifecycle. |
| **Spring Cloud / K8s** | Solves isolation by splitting *processes*. This means network hops, serialization, and infrastructure complexity. |
| **LingFrame** | In-process isolation. Microservice-like boundaries, but with local method call performance. |

LingFrame is for teams that **can't** do a full microservices split right now, but **need** better governance today.

---

## Current Status & Roadmap 🗺️

LingFrame is in **v0.1.x Preview**. The direction is set, but we're still validating the core loop.

**What's Done:**
- [x] Three-tier ClassLoader isolation
- [x] Capability-based permission model
- [x] Spring Boot 3 integration
- [x] Built-in visual dashboard
- [x] Canary deployment support

**What's Next:**
- [ ] JDK 8 / Spring Boot 2.x compatibility layer
- [ ] Resilience governance (Circuit breaker, Rate limiting, Retry)
- [ ] Observability (Metrics, Distributed tracing export)

---

## Try It Out 🚀

If you're wrestling with an aging monolith and dreaming of better governance, give LingFrame a look.

👉 **[GitHub: LingFrame/LingFrame](https://github.com/LingFrame/LingFrame)** ⭐

**How to Start:**
```bash
git clone https://github.com/LingFrame/LingFrame.git
cd LingFrame
mvn clean install -DskipTests
cd lingframe-examples/lingframe-example-host-app
mvn spring-boot:run
```
Then visit `http://localhost:8888/dashboard.html` to see the UI.

---

**Star the repo if this resonates with you!** ⭐

Drop a comment: What's the messiest legacy system you've had to deal with? How did you handle untrusted code?

*(This is Part 1 of my "JVM Runtime Governance" series. Next up: A deep dive into our three-tier ClassLoader architecture!)*

---
Series: #lingframe-series
#java #opensource #security #zerotrust #springboot #architecture
