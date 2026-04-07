# 实战教程：从零开始

本教程将带你从零开始，完成一个完整的灵珑应用开发。

> ⚠️ **注意**：本教程基于当前实际代码结构编写。

---

## 目标

我们将构建一个简单的订单管理系统：

```
灵核（LingCore）
    │
    ├── user-ling（用户服务灵元）
    │   └── 提供用户查询能力
    │
    └── order-ling（订单服务灵元）
        ├── 依赖 user-ling 获取用户信息
        └── 提供订单创建、查询能力
```

---

## 第一部分：环境准备

### 1.1 系统要求

| 软件 | 版本 |
|------|------|
| JDK | 17 或 8 |
| Maven | 3.6+ |
| Spring Boot | 3.x 或 2.7.x |

### 1.2 创建项目结构

```bash
mkdir lingframe-demo
cd lingframe-demo

mkdir -p ling-core
mkdir -p shared-api
mkdir -p lings/user-ling
mkdir -p lings/order-ling
```

### 1.3 父 POM 配置

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

## 第二部分：定义共享契约

### 2.1 创建 Shared API 模块

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

### 2.2 定义用户查询服务接口

> ⚠️ **重要**：Shared API 是灵核与灵元之间的进程级公共契约边界。跨边界使用的接口与 DTO 都应该放在这里。

```java
// shared-api/src/main/java/com/example/api/UserQueryService.java
package com.example.api;

import java.util.Optional;

public interface UserQueryService {
    
    Optional<UserDTO> findById(Long userId);
}
```

### 2.3 定义用户 DTO

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

## 第三部分：创建灵核应用

### 3.1 灵核 POM 配置

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
        
        <!-- Dashboard（可选） -->
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
        
        <!-- 数据库支持（示例需要） -->
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

### 3.2 应用配置

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
  
  # Shared API 预加载路径
  preload-api-jars:
    - ../shared-api
    
  # 灵元存放根目录
  ling-home: lings
  
  # 灵元额外目录（开发模式）
  ling-roots:
    - ../lings/user-ling
    - ../lings/order-ling
  
  # Dashboard 配置
  dashboard:
    enabled: true
    install-enabled: true

logging:
  level:
    com.lingframe: INFO
```

### 3.3 启动类

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

## 第四部分：创建用户服务灵元

### 4.1 灵元 POM 配置

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

### 4.2 灵元清单

> ⚠️ **注意**：`ling.yml` 使用驼峰命名法，如 `mainClass`、`accessType`。

```yaml
# lings/user-ling/src/main/resources/ling.yml
id: user-ling
version: 1.0.0
provider: "Example"
description: "用户服务灵元"
mainClass: "com.example.user.UserApplication"

governance:
  permissions: []
  
  # 灵元能力申请
  capabilities:
    - capability: "storage:sql"
      accessType: "WRITE"
    - capability: "cache:local"
      accessType: "WRITE"

properties:
  mark: "demo"
```

### 4.3 灵元入口类

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

### 4.4 服务接口定义

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

### 4.5 服务实现

> ⚠️ **重要**：使用 `@LingService` 注解标记方法，声明这是一个对外暴露的能力。灵核将此注解作为 RPC 协议契约和路由分发的关键依据。

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

    @LingService(id = "query_user", desc = "根据ID查询用户")
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

    @LingService(id = "list_users", desc = "列出所有用户")
    @RequiresPermission(Capabilities.STORAGE_SQL)
    @Auditable(action = "LIST_USERS", resource = "user")
    @Override
    public List<UserDTO> listUsers() {
        return jdbcTemplate.query(
            "SELECT * FROM t_user", 
            new BeanPropertyRowMapper<>(UserDTO.class)
        );
    }

    @LingService(id = "create_user", desc = "创建用户")
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

## 第五部分：创建订单服务灵元

### 5.1 灵元清单

```yaml
# lings/order-ling/src/main/resources/ling.yml
id: order-ling
version: 1.0.0
provider: "Example"
description: "订单服务灵元"
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

### 5.2 灵元入口类

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

### 5.3 服务实现（跨灵元调用）

> ⚠️ **重要**：使用 `@LingReference` 注入其他灵元提供的服务接口。框架会在所有已安装的灵元中查找实现了该接口的 Bean。

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

    @LingService(id = "get_order", desc = "根据ID查询订单")
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
            // 通过跨灵元调用获取用户信息
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

    @LingService(id = "create_order", desc = "创建订单")
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

## 第六部分：运行与测试

### 6.1 编译项目

```bash
mvn clean package -DskipTests
```

### 6.2 启动灵核应用

```bash
cd ling-core
mvn spring-boot:run
```

### 6.3 通过 Dashboard 管理灵元

> Dashboard 是治理控制面，而不只是一个页面。

Dashboard 地址：`http://localhost:8888/dashboard.html`

**查看灵元列表**：

```bash
curl http://localhost:8888/lingframe/dashboard/lings
```

**激活灵元**：

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/lings/user-ling/status \
  -H "Content-Type: application/json" \
  -d '{"status": "ACTIVE"}'

curl -X POST http://localhost:8888/lingframe/dashboard/lings/order-ling/status \
  -H "Content-Type: application/json" \
  -d '{"status": "ACTIVE"}'
```

### 6.4 测试调用

```bash
# 创建用户
curl -X POST "http://localhost:8888/user-ling/user/createUser?name=张三&email=zhangsan@example.com"

# 查询用户
curl "http://localhost:8888/user-ling/user/queryUser?userId=1"

# 列出用户
curl http://localhost:8888/user-ling/user/listUsers
```

---

## 第七部分：热更新演示

> ⚠️ 热更新仅在开发模式（`dev-mode: true`）下可用

### 7.1 修改灵元代码

修改 `UserServiceImpl`，添加日志或修改业务逻辑。

### 7.2 重新编译

```bash
cd lings/user-ling
mvn clean package -DskipTests
```

### 7.3 热更新

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/lings/user-ling/reload
```

---

## 第八部分：治理能力演示

### 8.1 灰度发布

> 灰度是把一部分流量路由到指定灵元版本或实例。

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/lings/user-ling/canary \
  -H "Content-Type: application/json" \
  -d '{"percent": 20, "canaryVersion": "1.1.0"}'
```

### 8.2 流量统计

```bash
curl http://localhost:8888/lingframe/dashboard/lings/user-ling/stats
```

### 8.3 健康指标

```bash
# 单个灵元健康指标
curl http://localhost:8888/lingframe/dashboard/lings/user-ling/health

# 所有灵元健康指标
curl http://localhost:8888/lingframe/dashboard/lings/health/all

# JVM 指标
curl http://localhost:8888/lingframe/dashboard/lings/metrics
```

---

## 关键差异说明

本教程与当前实际代码的关键差异：

| 项目 | 教程示例 | 实际代码规范 |
|------|----------|--------------|
| ling.yml 主类字段 | `main-class` | `mainClass`（驼峰） |
| 灵元入口类 | 实现 `Ling` 接口 | `@SpringBootApplication` |
| 服务注册 | `@LingService(serviceId = "...")` | `@LingService(id = "...", desc = "...")` |
| 跨灵元调用 | `@LingReference(lingId = "...", serviceId = "...")` | `@LingReference`（自动匹配） |
| capabilities 格式 | `name` / `access-type` | `capability` / `accessType` |

---

## 总结

通过本教程，你已经学会了：

1. ✅ 创建 Shared API 契约模块
2. ✅ 创建灵核应用
3. ✅ 创建独立的灵元模块
4. ✅ 实现跨灵元调用
5. ✅ 使用 Dashboard API 管理灵元
6. ✅ 执行热更新操作
7. ✅ 使用灰度发布和流量统计能力

下一步建议：

- 阅读 [故障排查手册](troubleshooting.md) 了解常见问题
- 阅读 [可观测性](observability.md) 了解监控能力
- 阅读 [架构设计](architecture.md) 深入理解框架原理
- 阅读 [Dashboard 文档](dashboard.md) 了解完整的 Dashboard API
- 查看 [示例项目](../../lingframe-examples) 了解更多实际代码
