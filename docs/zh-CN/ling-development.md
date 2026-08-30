# 业务灵元开发指南

这份指南讲的是：如何写一个符合当前公开运行时边界的业务灵元。

---

## 灵元到底是什么

灵元是一个业务单元，它：

- 运行在灵核进程里
- 拥有自己的 classloader 与生命周期
- 通过灵珑契约暴露服务
- 在治理内核之下运行

如果你对词汇还不熟，先看 [术语表 & FAQ](faq.md)。

---

## 业务演进与灵元开发三大模式

在灵珑微内核架构体系中，业务落地与开发分为三大标准模式：

| 模式 | 适用场景 | Controller 归属 | 核心机制 | 兜底保障 |
| :--- | :--- | :--- | :--- | :--- |
| **模式 1：存量业务透明接管** | 存量老系统平滑改造、老功能绞杀与增强 | **灵核底座（0 修改）** | 灵核 AOP 桥接 / `@LingReference` 动态代理 | 灵核默认实现 100% 自动兜底（永不 404） |
| **模式 2：新业务契约门面** | 企业核心新业务、平台中台能力扩展 | **灵核提供薄门面（统一鉴权/审计）** | 灵核注入 `@LingReference` 动态契约路由 | 灵核统一熔断降级与默认保底策略 |
| **模式 3：新业务自包含端点** | 完全独立的新业务功能、第三方能力包 | **灵元内部自包含** | 灵珑微内核动态注册/注销 Spring MVC 端点 | 卸载后端点即时注销，零内存残留 |

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

---

## 约束与限制（明写出来，避免踩坑）

灵珑再低侵入，也仍然有边界。这一节明写出来——把这些限制看清楚，比相信「万能隔离」更能放心用。

### 隔离边界是「编排隔离 / 类型隔离」，不是「绝对隔离」

在单 JVM + 共享灵核 Spring 上下文下，**物理上不存在「绝对隔离」**——进程级静态缓存（如 `AnnotatedElementUtils`、`BridgeMethodResolver.cache`）会持有灵元 Class 引用。

灵珑能做到的诚实表述是：
- **类型隔离**：每个灵元有独立 `LingClassLoader`（Child-First），灵元之间看到的同名类是不同 `Class` 对象
- **编排隔离**：灵元卸载时由 `LingUnloadCoordinator` 排空请求、驱逐资源、清理缓存引用，卸载后可证 GC
- **BeanFactory 层隔离**：灵元 Spring 上下文与灵核隔离

灵珑做不到、也不承诺的：
- 灵元之间「永不引用对方」——灵元代码自己持有另一个灵元的对象，框架不拦
- 「灵元卸载后 ClassLoader 立刻被 GC」——只承诺「编排到位 + 资源清理后可证 GC」，若灵元代码有静态集合/线程泄漏，框架诊断能报但不能替它清

### Shared API 一旦冻结，破坏性变更必须重启进程

Shared API 是进程级公共契约边界。灵元加载前预加载并冻结后：
- **全新的 Shared API JAR 可以热加载**（增）
- **已进入共享边界的 JAR 不允许热更新或热卸载**（不可改、不可删）

如果硬改，会出现同一个类被不同 ClassLoader 加载，导致 `ClassCastException`、类型系统整体失真。**破坏性变更的正确路径是重启进程**。

### 灵元能用 AOP / 独立线程 / 静态变量，但卸载时不会自动回收

灵元里可以使用 Spring AOP、可以起独立线程、可以用静态变量——框架不拦。但**这些东西在灵元卸载时不会自动消失**：

| 资源 | 卸载时默认行为 | 你必须做什么 |
| --- | --- | --- |
| 灵元 `@Component` / Bean | 由 `SpringLingContainer.stop()` 关闭 | 通常无需手动 |
| 独立线程池 / 调度器 | **不会自动停**——daemon 线程持有灵元 Class → ClassLoader 引用链 | 实现 `DisposableBean.destroy()` 或 `@PreDestroy`，shutdown 调度器并清空任务 |
| 静态集合 | **不会自动清**——静态引用持有灵元类对象 | 在卸载钩子里主动清空 |
| ThreadLocal | **不会自动移除** | 在停止回调里 `remove()` |

**正例**：业务灵元在持有独立线程资源时显式实现 `DisposableBean.destroy()` 或标注 `@PreDestroy`，关闭调度器并清空驻留数据（如在单体改造项目 `LingFrame-RuoYi` 中扩展灵元的退出逻辑）——这是灵元持有线程资源时的必须姿势。

### DB 治理边界：覆盖 Spring DataSource Bean 代理路径，不是全沙箱

灵珑的存储权限治理**主要覆盖 Spring `DataSource` Bean 代理路径**——即通过灵核 Spring 容器获取的 DataSource 调用会被治理。

**能绕过的**：
- `DriverManager.getConnection()` 手搓连接
- 非 Bean 的数据库连接池
- 灵元自己引入的独立 DataSource

这是**模型边界**，不是全路径沙箱——文档里不会把它吹成「全路径沙箱」。如果你的灵元需要严格存储治理，请通过灵核 Spring DataSource 路径访问数据库。

### 灵元侧引入 Spring Data JPA / Hibernate 的硬边界（实测结论）

灵元侧引入 `spring-boot-starter-data-jpa` 属于未经完整官方适配的高级场景，真实运行已通过 `ManagedJpaBoundaryTest` 实证三条硬边界：

1. **必须显式配置方言**：受管代理在元数据安全脱敏时将 URL 替换为 `jdbc:lingframe:masked`，导致 Hibernate 无法基于 URL 自动解析方言，启动报 `Access to DialectResolutionInfo cannot be null`。**灵元必须显式配置 `spring.jpa.database-platform`（如 `org.hibernate.dialect.MySQLDialect`），否则 EntityManagerFactory 启动即失败**；
2. **双事务管理器自动抑制，无注入歧义**：Spring Boot 的 `JpaBaseConfiguration` 带有 `@ConditionalOnMissingBean`。灵珑注入受管事务管理器 `lingTransactionManager`（`LingManagedTransactionManager`）后，JPA 自带的 `JpaTransactionManager` 会被自动抑制，容器仅有唯一事务管理器，无 `NoUniqueBeanDefinitionException`；
3. **穿透连接提交权降级**：事务穿透命中时，受管代理返回不可物理关闭的代理连接（`NonCloseableLingConnectionProxy`），Hibernate 的 `setAutoCommit(false)`、`commit()`、`close()` 全部被拦截为 safe no-op，`rollback()` 仅置回滚信号上行，底层物理连接提交权安全收敛至灵核根事务。

### 灵元依赖纪律：provided 依赖灵核接口，误打成 compile 会导致 Class 身份错乱

灵元 `implements` 灵核原生接口时（如业务灵元实现灵核已有服务接口），pom 必须用 `<scope>provided</scope>` 依赖灵核模块：

```xml
<!-- 正确：provided，运行期由灵核 ClassLoader 父回退解析 -->
<dependency>
    <groupId>com.example</groupId>
    <artifactId>example-app-lingcore</artifactId>
    <scope>provided</scope>
</dependency>
```

运行期灵元 `LingClassLoader` 父回退到灵核 ClassLoader 解析这些接口，**灵核与灵元看到同一个 `Class` 对象，身份一致**。

如果误打成 `compile`（默认 scope），灵元 JAR 会打包一份灵核接口类，Child-First 加载出**第二份 Class**——导致 `ClassCastException: com.example.UserService cannot be cast to com.example.UserService`（同名不同 ClassLoader）。

### 灵元不应直接依赖 `lingframe-core`

灵元只能依赖 `lingframe-api`（契约层：接口、注解、异常、安全抽象）。**直接依赖 `lingframe-core` 是越界**——会把治理内核实现类拉进灵元 ClassLoader，卸载时形成不可解的引用链。

 普通 `@Component` 业务灵元不需要、也不应该接触 `lingframe-core` 的任何类。
