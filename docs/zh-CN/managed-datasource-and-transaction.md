# 灵珑多模数据源与跨灵元事务一致性开发者指南

> **适用版本**：LingFrame 0.4.0+  
> **核心定位**：面向应用架构师与业务开发者的实战指南，全面阐明三种数据源架构模式、事务穿透运行机制与物理约束边界。

---

## 一、 为什么需要多模数据源与微内核穿透？

在传统的单体架构中，所有业务模块运行在同一个 ClassLoader 和同一个 Spring 容器中，一个 `@Transactional` 注解即可轻松实现跨方法、跨模块的原子回滚。

然而，当应用引入**动态模块化与微内核架构**后：
1. **类加载器隔离（ClassLoader Isolation）**：每个业务灵元拥有独立的类加载器与子容器，物理隔离阻断了常规的 Bean 依赖；
2. **连接池碎片化**：若每个灵元各自初始化连接池，数据库连接数会呈指数级爆炸，且灵核与各灵元之间的 Spring `@Transactional` 发生**物理断裂**；
3. **一致性割裂**：灵核业务发起事务调用灵元 A，灵元 A 执行失败，灵核回滚但灵元 A 已经独立提交，引发灾难性的数据不一致。

为了在**“模块解耦与热插拔”**与**“企业级 ACID 强一致性”**之间取得完美平衡，灵珑（LingFrame）提出了**“推荐 1，保留 2，扩展 3”的双轨制事务治理体系**。

---

## 二、 三大数据源与事务架构模式全景

针对不同的业务隔离诉求与基础设施拓扑，灵珑提供了三种本质不同的架构模式：

```
+---------------------------------------------------------------------------------------------------+
|                                   灵珑数据源与事务双轨制拓扑                                      |
+---------------------------------------------------------------------------------------------------+
|                                                                                                   |
|  【强一致轨道：模式 1 基础设施托管 (推荐态)】              【最终一致轨道：模式 2 领域自治 (隔离态)】      |
|                                                                                                   |
|      灵核 (LingCore) [物理连接池 (HikariCP/Druid)]               灵核 (LingCore)                          |
|         │                                                          │                              |
|         │  微内核 Pipeline 透明单连接穿透                           │  EventBus 发布进程内领域事件  |
|         ▼  (NonCloseableLingConnectionProxy)                       ▼  (OrderCreatedEvent)         |
|      业务灵元 (OrderLing / AccountLing)                         业务灵元 [自建独立物理连接池]      |
|         (0 数据源配置，单机本地 ACID 强事务回滚)                    (独立物理库，Saga 弹性补偿机制)   |
|                                                                                                   |
|---------------------------------------------------------------------------------------------------|
|                                                                                                   |
|  【扩展强一致：模式 3 基础设施灵元化 (扩展态)】                                                   |
|      灵核 (0 存储，极致纯洁) ◄─── 热挂载 ─── 存储灵元 (StorageLing，持有连接池，只增不减)          |
|                                                     │                                             |
|                                                     ▼ 受管数据源总线引渡 (dataSourceId)           |
|                                              上层业务灵元 (共享连接池，链路内强一致)                |
+---------------------------------------------------------------------------------------------------+
```

### 2.1 模式 1：基础设施托管模式（Managed DataSource Pattern，推荐开箱即用态）
- **架构拓扑**：由灵核（LingCore）统一声明并托管唯一的物理连接池（HikariCP、Druid 等）与事务管理器。业务灵元无需配置任何 JDBC 参数，通过微内核受管总线自动接入底座连接。
- **事务哲学**：**单机本地 ACID 强事务**。微内核流水线（Pipeline）在跨 ClassLoader 调用灵元时，透明将当前线程已绑定的物理数据库连接穿透注入下游灵元。
- **一致性目标**：**强一致性**（跨灵元同进同退，任何灵元抛异常或触发回滚，整条调用链路原子回滚）。
- **适用场景**：绝大多数企业核心业务（ERP、CRM、电商、标准单体系统重构），追求零迁移心智负担与单机原子回滚。

### 2.2 模式 2：领域完全自治模式（Database-per-Ling Pattern，物理隔离态）
- **架构拓扑**：各业务灵元完全自治，在自身配置文件中声明独立的 `spring.datasource.url`，自建独立的物理连接池，连接物理隔离的数据库实例（如订单库、结算库，甚至异构的 MongoDB/PostgreSQL）。
- **事务哲学**：**最终一致性（Eventual Consistency）**。由于物理上是完全不同的数据库实例与物理连接，单机 JDBC 层面无法做到物理回滚。必须依托微内核进程内极速 `EventBus` 发送领域事件，配合 Saga 状态机或重试补偿。
- **一致性目标**：**最终一致性**。
- **适用场景**：多租户物理分库、异构混合存储、外部不可逆操作（如支付、短信发信）、领域边界极强的大型模块。

### 2.3 模式 3：基础设施灵元化模式（Storage-Ling Pattern，动态扩展态）
- **架构拓扑**：灵核保持绝对的“零存储、零 JDBC 依赖”。连接池与存储驱动被封装在一个或多个专职的“存储基础设施灵元”中，在运行时动态热挂载，并向微内核总线注册受管数据源（按 `dataSourceId` 区分）。
- **事务哲学**：**同一受管数据源链路内支持强事务穿透**。
- **生命周期硬规则**：**只增不减**。存储灵元一旦挂载成功即常驻运行期，禁用热卸载，避免底层连接池级联销毁引发业务瘫痪。
- **适用场景**：边缘计算轻量底座、多云存储热切换、无 JDBC 纯计算节点的按需扩展。

### 2.4 三大模式全景决策对比表

| 比较维度 | 模式 1：基础设施托管 (推荐) | 模式 2：领域完全自治 (隔离) | 模式 3：基础设施灵元化 (扩展) |
| :--- | :--- | :--- | :--- |
| **连接池持有方** | 灵核底座统一持有 | 各业务灵元独立自建 | 专职存储灵元持有 |
| **连接池生命周期** | 静态（启动固定，运行期不可变） | 静态（随业务灵元生命周期） | 动态热挂载（**只增不减，禁用卸载**） |
| **多源异构能力** | 单一同构库 | 各灵元完全自由对接异构库 | 多个存储灵元对接多源异构 |
| **物理连接开销** | 极低（单一连接池集约复用） | 较高（连接池碎片化） | 适中（按存储灵元集约划分） |
| **一致性保障** | **本地单机 ACID 强事务** | **EventBus 进程内最终一致性** | **同数据源链路内强事务** |
| **跨模块原子回滚** | **原生支持**（单连接穿透） | **不支持**（需业务补偿） | **同源支持**（跨异构源需 Saga） |
| **业务开发心智** | 极低（与普通 Spring Boot 无异） | 中（需处理异步事件与补偿） | 极低（面向受管契约编程） |
| **灵核纯洁度** | 中（包含 JDBC 驱动与连接池） | 极高（灵核零存储依赖） | 极高（灵核零存储依赖） |

---

## 三、 实战编码与配置指南

### 3.1 模式 1 实战：开箱即用的单机强事务（推荐）

#### 步骤 1：灵核端配置数据源与事务管理器
灵核（LingCore）就像普通 Spring Boot 工程一样引入数据库连接池（以 HikariCP 为例）并配置 `application.yml`：

```yaml
# 灵核 application.yml
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://127.0.0.1:3306/lingframe_demo?useUnicode=true&characterEncoding=utf8
    username: root
    password: ******
    type: com.zaxxer.hikari.HikariDataSource
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5

lingframe:
  enabled: true
  tx:
    propagation:
      enabled: true # 开启事务穿透（默认即为 true）
```

灵核主启动类开启声明式事务支持：
```java
@SpringBootApplication
@EnableTransactionManagement
public class LingCoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(LingCoreApplication.class, args);
    }
}
```

#### 步骤 2：业务灵元开发（真正的 0 配置、0 样板代码！）
在业务灵元（例如 `order-ling`、`account-ling`）中：
- **配置文件**：**完全不需要配置 `spring.datasource.*`**！
- **Java 代码**：**完全不需要编写任何 `DataSource` 或 `TransactionManager` 的配置类！**

灵珑运行时的 `LingDataSourceRegistrar` 在灵元容器启动时，会自动完成两项装配：
1. 从底座受管总线自动提取默认数据源，以 `@Primary` 注入为灵元容器内的 `dataSource` Bean；
2. 自动在灵元容器内装配双路径受管事务管理器 `transactionManager`（`LingManagedTransactionManager`）。

MyBatis-Plus 或 Spring JDBC 会自动感应并绑定该 `dataSource`。开发者只需像往常一样编写 Mapper 和 Service：

```java
// 灵元内部直接编写常规 Mapper，无需任何数据源配置
@Mapper
public interface OrderMapper extends BaseMapper<Order> {
}
```

```java
// 灵元内部直接编写业务 Service，无缝支持 @Transactional
@Service
public class OrderLingServiceImpl implements OrderLingService {

    @Autowired
    private OrderMapper orderMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createOrder(String orderId, BigDecimal amount) {
        Order order = new Order(orderId, amount);
        orderMapper.insert(order); // 自动使用穿透的灵核事务连接，与灵核同进同退
    }
}
```

> **可选进阶（模式 3 多源指定）**：只有当灵元需要绑定模式 3 动态外挂的特定数据源时，才需在灵元 `application.yml` 中显式指定引用的源 ID：
> ```yaml
> lingframe:
>   ling:
>     datasource-ref: analytics-mysql # 默认缺省为 "default"，模式 1 下无需配置此项
> ```

#### 步骤 3：跨灵元事务回滚实战
灵核暴露业务接口，发起根事务并跨灵元调用：

```java
@Service
public class TradeCoreServiceImpl implements TradeService {

    @Autowired
    private OrderLingService orderLingService; // 跨 ClassLoader 注入的灵元服务

    @Autowired
    private AccountLingService accountLingService; // 跨 ClassLoader 注入的灵元服务

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitTrade(TradeRequest request) {
        // 1. 调用订单灵元落库订单（处于同一物理连接）
        orderLingService.createOrder(request.getOrderId(), request.getAmount());

        // 2. 调用账户灵元扣减余额（处于同一物理连接）
        accountLingService.deductBalance(request.getUserId(), request.getAmount());

        // 3. 模拟业务异常触发回滚
        if (request.isMockError()) {
            throw new BusinessException("Core transaction failed, rolling back all lings!");
        }
    }
}
```
**执行现象**：当抛出 `BusinessException` 或任意灵元内抛出异常时，订单灵元写入的数据、账户灵元扣除的操作将**全部被原子回滚，无任何脏数据落盘**！

---

### 3.2 模式 2 实战：领域自治与进程内最终一致性

#### 步骤 1：灵元自建独立物理库配置
当某个灵元有强烈的物理隔离要求时，在灵元私有配置中声明独立数据源：

```yaml
# settlement-ling 灵元独立的 application.yml
spring:
  datasource:
    url: jdbc:postgresql://127.0.0.1:5432/settlement_db
    username: settle_user
    password: ******
```

#### 步骤 2：基于 EventBus 的进程内最终一致性编排
由于使用的是独立物理库，跨灵元不能走穿透强事务，应当使用灵珑的高性能进程内 `EventBus` 进行事件驱动：

```java
// 1. 订单灵元在自身本地事务成功后发布领域事件
@Service
public class OrderServiceImpl implements OrderService {
    @Autowired
    private EventBus eventBus;

    @Transactional
    public void createOrderSuccess(Order order) {
        orderMapper.insert(order);
        // 发布进程内事件，毫秒级通知结算灵元
        eventBus.publish(new OrderCreatedEvent(order.getId(), order.getAmount()));
    }
}

// 2. 结算灵元监听事件并在独立事务中消费
@Component
public class SettlementEventListener {
    @Autowired
    private SettlementService settlementService;

    @LingEventListener
    public void onOrderCreated(OrderCreatedEvent event) {
        // 独立物理库事务入库，若失败可写入补偿表或重试
        settlementService.processSettlement(event.getOrderId(), event.getAmount());
    }
}
```

---

### 3.3 模式 3 实战：基础设施灵元化动态挂载

#### 步骤 1：存储灵元声明供给身份
专职存储灵元装配连接池，并通过配置向总线注册 `dataSourceId`：
```yaml
# infra-storage-mysql 灵元配置
lingframe:
  ling:
    datasource-id: analytics-mysql # 声明受管数据源对外标识
```

#### 步骤 2：业务灵元按需绑定受管源
上层业务灵元指定引用该数据源：
```yaml
# report-ling 业务灵元配置
lingframe:
  ling:
    datasource-ref: analytics-mysql
```
此时 `ManagedDataSourceRegistry.lookup("analytics-mysql")` 即可获取到动态存储灵元提供的受管连接池，同样享受该数据源链路内的强事务穿透。

---

## 四、 事务传播机制与运行期行为

在跨灵元调用链中，Spring 的传播行为（Propagation）映射如下：

| 传播属性 | 灵珑微内核实际行为 | 架构考量与物理边界 |
| :--- | :--- | :--- |
| **REQUIRED (默认)** | **加入当前穿透连接** | 根事务已有时直接复用连接；无事务时开启新物理连接 |
| **SUPPORTS** | **有事务则加入，无事务则独立** | 完全兼容标准语义 |
| **REQUIRES_NEW** | **自动降级为 REQUIRED，输出 WARN** | 单一物理连接无法在同一线程内挂起并另起连接（除非借新连接破坏原子回滚），故安全降级为加入当前根事务 |
| **NESTED** | **自动降级为 REQUIRED，输出 WARN** | 避免 Savepoint 复杂度失控与驱动兼容性陷阱，统一纳管为单层原子提交 |
| **NOT_SUPPORTED** | **防御性拦截并抛异常** | 灵元内若要求“无事务”，若静默加入会导致本不该提交的逻辑被提早提交，因此安全阻断 |
| **NEVER** | **防御性拦截并抛异常** | 上游若存在活跃事务，直接抛出 `IllegalTransactionStateException` 拒绝执行 |
| **MANDATORY** | **严格校验** | 上游无事务时抛出异常，有事务时正常复用 |

---

## 五、 物理边界与避坑指南（关键红线）

### 5.1 JPA / Hibernate 为什么不支持跨灵元事务穿透？

这是由持久层底层架构与微内核 ClassLoader 隔离模型共同决定的：

1. **JPA 的「一级缓存与延迟 Flush 时序冲突」（幽灵数据覆盖）**：
   - JPA/Hibernate 核心是面向对象持久化。当灵核执行 `user.setName("Alice")` 时，底层物理数据库**尚未执行任何 SQL**，修改只停留在 JPA 内存的 `Persistence Context`（一级缓存）中；
   - 若此时物理连接穿透给灵元，灵元用 MyBatis 或原生 SQL 去查库，查到的**依然是修改前的老数据**；
   - 更致命的是：灵元执行写操作返回后，灵核事务 commit 触发 JPA 内部 Dirty Checking 强制 flush，**极易覆盖灵元的修改或触发并发乐观锁报错**。
2. **ClassLoader 卸载与内存泄漏（Metaspace 杀手）**：
   - Hibernate 会在全局单例中重度缓存实体类的 Class 引用、动态代理与字节码增强器；
   - 灵元若使用 JPA，在热卸载时其类加载器会被 Hibernate 强引用死死咬住，**导致 Metaspace 永久内存泄漏**。
3. **框架安全策略**：
   - 当检测到灵核根事务管理器为 `JpaTransactionManager` 时，框架在启动期输出 `WARN` 告警，并将事务穿透**安全降级**；下游灵元将借出独立连接执行（模式 2 行为），不产生穿透。
   - **推荐方案**：JPA 项目推荐践行 **模式 2（领域自治 + EventBus 进程内最终一致性）**。

### 5.2 灵元侧引入 Spring Data JPA 的实测硬边界（实证结论）

针对部分开发者在灵元内部执意引入 `spring-boot-starter-data-jpa` 的场景，LingFrame 官方通过真实容器测试 `ManagedJpaBoundaryTest`（5 用例全绿）实测锁定了以下三条关键运行期行为：

1. **方言自动检测失败（必须显式配置方言）**：
   - **现象**：若未配置方言，启动直接抛出 `Access to DialectResolutionInfo cannot be null when 'hibernate.dialect' not set` 导致 `EntityManagerFactory` 启动崩溃；
   - **根因**：灵珑受管代理连接在 `LingDatabaseMetaDataProxy` 中对数据库 URL 进行了安全治理脱敏（替换为 `jdbc:lingframe:masked`），导致 Hibernate 无法基于 URL 协议头推测底层数据库类型；
   - **解决**：灵元必须显式在 `application.yml` 中声明方言，例如：`spring.jpa.database-platform: org.hibernate.dialect.MySQLDialect`。
2. **双事务管理器自动互斥抑制（无注入歧义）**：
   - Spring Boot 的 `JpaBaseConfiguration` 对 `transactionManager` 声明了 `@ConditionalOnMissingBean`；
   - 灵珑运行时注册了受管事务管理器 `lingTransactionManager`（`LingManagedTransactionManager`）后，JPA 自动装配的 `JpaTransactionManager` 会被自动抑制；
   - 容器中仅保留唯一的 `PlatformTransactionManager`，`TransactionTemplate` 与 `@Transactional` 正常解析，绝无歧义报错。
3. **穿透命中时 Hibernate 提交权安全降级**：
   - 穿透栈非空时，受管代理返回不可物理关闭的 `NonCloseableLingConnectionProxy`；
   - Hibernate 发起的 `setAutoCommit(false)`、`commit()`、`close()` 全部被安全拦截为 no-op，`rollback()` 仅置回滚信号上行；Hibernate 的物理提交权被彻底降级，由灵核根事务统一协调，有效防止连接被 Hibernate 提前物理关闭。

### 5.3 野生数据源红线（禁止自行 new 连接池）
- **红线**：严禁在业务代码中直接通过 `DriverManager.getConnection()` 或 `new HikariDataSource()` 创建不经 Spring 管理的野生连接池。
- **后果**：野生数据源完全脱离灵珑微内核治理网络，无权限检查、无越权审计、无法享受事务穿透，且热卸载时极易发生物理句柄泄漏挂死 JVM。

### 5.4 根连接属性防篡改约束
- 下游灵元拿到的连接是 `NonCloseableLingConnectionProxy` 治理代理。
- 灵元内如果调用 `setTransactionIsolation()`、`setReadOnly()`、`setHoldability()`，框架**物理上不予执行（降级为 no-op）**，以保护灵核根连接的环境纯洁；但同时**保留完整的安全审计日志**（`transaction:*-suppressed`），兼顾稳定性与治理可观测性。

### 5.5 超时与毒化连接逃生舱（Poisoned Close）
- 若跨灵元调用配置了超时时间（Resilience Timeout），当主线程因超时强行放弃等待时：
  - 主线程将物理回滚事务；
  - 针对仍在后台异步执行不可中断 SQL 的工作线程，微内核会尝试在有界宽限期内 join；若超时未退出，微内核将该物理连接打标为 **Poisoned（毒化）并强制废弃关闭**，防止脏连接归还池后被其他业务借出产生数据串扰。

---

## 六、 总结与最佳选型建议

1. **核心业务、表在同一个库**：首选 **模式 1（基础设施托管）**。灵核配置连接池，灵元 0 配置使用 MyBatis-Plus，享受单机 ACID 强事务与无缝跨灵元原子回滚；
2. **异构库、外部调用、多租户物理分库**：采用 **模式 2（领域完全自治）**。灵元自建连接池，通过微内核 `EventBus` 实现进程内最终一致性；
3. **无存储轻量底座、多云动态接入**：采用 **模式 3（基础设施灵元化）**。存储作为灵元热挂载，基础设施遵循“只增不减”原则稳定运行。
