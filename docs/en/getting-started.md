# Getting Started

This document is the **formal getting-started guide**.

If you just want to get the examples up and running first, prioritize reading `QUICK_START.md` in the repository root.
This document, however, focuses on explaining what happens after you get it running:

- What exactly was started in the example?
- Why do these steps work?
- How should you continue understanding and using LingFrame?

If you only remember one sentence, remember this:

> LingFrame lets you load and govern isolated business lings within a single JVM process, rather than forcing you to split the system into microservices right from the start.

For the current public implementation, this is not just a demonstration of "getting lings loaded." It is your first encounter with a governable, convergent runtime chain that can later be verified for disciplined hot unloads.

---

## Part 1: Running in 5 Minutes

**What you are about to run**: In the example project, you will start a LingCore application and tell it to load two example lings (`user-ling`, `order-ling`). In this single run, you will simultaneously see three things: lings can be loaded within the same process; LingCore can invoke ling services via shared contracts; the invocation process still passes through the governance kernel.

**Environment requirements**: JDK 17+ (as the main example path); Maven 3.8+. The current runtime simultaneously supports both JDK 8 and Spring Boot 2.x, but the example project remains the easiest entry point for beginners.

### 1. Clone the Repository

```bash
# GitHub
git clone https://github.com/LingFrame/LingFrame.git

# AtomGit
git clone https://atomgit.com/lingframe/LingFrame.git

# Gitee
git clone https://gitee.com/LingFrame/LingFrame.git
```

### 2. Build the Project

```bash
cd LingFrame
mvn clean install -DskipTests
```

### 3. Start the Example LingCore Application

```bash
cd lingframe-examples/lingframe-example-lingcore-app
mvn spring-boot:run
```

### 4. Verify the Example

```bash
curl http://localhost:8888/user-ling/user/listUsers
curl "http://localhost:8888/user-ling/user/queryUser?userId=1"
```

If both of these requests return normally, you already have a runnable LingFrame runtime.

---

## Part 2: Taking Another 5 Minutes — Verifying Current Closed-Loop Governance

If you want to confirm that the current example doesn't just "run," but truly possesses a closed-loop control surface, observability, and unloading capability, you can continue with the following steps.

### 1. Open the Dashboard

Visit in your browser:

```text
http://localhost:8888/dashboard.html
```

You should see a list of currently loaded lings, as well as control surface information like health metrics, governance configs, and timelines.

### 2. View Current Lings and Versions

```bash
curl http://localhost:8888/lingframe/dashboard/lings
```

In the default example, you'll generally see:

- `order-ling:1.0.0`
- `user-ling:1.0.0`
- `user-ling:1.1.0-canary`

### 3. Check Health and Governance Metrics

```bash
curl http://localhost:8888/lingframe/dashboard/lings/health/all
curl http://localhost:8888/lingframe/dashboard/lings/governance/all
```

Here you can directly see:

- Ling-level summaries
- Version-level details
- Currently collected governance signals

### 4. Push a Governance Patch to `user-ling`

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/governance/user-ling/invocation \
  -H "Content-Type: application/json" \
  -d "{\"timeoutMs\":3000,\"rateLimitPerSecond\":1,\"maxConcurrentThreads\":1}"
```

This step corresponds to the currently closed-loop invocation governance parameters:

- `timeoutMs`
- `rateLimitPerSecond`
- `maxConcurrentThreads`

### 5. Send Requests Again and Observe Changes

```bash
curl http://localhost:8888/user-ling/user/listUsers
curl http://localhost:8888/lingframe/dashboard/lings/health/all
curl http://localhost:8888/lingframe/dashboard/lings/governance/all
```

You should be able to see:

- Changes in request count, latency, and QPS in the health metrics.
- Signaling changes locally triggered by rate limit/timeouts in the governance metrics.

### 6. Verify Structured Unload Precheck

```bash
curl -X DELETE http://localhost:8888/lingframe/dashboard/lings/uninstall/user-ling/1.1.0-canary
```

What this step returns is no longer simply success/failure, but a structured unload result representing:

- Whether the unload was actually triggered.
- Overall risk level.
- A summary list of risks.

Note:

- The current default strategy is "prompt but do not block."
- So even if the precheck returns risk warnings, the main unload process may still proceed.
- The passive leak detection chain post-unload is still preserved, and has not been replaced by the pre-unload precheck.

**What exactly did you just start?**

- **LingCore**: the core-side application within the current process. It owns the runtime, the governance kernel, and the shared contract boundaries.
- **Ling**: the isolated business unit being deployed independently inside the LingCore process.
- **Shared API**: the process-level common contract layer bridging LingCore and Lings, or bridging between Lings. Interfaces and DTOs intended to cross boundaries belong here.

As a beginner, remember these three definitions: LingCore is the core application executing in the current process; Ling is the isolated business unit; Shared API is the mutually respected contract between them. For terminology details, see the [Glossary & FAQ](faq.md).

**Minimal viable configuration**: The example application arrives with functional configs. The most critical parts are these:

```yaml
server:
  port: 8888

lingframe:
  enabled: true
  dev-mode: true

  preload-api-jars:
    - lingframe-examples/lingframe-example-order-api

  ling-home: lings
  ling-roots:
    - lingframe-examples/lingframe-example-ling-order
    - lingframe-examples/lingframe-example-ling-user
```

What this config expresses is: enable the LingFrame runtime; run in developer-friendly mode; preload shared contracts before starting the lings; discover lings from the local example source paths.

---

## What Does This Execution Prove?

When the example runs successfully, you have actually verified four things:

- LingCore can discover and load lings within a single process.
- Shared contracts are preloaded before lings start.
- Cross-ling invocations do not bypass the governance kernel.
- The current example configs are ready for you to read further into the development documentation.

If you continue and complete the Dashboard/governance/unload validations above, you'll additionally see:

- The control surface can hot-adjust invocation governance parameters.
- Health and governance metrics change following real requests.
- The pre-unload precheck, true unload run, and post-unload diagnostics have formed a primary chain.

The next thing most worth verifying is not just "can we load another ling," but whether this runtime chain can stay orderly under reload/unload/cleanup scenarios.

Next, if you want to judge how to adapt this, read the "First-Round Adoption Strategy" section below. If you want to dive straight into writing lings, jump to [Ling Development Guide](ling-development.md).  
Before production, read the [Production Hardening Checklist](production-hardening.md).

---

## Part 3: From Scratch — Building a Complete LingFrame Application

This section walks you from zero through a complete LingFrame application build.

> ⚠️ **Note**: This tutorial is written against the current actual code structure.

### Goal

We will build a simple order management system:

```
LingCore
    │
    ├── user-ling (user service ling)
    │   └ └─ provides user query capability
    │
    └ └─ order-ling (order service ling)
        ├── depends on user-ling for user info
        └ └─ provides order create / query capability
```

### 3.1 Environment Preparation

| Software | Version |
| :-- | :-- |
| JDK | **8** (example default); support line optional 17 + Boot 3 |
| Maven | 3.6+ |
| Spring Boot | **2.7.x** (example default, `spring-boot2-starter`); support line 3.x (`spring-boot3-starter`) |

The POM examples below walk the **main path**: `lingframe-spring-boot2-starter` + Boot 2.7. If using Boot 3 / JDK 17, swap to `lingframe-spring-boot3-starter` and the Boot 3 BOM (this repo maps to `-Pspring-boot3`).

```bash
mkdir lingframe-demo
cd lingframe-demo

mkdir -p ling-core
mkdir -p shared-api
mkdir -p lings/user-ling
mkdir -p lings/order-ling
```

Parent POM:

```xml
<!-- pom.xml -->
<project>
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>lingframe-demo</artifactId>
    <version>1.0.0</version>
    <packaging>pom</packaging>

    <modules>
        <module>ling-core</module>
        <module>shared-api</module>
        <module>lings/user-ling</module>
        <module>lings/order-ling</module>
    </modules>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>com.lingframe</groupId>
                <artifactId>lingframe-bom</artifactId>
                <version>${lingframe.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

### 3.2 Define Shared Contracts

> ⚠️ **Important**: Shared API is the process-level public contract boundary between LingCore and lings. Interfaces and DTOs that cross this boundary should live here.

```xml
<!-- shared-api/pom.xml -->
<project>
    <parent>
        <groupId>com.example</groupId>
        <artifactId>lingframe-demo</artifactId>
        <version>1.0.0</version>
    </parent>

    <artifactId>shared-api</artifactId>
    <packaging>jar</packaging>

    <dependencies>
        <dependency>
            <groupId>com.lingframe</groupId>
            <artifactId>lingframe-api</artifactId>
        </dependency>
    </dependencies>
</project>
```

Define the user query service interface:

```java
// shared-api/src/main/java/com/example/api/UserQueryService.java
package com.example.api;

import java.util.Optional;

public interface UserQueryService {

    Optional<UserDTO> findById(Long userId);
}
```

Define the user DTO:

```java
// shared-api/src/main/java/com/example/api/UserDTO.java
package com.example.api;

import java.io.Serializable;

public class UserDTO implements Serializable {
    private Long id;
    private String userName;
    private String email;

    public UserDTO() {}

    public UserDTO(Long id, String userName, String email) {
        this.id = id;
        this.userName = userName;
        this.email = email;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
```

### 3.3 Create the LingCore Application

```xml
<!-- ling-core/pom.xml -->
<project>
    <parent>
        <groupId>com.example</groupId>
        <artifactId>lingframe-demo</artifactId>
        <version>1.0.0</version>
    </parent>

    <artifactId>ling-core</artifactId>
    <packaging>jar</packaging>

    <dependencies>
        <!-- LingFrame Spring Boot Starter (main path: Boot 2.7 / JDK 8). For Boot 3, swap to lingframe-spring-boot3-starter -->
        <dependency>
            <groupId>com.lingframe</groupId>
            <artifactId>lingframe-spring-boot2-starter</artifactId>
        </dependency>

        <!-- Dashboard (optional) -->
        <dependency>
            <groupId>com.lingframe</groupId>
            <artifactId>lingframe-dashboard</artifactId>
        </dependency>

        <!-- Shared API -->
        <dependency>
            <groupId>com.example</groupId>
            <artifactId>shared-api</artifactId>
            <version>${project.version}</version>
        </dependency>

        <!-- Spring Boot -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- Database support (required by the example) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>runtime</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

Application config:

```yaml
# ling-core/src/main/resources/application.yaml
server:
  port: 8888

spring:
  datasource:
    driver-class-name: org.h2.Driver
    url: jdbc:h2:mem:lingframe;DB_CLOSE_DELAY=-1
    username: sa
    password:
  cache:
    type: simple

lingframe:
  enabled: true
  dev-mode: true

  # Shared API preload paths
  preload-api-jars:
    - ../shared-api

  # Ling home root directory
  ling-home: lings

  # Extra ling directories (dev mode)
  ling-roots:
    - ../lings/user-ling
    - ../lings/order-ling

  # Dashboard config
  dashboard:
    enabled: true
    install-enabled: true

logging:
  level:
    com.lingframe: INFO
```

Boot main class:

```java
// ling-core/src/main/java/com/example/LingCoreApplication.java
package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LingCoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(LingCoreApplication.class, args);
    }
}
```

### 3.4 Create the User Service Ling

```xml
<!-- lings/user-ling/pom.xml -->
<project>
    <parent>
        <groupId>com.example</groupId>
        <artifactId>lingframe-demo</artifactId>
        <version>1.0.0</version>
    </parent>

    <artifactId>user-ling</artifactId>
    <packaging>jar</packaging>

    <dependencies>
        <!-- Shared API -->
        <dependency>
            <groupId>com.example</groupId>
            <artifactId>shared-api</artifactId>
            <version>${project.version}</version>
            <scope>provided</scope>
        </dependency>

        <!-- LingFrame API -->
        <dependency>
            <groupId>com.lingframe</groupId>
            <artifactId>lingframe-api</artifactId>
            <scope>provided</scope>
        </dependency>

        <!-- Spring Boot -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
            <scope>provided</scope>
        </dependency>
    </dependencies>
</project>
```

Ling manifest:

> ⚠️ **Note**: `ling.yml` uses camelCase, e.g. `mainClass`, `accessType`.

```yaml
# lings/user-ling/src/main/resources/ling.yml
id: user-ling
version: 1.0.0
provider: "Example"
description: "User service ling"
mainClass: "com.example.user.UserApplication"

governance:
  permissions: []

  # Ling capability declarations
  capabilities:
    - capability: "storage:sql"
      accessType: "WRITE"
    - capability: "cache:local"
      accessType: "WRITE"

properties:
  mark: "demo"
```

Ling entry class:

```java
// lings/user-ling/src/main/java/com/example/user/UserApplication.java
package com.example.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@EnableCaching
@SpringBootApplication
public class UserApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}
```

Service interface:

```java
// lings/user-ling/src/main/java/com/example/user/service/UserService.java
package com.example.user.service;

import com.example.api.UserDTO;
import java.util.List;
import java.util.Optional;

public interface UserService {

    Optional<UserDTO> queryUser(String userId);

    List<UserDTO> listUsers();

    UserDTO createUser(String name, String email);
}
```

Service implementation:

> ⚠️ **Important**: Use the `@LingService` annotation to mark a method as an outward-exposed capability. LingCore uses this annotation as the key dispatch hook for RPC contract and routing.

```java
// lings/user-ling/src/main/java/com/example/user/service/impl/UserServiceImpl.java
package com.example.user.service.impl;

import com.example.api.UserDTO;
import com.example.user.service.UserService;
import com.lingframe.api.annotation.Auditable;
import com.lingframe.api.annotation.LingService;
import com.lingframe.api.annotation.RequiresPermission;
import com.lingframe.api.security.Capabilities;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final JdbcTemplate jdbcTemplate;

    @LingService(id = "query_user", desc = "Query user by ID")
    @RequiresPermission(Capabilities.STORAGE_SQL)
    @Cacheable(value = "users", key = "#userId")
    @Override
    public Optional<UserDTO> queryUser(String userId) {
        log.info("queryUser, userId: {}", userId);
        String sql = "SELECT * FROM t_user WHERE id = ?";
        try {
            UserDTO user = jdbcTemplate.queryForObject(
                sql,
                new BeanPropertyRowMapper<>(UserDTO.class),
                userId
            );
            return Optional.ofNullable(user);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @LingService(id = "list_users", desc = "List all users")
    @RequiresPermission(Capabilities.STORAGE_SQL)
    @Auditable(action = "LIST_USERS", resource = "user")
    @Override
    public List<UserDTO> listUsers() {
        return jdbcTemplate.query(
            "SELECT * FROM t_user",
            new BeanPropertyRowMapper<>(UserDTO.class)
        );
    }

    @LingService(id = "create_user", desc = "Create user")
    @RequiresPermission(Capabilities.STORAGE_SQL)
    @Auditable(action = "CREATE_USER", resource = "user")
    @Override
    public UserDTO createUser(String name, String email) {
        jdbcTemplate.update(
            "INSERT INTO t_user (name, email) VALUES (?, ?)",
            name, email
        );
        return new UserDTO(null, name, email);
    }
}
```

### 3.5 Create the Order Service Ling (Cross-Ling Invocation)

Ling manifest:

```yaml
# lings/order-ling/src/main/resources/ling.yml
id: order-ling
version: 1.0.0
provider: "Example"
description: "Order service ling"
mainClass: "com.example.order.OrderApplication"

governance:
  permissions: []

  capabilities:
    - capability: "storage:sql"
      accessType: "WRITE"
    - capability: "cache:local"
      accessType: "WRITE"
    - capability: "ipc:user-ling"
      accessType: "EXECUTE"
```

Service implementation (cross-ling invocation):

> ⚠️ **Important**: Use `@LingReference` to inject a service interface provided by another ling. The framework searches all installed lings for a Bean implementing that interface.

```java
// lings/order-ling/src/main/java/com/example/order/service/impl/OrderServiceImpl.java
package com.example.order.service.impl;

import com.example.api.UserDTO;
import com.example.api.UserQueryService;
import com.example.order.dto.OrderDTO;
import com.example.order.service.OrderService;
import com.lingframe.api.annotation.LingReference;
import com.lingframe.api.annotation.LingService;
import com.lingframe.api.annotation.RequiresPermission;
import com.lingframe.api.security.Capabilities;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    @LingReference
    private UserQueryService userQueryService;

    private final JdbcTemplate jdbcTemplate;

    @LingService(id = "get_order", desc = "Query order by ID")
    @RequiresPermission(Capabilities.STORAGE_SQL)
    @Override
    public OrderDTO getOrderById(Long orderId) {
        log.info("getOrderById, orderId: {}", orderId);
        try {
            OrderDTO order = jdbcTemplate.queryForObject(
                "SELECT * FROM t_order WHERE order_id = ?",
                new BeanPropertyRowMapper<>(OrderDTO.class),
                orderId
            );
            // Fetch user info via cross-ling invocation
            if (order != null && order.getUserId() != null) {
                userQueryService.findById(order.getUserId()).ifPresent(
                    user -> order.setUserName(user.getUserName())
                );
            }
            return order;
        } catch (Exception e) {
            log.warn("Order not found: {}", orderId);
            return null;
        }
    }

    @LingService(id = "create_order", desc = "Create order")
    @RequiresPermission(Capabilities.STORAGE_SQL)
    @Override
    public OrderDTO createOrder(Long userId, String productName) {
        log.info("createOrder, userId: {}, productName: {}", userId, productName);
        jdbcTemplate.update(
            "INSERT INTO t_order (user_id, product_name) VALUES (?, ?)",
            userId, productName
        );
        OrderDTO order = new OrderDTO();
        order.setUserId(userId);
        order.setProductName(productName);
        return order;
    }
}
```

### 3.6 Run and Test

Build the project:

```bash
mvn clean package -DskipTests
```

Start the LingCore app:

```bash
cd ling-core
mvn spring-boot:run
```

Manage lings via the Dashboard:

> The Dashboard is a governance control plane, not just a page.

Dashboard URL: `http://localhost:8888/dashboard.html`

**List lings**:

```bash
curl http://localhost:8888/lingframe/dashboard/lings
```

**Activate lings**:

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/lings/user-ling/status \
  -H "Content-Type: application/json" \
  -d '{"status": "ACTIVE"}'

curl -X POST http://localhost:8888/lingframe/dashboard/lings/order-ling/status \
  -H "Content-Type: application/json" \
  -d '{"status": "ACTIVE"}'
```

**Test invocations**:

```bash
# Create user
curl -X POST "http://localhost:8888/user-ling/user/createUser?name=Alice&email=alice@example.com"

# Query user
curl "http://localhost:8888/user-ling/user/queryUser?userId=1"

# List users
curl http://localhost:8888/user-ling/user/listUsers
```

### 3.7 Hot Reload Demo

> ⚠️ Hot reload is only available under dev mode (`dev-mode: true`)

Modify `UserServiceImpl` — add logging or change business logic.

Rebuild:

```bash
cd lings/user-ling
mvn clean package -DskipTests
```

Hot reload:

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/lings/user-ling/reload
```

### 3.8 Governance Capability Demo

Canary release:

> Canary routes a portion of traffic to a designated ling version or instance.

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/lings/user-ling/canary \
  -H "Content-Type: application/json" \
  -d '{"percent": 20, "canaryVersion": "1.1.0"}'
```

Traffic stats:

```bash
curl http://localhost:8888/lingframe/dashboard/lings/user-ling/stats
```

Health metrics:

```bash
# Single ling health
curl http://localhost:8888/lingframe/dashboard/lings/user-ling/health

# All lings health
curl http://localhost:8888/lingframe/dashboard/lings/health/all

# JVM metrics
curl http://localhost:8888/lingframe/dashboard/lings/metrics
```

### 3.9 Key Difference — Tutorial vs Actual Code

Key differences between this tutorial and the current actual code:

| Item | Tutorial example | Actual code convention |
| :-- | :-- | :-- |
| ling.yml main class field | `main-class` | `mainClass` (camelCase) |
| Ling entry class | implements `Ling` interface | `@SpringBootApplication` |
| Service registration | `@LingService(serviceId = "...")` | `@LingService(id = "...", desc = "...")` |
| Cross-ling invocation | `@LingReference(lingId = "...", serviceId = "...")` | `@LingReference` (auto-matched) |
| capabilities format | `name` / `access-type` | `capability` / `accessType` |

---

## Part 4: First-Round Adoption Strategy

If you are not here to study framework history but to judge "is LingFrame suitable for my current system," this section is for you.

This section does not discuss ideal blueprints. It only answers:

- Which problems are most worth solving with LingFrame first
- How the first round of adoption should go
- How far the current code has actually progressed

### Problems Worth Solving First with LingFrame

The scenarios most worth prioritizing LingFrame for now are not "I want to build a whole platform" but these real ones:

- The system has been running long-term, and no one dares touch it casually
- You need to gradually rebuild boundaries without stopping the system
- You want to收敛 governance capabilities into one unified spine, instead of continuing to scatter them across layers
- You want multi-version coexistence, canary, permission, audit, and unload to all become explainable

If your诉求 is only:

- Build a simple "plugin framework"
- Quickly拼 a frontend extension market
- Immediately turn the monolith into distributed

then LingFrame is currently not the most suitable tool.

### Recommended Adoption Path

**Phase 1: Pick a non-core ling first**

The first step is not to拆 the most core business.

The more稳妥 approach currently is:

- Pick a business capability with relatively clear boundaries
- Let it first become a ling
- First run through the install / invoke / govern / unload / observe chain

**Phase 2: Move contracts before moving business**

Shared API is currently still a hard boundary.

Therefore the recommended order is:

1. First define Shared API contracts
2. Then let business lings implement围绕 the contracts
3. Avoid putting implementation classes into Shared API

**Phase 3: First observe governance behavior under dev mode**

Before going to production, observe governance behavior under `dev-mode: true`. Confirm the chain works end-to-end before tightening.

### Three Technical Facts You Only Need to Know Before Adoption

1. **Lings depend only on `lingframe-api`**, not `lingframe-core`. The governance kernel is not a dependency of the ling.
2. **Shared API is frozen after load**. New shared JARs can be preloaded, but already-loaded shared contracts cannot be hot-updated or hot-unloaded. Breaking changes require restarting the process.
3. **Single-process boundary**. LingFrame governs in-process isolation. It does not govern cross-process — that is the job of Service Mesh / API Gateway.

### A Prudent First-Round Launch Checklist

- [ ] Run the example app end-to-end first
- [ ] Confirm the Dashboard can see real events, not a shadow view
- [ ] Pick a non-core business capability as the first ling
- [ ] Define Shared API contracts first
- [ ] Observe governance behavior under dev mode before tightening
- [ ] Before production, go through the [Production Hardening Checklist](production-hardening.md)

### Mistakes Newcomers Most Easily Make

- Treating LingFrame as a微服务替代品 — it is not; it governs in-process, complementing cross-process architecture
- Putting business implementation into Shared API — Shared API is contracts only, not implementations
- Expecting "absolute static isolation" — LingFrame provides type isolation + orchestration isolation + post-unload provable GC, not runtime orthogonal isolation
- Skipping the governance chain to call lings directly — all cross-ling invocations must go through the Pipeline

### Most Realistic Usage for the Current Stage

The most realistic usage for the current Pre-1.0 stage is:

- Use it in dev / staging to evaluate fit
- Pick one non-core business capability as a pilot ling
- Build confidence through the observe → adjust → converge loop
- Do not yet bet the whole production system on it

---

## What This Execution Has Proven

When the example runs successfully, you have actually verified four things:

- LingCore can discover and load lings within a single process.
- Shared contracts are preloaded before lings start.
- Cross-ling invocations do not bypass the governance kernel.
- The current example configs are ready for you to read further into the development documentation.

If you continue and complete the Dashboard / governance / unload validations above, you will additionally see:

- The control surface can hot-adjust invocation governance parameters.
- Health and governance metrics change following real requests.
- The pre-unload precheck, true unload run, and post-unload diagnostics have formed a primary chain.

The next thing most worth verifying is not just "can we load another ling," but whether this runtime chain can stay orderly under reload / unload / cleanup scenarios.

Next, if you want to dive straight into writing lings, jump to [Ling Development Guide](ling-development.md).
Example tracks overview: [lingframe-examples/README.en.md](../../lingframe-examples/README.en.md) (starter usage / legacy migration `saas-mall`).
For config cross-reference, see the [Production Hardening Checklist](production-hardening.md).
