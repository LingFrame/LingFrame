# Tutorial: From Zero to Hero

This tutorial will guide you through building a complete LingFrame application from scratch.

> ⚠️ **Note**: This tutorial is based on the current actual code structure.

---

## Goal

We will build a simple order management system:

```
LingCore
    │
    ├── user-ling (User Service Ling)
    │   └── Provides user query capabilities
    │
    └── order-ling (Order Service Ling)
        ├── Depends on user-ling for user info
        └── Provides order creation and query capabilities
```

---

## Part 1: Environment Setup

### 1.1 System Requirements

| Software | Version |
|----------|---------|
| JDK | 17 or 8 |
| Maven | 3.6+ |
| Spring Boot | 3.x or 2.7.x |

### 1.2 Create Project Structure

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

## Part 2: Define Shared Contract

### 2.1 Create Shared API Module

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

### 2.2 Define User Query Service Interface

> ⚠️ **Important**: Shared API is the process-level public contract boundary between LingCore and Lings. Interfaces and DTOs used across boundaries should be placed here.

```java
// shared-api/src/main/java/com/example/api/UserQueryService.java
package com.example.api;

import java.util.Optional;

public interface UserQueryService {
    
    Optional<UserDTO> findById(Long userId);
}
```

### 2.3 Define User DTO

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

## Part 3: Create LingCore Application

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
        
        <!-- Database Support (for demo) -->
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
  
  # Shared API preload paths
  preload-api-jars:
    - ../shared-api
    
  # Ling home directory
  ling-home: lings
  
  # Additional ling directories (development mode)
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

### 3.3 Main Application Class

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

## Part 4: Create User Service Ling

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

> ⚠️ **Note**: `ling.yml` uses camelCase naming, such as `mainClass`, `accessType`.

```yaml
# lings/user-ling/src/main/resources/ling.yml
id: user-ling
version: 1.0.0
provider: "Example"
description: "User Service Ling"
mainClass: "com.example.user.UserApplication"

governance:
  permissions: []
  
  # Ling capability requests
  capabilities:
    - capability: "storage:sql"
      accessType: "WRITE"
    - capability: "cache:local"
      accessType: "WRITE"

properties:
  mark: "demo"
```

### 4.3 Ling Entry Class

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

> ⚠️ **Important**: Use `@LingService` annotation to mark methods, declaring them as externally exposed capabilities. LingCore uses this annotation as the key basis for RPC protocol contracts and routing dispatch.

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

---

## Part 5: Create Order Service Ling

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

### 5.2 Ling Entry Class

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

### 5.3 Service Implementation (Cross-Ling Call)

> ⚠️ **Important**: Use `@LingReference` to inject service interfaces provided by other Lings. The framework will search for Beans implementing the interface across all installed Lings.

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

    @LingService(id = "get_order", desc = "Get order by ID")
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
            // Get user info via cross-Ling call
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

---

## Part 6: Run and Test

### 6.1 Build Project

```bash
mvn clean package -DskipTests
```

### 6.2 Start LingCore Application

```bash
cd ling-core
mvn spring-boot:run
```

### 6.3 Manage Lings via Dashboard

> Dashboard is a governance control plane, not just a page.

Dashboard URL: `http://localhost:8888/dashboard.html`

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

### 6.4 Test Calls

```bash
# Create user
curl -X POST "http://localhost:8888/user-ling/user/createUser?name=John&email=john@example.com"

# Query user
curl "http://localhost:8888/user-ling/user/queryUser?userId=1"

# List users
curl http://localhost:8888/user-ling/user/listUsers
```

---

## Part 7: Hot Reload Demo

> ⚠️ Hot reload is only available in development mode (`dev-mode: true`)

### 7.1 Modify Ling Code

Modify `UserServiceImpl`, add logging or change business logic.

### 7.2 Rebuild

```bash
cd lings/user-ling
mvn clean package -DskipTests
```

### 7.3 Hot Reload

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/lings/user-ling/reload
```

---

## Part 8: Governance Capabilities Demo

### 8.1 Canary Release

> Canary is routing a portion of traffic to a specified Ling version or instance.

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/lings/user-ling/canary \
  -H "Content-Type: application/json" \
  -d '{"percent": 20, "canaryVersion": "1.1.0"}'
```

### 8.2 Traffic Statistics

```bash
curl http://localhost:8888/lingframe/dashboard/lings/user-ling/stats
```

### 8.3 Health Metrics

```bash
# Single ling health metrics
curl http://localhost:8888/lingframe/dashboard/lings/user-ling/health

# All lings health metrics
curl http://localhost:8888/lingframe/dashboard/lings/health/all

# JVM metrics
curl http://localhost:8888/lingframe/dashboard/lings/metrics
```

---

## Key Differences

Key differences between this tutorial and the current actual code:

| Item | Tutorial Example | Actual Code Convention |
|------|------------------|------------------------|
| ling.yml main class field | `main-class` | `mainClass` (camelCase) |
| Ling entry class | Implements `Ling` interface | `@SpringBootApplication` |
| Service registration | `@LingService(serviceId = "...")` | `@LingService(id = "...", desc = "...")` |
| Cross-Ling call | `@LingReference(lingId = "...", serviceId = "...")` | `@LingReference` (auto-match) |
| capabilities format | `name` / `access-type` | `capability` / `accessType` |

---

## Summary

Through this tutorial, you have learned:

1. ✅ Create Shared API contract module
2. ✅ Create LingCore application
3. ✅ Create independent Ling modules
4. ✅ Implement cross-Ling calls
5. ✅ Use Dashboard API to manage Lings
6. ✅ Perform hot reload operations
7. ✅ Use canary release and traffic statistics capabilities

Next Steps:

- Read [Troubleshooting Guide](troubleshooting.md) for common issues
- Read [Observability](observability.md) for monitoring capabilities
- Read [Architecture](architecture.md) for deep understanding of framework principles
- Read [Dashboard Documentation](dashboard.md) for complete Dashboard API
- Check [Example Projects](../../lingframe-examples) for more actual code
