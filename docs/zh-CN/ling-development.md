# 业务灵元开发指南

这份指南讲的是：如何写一个符合当前公开运行时边界的业务灵元。

---

## 灵元到底是什么

灵元是一个业务单元，它：

- 运行在灵核进程里
- 拥有自己的 classloader 与生命周期
- 通过灵珑契约暴露服务
- 在治理内核之下运行

如果你对词汇还不熟，先看 [术语表](glossary.md)。

---

## 一个最小可用灵元需要什么

- 一个 Maven 模块
- 一个实现 `Ling` 的入口类
- 一个 `ling.yml` 描述文件

### 1. Maven 依赖

```xml
<dependencies>
    <dependency>
        <groupId>com.lingframe</groupId>
        <artifactId>lingframe-api</artifactId>
        <version>${lingframe.version}</version>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter</artifactId>
    </dependency>
</dependencies>
```

### 2. 入口类

```java
@SpringBootApplication
public class MyLing implements Ling {

    @Override
    public void onStart(LingContext context) {
        System.out.println("Ling started: " + context.getLingId());
    }

    @Override
    public void onStop(LingContext context) {
        System.out.println("Ling stopped: " + context.getLingId());
    }
}
```

### 3. `ling.yml`

```yaml
id: my-ling
version: 1.0.0
description: My first ling
mainClass: com.example.myling.MyLing
```

---

## 如何暴露服务

在生产者实现类上使用 `@LingService`。

灵珑遵循"消费者驱动契约"：

- 由消费者定义它需要的接口
- 由生产者灵元实现这份接口

```java
public interface UserQueryService {
    Optional<UserDTO> findById(String userId);
}
```

```java
@Component
public class UserQueryServiceImpl implements UserQueryService {

    @LingService(id = "find_user", desc = "Query user by ID")
    @Override
    public Optional<UserDTO> findById(String userId) {
        return userRepository.findById(userId).map(this::toDTO);
    }
}
```

最终服务标识格式为：`lingId:serviceId`。

---

## 如何调用其他灵元

推荐顺序如下：

### 方式 1：`@LingReference`

```java
@Component
public class OrderService {

    @LingReference
    private UserQueryService userQueryService;

    public Order createOrder(String userId) {
        UserDTO user = userQueryService.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return new Order(user);
    }
}
```

### 方式 2：`LingContext.getService()`

当你想显式处理"服务是否存在"时使用。

### 方式 3：`LingContext.invoke()`

当你明确想通过 FQSID 做更松耦合调用时再使用。

---

## 如何声明治理要求

### 在 `ling.yml` 中声明权限

```yaml
governance:
  permissions:
    - methodPattern: "storage:sql"
      permissionId: "READ"
    - methodPattern: "cache:local"
      permissionId: "WRITE"
```

### 通过注解补充语义

```java
@RequiresPermission("user:write")
@Auditable(action = "CREATE_USER", resource = "user")
public UserDTO createUser(CreateUserRequest request) {
    ...
}
```

### 开发模式

```yaml
lingframe:
  dev-mode: true
```

---

## 如何打包与加载

### 开发路径

让灵核指向源码根目录，边开发边重新编译灵元。

### 生产路径

把灵元打成 jar，放入 `ling-home` 目录。

```bash
mvn clean package
```

---

## 运行时已经替你做了什么

在当前实现里，业务灵元不需要自己去实现治理内核。

运行时已经提供：

- 统一调用治理
- 生命周期协调
- 灰度路由
- 模拟支持
- 卸载清理钩子
- 泄漏诊断

你的主要职责是：

- 把契约写清楚
- 把业务实现写干净
- 把权限声明写诚实

---

## 最佳实践

- 第一批灵元尽量小
- `Shared API` 只放契约
- 第一条调用路径优先用 `@LingReference`
- 在 `ling.yml` 中显式声明权限
- 用 SLF4J 日志
- 普通业务灵元不要直接依赖 `lingframe-core`

如果你下一步要看契约边界，去 [Shared API 设计规范](shared-api-guidelines.md)；如果你要看基础设施代理模式，去 [基础设施开发指南](infrastructure-development.md)。
