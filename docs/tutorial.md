# Practical Tutorial: from Scratch

This tutorial will take you step-by-step from zero to a complete LingFrame application.

> ⚠️ **Note**: This tutorial is written against the current actual codebase structure.

---

## Goal

We will build a simple order management system:

```
LingCore
    �?    ├── user-ling (User Service Ling)
    �?  └── Provides user query capabilities
    �?    └── order-ling (Order Service Ling)
        ├── Depends on user-ling to fetch user info
        └── Provides order creation, query capabilities
```

---

## Part 1: Environment Preparation

### 1.1 System Requirements

| Software | Version |
|------|------|
| JDK | 17 or 8 |
| Maven | 3.6+ |
| Spring Boot | 3.x or 2.7.x |

### 1.2 Creating the Project Structure

```bash
mkdir lingframe-demo
cd lingframe-demo

mkdir -p ling-core
mkdir -p shared-api
mkdir -p lings/user-ling
mkdir -p lings/order-ling
```

### 1.3 Parent POM Configuration

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

---

## Part 2: Defining Shared Contracts

### 2.1 Create the Shared API Module

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

### 2.2 Define the User Query Service Interface

> ⚠️ **Important**: `Shared API` is the process-level common contract boundary between LingCore and Lings. Any interfaces and DTOs used across this boundary must be placed here.

```java
// shared-api/src/main/java/com/example/api/UserQueryService.java
package com.example.api;

import java.util.Optional;

public interface UserQueryService {
    
    Optional<UserDTO> findById(Long userId);
}
```

### 2.3 Define the User DTO

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

---

## Part 3: Creating the LingCore App

### 3.1 LingCore POM Configuration

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
        <!-- LingFrame Spring Boot Starter -->
        <dependency>
            <groupId>com.lingframe</groupId>
            <artifactId>lingframe-spring-boot3-starter</artifactId>
        </dependency>
        
        <!-- Dashboard (Optional) -->
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
        
        <!-- Database capabilities (needed for example) -->
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

### 3.2 Application Configuration

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
  
  # Shared API preload path
  preload-api-jars:
    - ../shared-api
    
  # Ling home folder
  ling-home: lings
  
  # Extra ling roots (for dev mode)
  ling-roots:
    - ../lings/user-ling
    - ../lings/order-ling
  
  # Dashboard configuration
  dashboard:
    enabled: true
    install-enabled: true

logging:
  level:
    com.lingframe: INFO
```

### 3.3 Main Class

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

---

## Part 4: Building the User Service Ling

### 4.1 Ling POM Configuration

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

### 4.2 Ling Manifest

> ⚠️ **Note**: `ling.yml` uses camelCase for keys like `mainClass` and `accessType`.

```yaml
# lings/user-ling/src/main/resources/ling.yml
id: user-ling
version: 1.0.0
provider: "Example"
description: "User Service Ling"
mainClass: "com.example.user.UserApplication"

governance:
  permissions: []
  
  # Declaring required capabilities
  capabilities:
    - capability: "storage:sql"
      accessType: "WRITE"
    - capability: "cache:local"
      accessType: "WRITE"

properties:
  mark: "demo"
```

### 4.3 Ling Application Class

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

### 4.4 Service Interface Definition

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

### 4.5 Service Implementation

> ⚠️ **Important**: Use the `@LingService` annotation on methods to declare an externally exposed capability. LingCore uses this annotation as the key basis for RPC contract tracking and routing dispatch.

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

    @LingService(id = "create_user", desc = "Create single user")
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

---

## Part 5: Building the Order Service Ling

### 5.1 Ling Manifest

```yaml
# lings/order-ling/src/main/resources/ling.yml
id: order-ling
version: 1.0.0
provider: "Example"
description: "Order Service Ling"
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

### 5.2 Ling Application Class

```java
// lings/order-ling/src/main/java/com/example/order/OrderApplication.java
package com.example.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@EnableCaching
@SpringBootApplication
public class OrderApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
```

### 5.3 Service Implementation (Cross-Ling Invocation)

> ⚠️ **Important**: Use `@LingReference` to inject a service interface provided by another ling. The framework automatically finds the Bean implementing this interface amongst installed lings.

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

    @LingService(id = "get_order", desc = "Query Order By ID")
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
            // Fetch User info via cross-ling invocation
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

    @LingService(id = "create_order", desc = "Create Order")
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

---

## Part 6: Running and Testing

### 6.1 Build Projects

```bash
mvn clean package -DskipTests
```

### 6.2 Start LingCore App

```bash
cd ling-core
mvn spring-boot:run
```

### 6.3 Manage Lings via Dashboard

> Dashboard is a governance control surface, not just a static page.

Dashboard UI: `http://localhost:8888/dashboard.html`

**List Lings**:

```bash
curl http://localhost:8888/lingframe/dashboard/lings
```

**Activate Lings**:

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/lings/user-ling/status \
  -H "Content-Type: application/json" \
  -d '{"status": "ACTIVE"}'

curl -X POST http://localhost:8888/lingframe/dashboard/lings/order-ling/status \
  -H "Content-Type: application/json" \
  -d '{"status": "ACTIVE"}'
```

### 6.4 Let's Test

```bash
# Create User
curl -X POST "http://localhost:8888/user-ling/user/createUser?name=ZhangSan&email=zhangsan@example.com"

# Query User
curl "http://localhost:8888/user-ling/user/queryUser?userId=1"

# List Users
curl http://localhost:8888/user-ling/user/listUsers
```

---

## Part 7: Hot-Reload Demo

> ⚠️ Hot-reload is only available in development mode (`dev-mode: true`)

### 7.1 Modify Ling Code

Modify `UserServiceImpl`, maybe by adding a log or altering business logic.

### 7.2 Recompile

```bash
cd lings/user-ling
mvn clean package -DskipTests
```

### 7.3 Hot-Reload It

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/lings/user-ling/reload
```

---

## Part 8: Governance Demo

### 8.1 Canary Releases

> Canary routing shifts a subset of traffic over to a specialized ling version.

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/lings/user-ling/canary \
  -H "Content-Type: application/json" \
  -d '{"percent": 20, "canaryVersion": "1.1.0"}'
```

### 8.2 Traffic Stats

```bash
curl http://localhost:8888/lingframe/dashboard/lings/user-ling/stats
```

### 8.3 Health Snapshots

```bash
# Single ling health
curl http://localhost:8888/lingframe/dashboard/lings/user-ling/health

# All lings health
curl http://localhost:8888/lingframe/dashboard/lings/health/all

# JVM Metrics
curl http://localhost:8888/lingframe/dashboard/lings/metrics
```

---

## Key Differences

If tracing the actual codebase vs. this tutorial, mind these details:

| Item | Tutorial | Actual Code Checks |
|------|----------|--------------|
| `ling.yml` main class field | `main-class` | `mainClass` (camelCase) |
| Ling App class | implements `Ling` | `@SpringBootApplication` |
| Service Registry | `@LingService(serviceId = "...")` | `@LingService(id = "...", desc = "...")` |
| Cross-ling Call | `@LingReference(lingId = "...", serviceId = "...")` | `@LingReference` (Auto Match) |
| Capabilities Config | `name` / `access-type` | `capability` / `accessType` |

---

## Conclusion

Through this tutorial, you have learned to:

1. �?Create Shared API modules
2. �?Create the LingCore application
3. �?Create an independent Ling module
4. �?Achieve a cross-ling invocation
5. �?Use the Dashboard API to manage lings
6. �?Execute hot-reloads
7. �?Use canary releases and traffic statistics features

Recommended next steps:

- Read [Troubleshooting](troubleshooting.md) for dealing with errors.
- Read [Observability](observability.md) to understand monitoring capabilities.
- Read [Architecture](architecture.md) for a deeper conceptual framing.
- Read [Dashboard Docs](dashboard.md) to comprehend the full Dashboard API layout.
- Look at the [Examples Folder](../lingframe-examples) to read more code paths.
