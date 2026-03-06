# 共享 API 设计规范

## 架构概述

```
灵核 ClassLoader (AppClassLoader)
    ↓ parent
SharedApiClassLoader (共享 API 层)
    ↓ parent
LingClassLoader (灵元实现层)
```

## 核心设计原则

### 1. API 由消费方提供（消费者驱动契约）

```
┌─────────────────────────────────────────────────────────────┐
│                     消费者驱动契约模式                        │
└─────────────────────────────────────────────────────────────┘

场景：Order 灵元需要查询用户信息

┌─────────────┐     需要能力      ┌─────────────┐
│  Order 灵元  │ ───────────────▶ │  User 灵元   │
│  (消费者)    │                  │  (生产者)    │
└─────────────┘                  └─────────────┘
       │                               ▲
       │ 1. 定义所需接口               │ 2. 实现消费者定义的接口
       ▼                               │
┌─────────────────────────────────────────────────────────────┐
│                      order-api 灵元                          │
│          (由消费者 Order 灵元定义和维护)                       │
│                                                              │
│   public interface UserQueryService {                        │
│       Optional<UserDTO> findById(String userId);             │
│   }                                                          │
└─────────────────────────────────────────────────────────────┘
```

**核心原则**：
- API 接口由**消费方**定义和维护（谁需要能力，谁定义接口）
- 生产方**实现**消费方定义的接口（谁有能力，谁提供实现）
- 消费方最了解自己需要什么功能，接口设计更贴合实际需求

**为什么这样设计？**
- 传统模式：User 灵元定义 `UserService`，所有消费者适配生产者的接口
- 消费者驱动：Order 灵元定义 `UserQueryService`（只包含它需要的方法），User 灵元适配消费者需求
- 优势：解耦更彻底，消费者不依赖生产者的全量接口，可独立演进

---

## API 灵元结构

### 2. API 灵元只包含接口和 DTO

消费者（Order 灵元）定义它需要的接口，生产者（User 灵元）实现：

```
order-api/                              # 消费者 Order 灵元的 API 灵元
├── src/main/java/com/example/order/
│   ├── api/
│   │   ├── UserQueryService.java      # Order 需要的用户查询能力（由 User 灵元实现）
│   │   └── PaymentService.java        # Order 需要的支付能力（由 Payment 灵元实现）
│   └── dto/
│       ├── UserDTO.java               # 用户数据传输对象
│       └── PaymentResultDTO.java
└── pom.xml
```

**不应该包含**：
- ❌ 业务逻辑实现
- ❌ 数据库访问代码
- ❌ Spring 组件（@Service, @Repository 等）
- ❌ 治理逻辑（熔断、重试等）

### 3. DTO 设计规范

```java
// ✅ 正确：简单 POJO，可序列化
@Data
public class OrderDTO implements Serializable {
    private Long id;
    private String orderNo;
    private BigDecimal amount;
    private LocalDateTime createTime;
}

// ❌ 错误：包含业务逻辑或复杂依赖
public class OrderDTO {
    private Order order;  // 不要引用实体类
    public void process() { ... }  // 不要有业务方法
}
```

### 3. 避免重量级依赖

API 灵元的依赖应该尽量精简：

```xml
<!-- ✅ 推荐的依赖 -->
<dependencies>
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <scope>provided</scope>
    </dependency>
</dependencies>

<!-- ❌ 避免的依赖 -->
<!-- 不要引入 Spring、数据库驱动等重量级依赖 -->
```

## API 演进原则

### 4. 向后兼容（强烈推荐）

```java
// ✅ 正确：只增加，不修改
interface OrderService {
    Order getOrder(Long id);           // v1 保留
    List<Order> batchGet(List<Long> ids); // v2 新增
}

// ❌ 错误：修改现有方法签名
interface OrderService {
    OrderDTO getOrder(String orderId); // 破坏兼容！
}
```

### 5. 破坏性变更使用版本化包名

```java
// 版本 1
package com.example.order.api.v1;
public interface OrderService { ... }

// 版本 2（不兼容）
package com.example.order.api.v2;
public interface OrderService { ... }
```

两个版本可以共存于 SharedApiClassLoader。

## 灰度发布支持

| 场景 | 支持 | 处理方式 |
|------|------|----------|
| 新增 API 方法 | ✅ | 增量添加 JAR |
| 破坏性变更 | ✅ | 版本化包名 |
| 新旧灵元共存 | ✅ | API 向后兼容 |

### 灰度流程示例

```
T0: LingA-v1 + API-v1
T1: 添加 API-v2，部署 LingA-v2（v1/v2 共存）
T2: 验证通过，卸载 LingA-v1
```

## 配置示例

```yaml
lingframe:
  preload-api-jars:
    - api/order-api-*.jar      # 通配符加载多版本
    - api/user-api/            # 目录自动扫描
    - lingframe-examples/lingframe-example-order-api  # Maven 灵元（开发模式）
```

## 常见问题

### Q: ClassNotFoundException / NoClassDefFoundError

**原因**：API 未正确加载到 SharedApiClassLoader

**检查**：
1. 确认 `preload-api-jars` 配置正确
2. 确认 JAR/目录路径存在
3. 查看启动日志中的 `📦 [SharedApi]` 输出

### Q: ClassCastException

**原因**：同一个类被不同 ClassLoader 加载

**解决**：确保 API 类只在 SharedApiClassLoader 中加载，不要在灵元 JAR 中重复打包
