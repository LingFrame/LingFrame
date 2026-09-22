# LingFrame Multi-Paradigm DataSource & Cross-Ling Transaction Consistency Guide

> **Applicable Version**: LingFrame 0.4.0+  
> **Target Audience**: Application Architects and Software Engineers. Explains the three DataSource paradigms, the microkernel transaction propagation mechanics, and physical constraints.

---

## 1. Why Multi-Paradigm DataSource & Microkernel Propagation?

In a monolithic Spring Boot application, all services reside in the same ClassLoader and Spring ApplicationContext. A single `@Transactional` annotation guarantees atomic commit and rollback across components.

However, when evolving towards **Dynamic Modular Monoliths and Microkernel Architecture**:
1. **ClassLoader Isolation**: Each Ling (business module) operates under an isolated ClassLoader and its own child Spring context. Conventional Spring Bean wiring is physically separated;
2. **Connection Pool Fragmentation**: If each Ling instantiates its own connection pool, database connection count escalates exponentially, and Spring's `@Transactional` boundary across LingCore and Lings becomes **physically broken**;
3. **Consistency Fracture**: When LingCore initiates a transaction and invokes Ling A, if Ling A fails or LingCore aborts, Ling A has already committed independently, leading to catastrophic partial commits and dirty data.

To strike the ideal balance between **"Modular Decoupling & Hot Pluggability"** and **"Enterprise ACID Strong Consistency"**, LingFrame implements a **Dual-Track Transaction Governance System: "Recommend 1, Retain 2, Extend 3"**.

---

## 2. The Three DataSource & Transaction Architecture Paradigms

```
+---------------------------------------------------------------------------------------------------+
|                        LingFrame Dual-Track Transaction Topology                                  |
+---------------------------------------------------------------------------------------------------+
|                                                                                                   |
|  [Strong Consistency Track: Paradigm 1 (Managed)]         [Eventual Consistency Track: Paradigm 2] |
|                                                                                                   |
|      LingCore [Static Connection Pool (HikariCP/Druid)]          LingCore                         |
|         │                                                          │                              |
|         │  Microkernel Pipeline Transparent Connection Penetration │  In-Process EventBus         |
|         ▼  (NonCloseableLingConnectionProxy)                       ▼  (OrderCreatedEvent)         |
|      Business Lings (OrderLing / AccountLing)                    Business Lings [Isolated Pools]   |
|         (Zero JDBC config, In-Process Local ACID Rollback)         (Independent DB, Saga Pattern) |
|                                                                                                   |
|---------------------------------------------------------------------------------------------------|
|                                                                                                   |
|  [Extended Strong Consistency: Paradigm 3 (Storage-Ling)]                                         |
|      LingCore (0 Storage, Pure Compute) ◄── Hot Mount ─── Storage-Ling (Dedicated Pool, Append-Only)
|                                                                 │                                 |
|                                                                 ▼ Managed Registry (dataSourceId) |
|                                                           Upper Lings (Shared Pool, Local ACID)   |
+---------------------------------------------------------------------------------------------------+
```

### 2.1 Paradigm 1: Managed DataSource Pattern (Recommended, Out-of-the-Box)
- **Topology**: LingCore declares and manages a single static physical connection pool (HikariCP, Druid, etc.) and transaction manager. Business Lings require zero JDBC configuration and automatically access the managed connection via the microkernel bus.
- **Transaction Philosophy**: **In-Process Local ACID Strong Consistency**. When invoking a Ling across ClassLoaders, the microkernel pipeline transparently propagates the active physical `Connection` bound to the caller thread into the downstream Ling.
- **Consistency Goal**: **Strong Consistency** (atomic commit/rollback across LingCore and all participating Lings).
- **Use Cases**: Core enterprise applications (ERP, CRM, eCommerce, monolithic refactoring) demanding zero migration friction and ACID rollbacks.

### 2.2 Paradigm 2: Database-per-Ling Pattern (Domain Autonomy)
- **Topology**: Each Ling maintains complete storage autonomy, declaring its own `spring.datasource.url` and owning a physically dedicated connection pool connected to an isolated database (e.g., dedicated MySQL, MongoDB, PostgreSQL).
- **Transaction Philosophy**: **Eventual Consistency**. Because physical database instances and connections are disjoint, single-connection ACID rollback is physically impossible. Consistency is maintained via LingFrame's ultra-low-latency in-process `EventBus` combined with Saga state machines and idempotent retries.
- **Consistency Goal**: **Eventual Consistency**.
- **Use Cases**: Multi-tenant physical database sharding, heterogeneous databases, irreversible external I/O (payment gateways, SMS dispatch).

### 2.3 Paradigm 3: Storage-Ling Pattern (Infrastructure as a Ling)
- **Topology**: LingCore maintains strict "Zero Storage, Zero JDBC Dependency" purity. Connection pools and JDBC drivers are packaged into dedicated "Storage Infrastructure Lings", dynamically mounted at runtime, and published to the microkernel bus under distinct `dataSourceId`s.
- **Transaction Philosophy**: **Strong Consistency within the same `dataSourceId` pipeline**.
- **Lifecycle Invariant**: **Append-Only**. Once mounted, a Storage-Ling cannot be hot-unloaded at runtime to prevent cascading destruction of active database connection pools.
- **Use Cases**: Cloud-native edge compute gateways, multi-cloud dynamic storage switching, pure compute nodes.

### 2.4 Comprehensive Decision Matrix

| Dimension | Paradigm 1: Managed (Default) | Paradigm 2: Domain Autonomy | Paradigm 3: Storage-Ling |
| :--- | :--- | :--- | :--- |
| **Pool Ownership** | Centrally managed by LingCore | Independently owned by each Ling | Owned by dedicated Storage-Lings |
| **Pool Lifecycle** | Static (fixed at boot, immutable) | Static (tied to Ling lifecycle) | Dynamic hot-mounting (**Append-Only**) |
| **Heterogeneous Sources**| Single homogeneous pool | Completely heterogeneous per Ling | Multi-source via multiple Storage-Lings |
| **Connection Overhead** | Minimal (centralized multiplexing) | High (fragmented connection pools) | Moderate (partitioned per Storage-Ling) |
| **Consistency Model** | **In-Process Local ACID** | **EventBus In-Process Eventual** | **Local ACID per dataSourceId** |
| **Cross-Ling Rollback** | **Native Support** (single connection)| **No** (requires business compensation) | **Supported within same source** |
| **Developer Overhead** | Minimal (standard Spring Boot idioms)| Moderate (requires async Saga logic) | Minimal (managed contracts) |
| **LingCore Purity** | Moderate (contains JDBC driver/pool) | Pure (Zero storage dependencies) | Pure (Zero storage dependencies) |

---

## 3. Practical Configuration & Implementation Guide

### 3.1 Paradigm 1: Managed DataSource & ACID Transaction (Recommended)

#### Step 1: LingCore Configuration
Configure the connection pool in LingCore's `application.yml` just like any standard Spring Boot application:

```yaml
# LingCore application.yml
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
      enabled: true # Enable transaction propagation (default: true)
```

Enable declarative transaction management in LingCore:
```java
@SpringBootApplication
@EnableTransactionManagement
public class LingCoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(LingCoreApplication.class, args);
    }
}
```

#### Step 2: Business Ling Development (True Zero-Config & Zero-Boilerplate!)
In business Lings (e.g., `order-ling`, `account-ling`):
- **Configuration**: **No `spring.datasource.*` needed at all!**
- **Java Code**: **No `DataSource` or `TransactionManager` `@Configuration` classes required!**

During Ling container startup, LingFrame runtime's `LingDataSourceRegistrar` automatically performs two essential tasks:
1. Injects the managed DataSource from the microkernel bus as a `@Primary` `dataSource` Bean into the Ling's Spring context;
2. Registers the dual-path managed transaction manager `transactionManager` (`LingManagedTransactionManager`).

MyBatis-Plus or Spring JDBC automatically detects and binds to this `dataSource`. Developers simply write Mappers and Services as usual:

```java
// Directly write standard Mappers in Ling without any DataSource wiring
@Mapper
public interface OrderMapper extends BaseMapper<Order> {
}
```

```java
// Directly write standard business Services, seamlessly supporting @Transactional
@Service
public class OrderLingServiceImpl implements OrderLingService {

    @Autowired
    private OrderMapper orderMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createOrder(String orderId, BigDecimal amount) {
        Order order = new Order(orderId, amount);
        orderMapper.insert(order); // Reuses the propagated transaction connection atomically
    }
}
```

> **Optional Advanced (Specifying Multi-Source in Paradigm 3)**: Only when a Ling needs to bind to a specific dynamically mounted Storage-Ling does it declare the target ID in `application.yml`:
> ```yaml
> lingframe:
>   ling:
>     datasource-ref: analytics-mysql # Defaults to "default", omitted in Paradigm 1
> ```

#### Step 3: Atomic Cross-Ling Transaction in Action
LingCore initiates a root transaction and invokes downstream Lings:

```java
@Service
public class TradeCoreServiceImpl implements TradeService {

    @Autowired
    private OrderLingService orderLingService; // Ling service injected across ClassLoader

    @Autowired
    private AccountLingService accountLingService; // Ling service injected across ClassLoader

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitTrade(TradeRequest request) {
        // 1. Insert order in OrderLing (reuses the same physical connection)
        orderLingService.createOrder(request.getOrderId(), request.getAmount());

        // 2. Deduct balance in AccountLing (reuses the same physical connection)
        accountLingService.deductBalance(request.getUserId(), request.getAmount());

        // 3. Simulate an unexpected error
        if (request.isMockError()) {
            throw new BusinessException("Core transaction failed, rolling back all Lings!");
        }
    }
}
```
**Outcome**: If `BusinessException` is thrown, all changes made inside `OrderLing` and `AccountLing` **roll back atomically without any dirty data written to the database**.

---

### 3.2 Paradigm 2: Domain Autonomy & In-Process Eventual Consistency

#### Step 1: Autonomous Ling DataSource Configuration
```yaml
# settlement-ling application.yml
spring:
  datasource:
    url: jdbc:postgresql://127.0.0.1:5432/settlement_db
    username: settle_user
    password: ******
```

#### Step 2: Event-Driven Orchestration via EventBus
```java
// 1. OrderLing publishes a domain event upon successful local commit
@Service
public class OrderServiceImpl implements OrderService {
    @Autowired
    private EventBus eventBus;

    @Transactional
    public void createOrderSuccess(Order order) {
        orderMapper.insert(order);
        // Publish in-process domain event
        eventBus.publish(new OrderCreatedEvent(order.getId(), order.getAmount()));
    }
}

// 2. SettlementLing listens and processes in its own physical transaction
@Component
public class SettlementEventListener {
    @Autowired
    private SettlementService settlementService;

    @LingEventListener
    public void onOrderCreated(OrderCreatedEvent event) {
        // Process in autonomous database transaction
        settlementService.processSettlement(event.getOrderId(), event.getAmount());
    }
}
```

---

### 3.3 Paradigm 3: Storage Infrastructure as a Ling

#### Step 1: Storage-Ling Publishes Identity
```yaml
# infra-storage-mysql Ling configuration
lingframe:
  ling:
    datasource-id: analytics-mysql
```

#### Step 2: Business Ling Binds to Managed Source
```yaml
# report-ling configuration
lingframe:
  ling:
    datasource-ref: analytics-mysql
```

---

## 4. Transaction Propagation Semantics

| Propagation Attribute | LingFrame Microkernel Behavior | Design Rationale & Boundary |
| :--- | :--- | :--- |
| **REQUIRED (Default)** | **Join existing propagated connection** | Reuses active connection if present; acquires new physical connection if root |
| **SUPPORTS** | **Join if transaction exists, standalone otherwise**| Fully compliant with standard semantics |
| **REQUIRES_NEW** | **Safely downgraded to REQUIRED with WARN** | A single physical connection cannot be suspended on the same thread without breaking atomic rollback. Safely joins current transaction |
| **NESTED** | **Safely downgraded to REQUIRED with WARN** | Eliminates driver-specific Savepoint bugs; managed as single-layer atomic transaction |
| **NOT_SUPPORTED** | **Defensively rejected with Exception** | Silently joining would violate caller's intent to commit immediately |
| **NEVER** | **Defensively rejected with Exception** | Throws `IllegalTransactionStateException` if an active transaction is detected |
| **MANDATORY** | **Strictly validated** | Throws exception if no transaction is active; joins if active |

---

## 5. Physical Boundaries & Hard Constraints

### 5.1 Why JPA / Hibernate Does NOT Support Cross-Ling Propagation
1. **L1 Cache & Deferred Flush Conflict (Phantom Overwrites)**:
   - JPA/Hibernate uses an in-memory `Persistence Context`. Modifying an entity does not immediately execute SQL statements.
   - If the underlying connection is propagated to a downstream Ling executing native SQL/MyBatis, the Ling reads **stale data** because JPA has not flushed yet.
   - Worse, when the Ling finishes and LingCore commits, JPA's dirty checking forces a deferred flush, **overwriting the Ling's changes or triggering optimistic lock failures**.
2. **ClassLoader Leakage (Metaspace Exhaustion)**:
   - Hibernate maintains heavy static class references, dynamic proxies, and byte-buddy caches. If Lings use JPA entities, unloading a Ling leaks its `ClassLoader` permanently.
3. **Safe Degradation**:
   - When LingCore uses `JpaTransactionManager`, LingFrame outputs a startup `WARN` and safely degrades transaction propagation: downstream Lings acquire independent physical connections (Paradigm 2 behavior).
   - **Recommendation**: For JPA projects, adopt **Paradigm 2 (Domain Autonomy + In-Process EventBus)**.

### 5.2 Spring Data JPA Boundaries inside a Ling (Verified Conclusions)

For scenarios where Lings introduce `spring-boot-starter-data-jpa`, the real container test `ManagedJpaBoundaryTest` (5/5 passed) verified three critical runtime behaviors:

1. **Dialect Auto-Detection Failure (Explicit Dialect Required)**:
   - **Symptom**: Without configuration, startup crashes with `Access to DialectResolutionInfo cannot be null when 'hibernate.dialect' not set`;
   - **Root Cause**: `LingDatabaseMetaDataProxy` masks the connection URL (`jdbc:lingframe:masked`) for security isolation, disabling Hibernate's URL-based dialect resolver;
   - **Solution**: Lings must explicitly configure `spring.jpa.database-platform` in `application.yml` (e.g., `org.hibernate.dialect.MySQLDialect`).
2. **Dual Transaction Manager Mutual Exclusion (No Ambiguity)**:
   - Spring Boot's `JpaBaseConfiguration` marks `transactionManager` with `@ConditionalOnMissingBean`;
   - Once LingFrame's `lingTransactionManager` (`LingManagedTransactionManager`) is registered, JPA's `JpaTransactionManager` is suppressed automatically;
   - Only a single `PlatformTransactionManager` resides in the container, resolving `TransactionTemplate` and `@Transactional` without conflict.
3. **Hibernate Physical Commit Rights Safely Degraded**:
   - When transaction propagation is active, the managed DataSource returns a `NonCloseableLingConnectionProxy`;
   - Hibernate's calls to `setAutoCommit(false)`, `commit()`, and `close()` are intercepted as safe no-ops, while `rollback()` only marks the rollback signal upward. Physical commit rights remain safely coordinated by LingCore.

### 5.3 Wildcat DataSource Prohibition (No Manual `new DataSource()`)
- **Prohibition**: Never instantiate unmanaged connection pools via `new HikariDataSource()` or raw `DriverManager.getConnection()`.
- **Consequence**: Bypasses all security audits, disables transaction propagation, and leaks OS sockets upon Ling unloading.

### 5.4 Root Connection Tamper Guard
- Downstream Lings operate on a `NonCloseableLingConnectionProxy`.
- Calls to `setTransactionIsolation()`, `setReadOnly()`, and `setHoldability()` are **suppressed (no-op)** to protect LingCore's root connection state, while **full audit logs (`transaction:*-suppressed`)** are recorded.

### 5.5 Timeout & Poisoned Connection Escape Hatch
- When a cross-Ling call exceeds the resilience timeout, LingCore rolls back the transaction.
- If a worker thread is stuck in an uninterruptible SQL execution, LingFrame waits for a bounded grace period. If it fails to exit, the connection is marked **Poisoned and physically destroyed** to prevent returning corrupted connection state to the pool.

---

## 6. Summary & Architecture Recommendations

1. **Monolithic DB & Core Business**: Choose **Paradigm 1 (Managed DataSource)**. LingCore manages the pool; Lings use MyBatis-Plus with zero JDBC config, enjoying seamless local ACID rollbacks.
2. **Heterogeneous DBs & Multi-Tenant Sharding**: Choose **Paradigm 2 (Domain Autonomy)**. Lings own their pools and coordinate via LingFrame's in-process `EventBus`.
3. **Pure Compute Nodes & Cloud-Native Switching**: Choose **Paradigm 3 (Storage-Ling)**. Dynamic hot-mounting under the "Append-Only" invariant.
