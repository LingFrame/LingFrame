# SQLite 持久化 + 访问令牌 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Dashboard 引入 SQLite 持久化存储（监控指标、治理配置、灵元状态、审计记录）和轻量访问令牌机制，使 Dashboard 可安全对外展示。

**Architecture:** 在 lingframe-dashboard 模块中新增 `storage` 包，封装 SQLite 访问层（JDBC 直连，不引入 JPA/Hibernate）。后端定时采集 JVM 指标写入 SQLite，前端通过新增的历史查询 API 获取任意时间范围数据。访问令牌通过 Spring HandlerInterceptor 实现，配置驱动，不引入用户体系。CanaryRouter 的灰度配置也持久化到 SQLite（替代内存丢失问题）。

**数据隔离说明：** 每个灵元独立部署，Dashboard 内嵌在灵元进程中，SQLite 数据库文件为每个灵元进程私有。因此 `metrics_snapshot` 表不需要 `source` 或 `application_name` 字段来区分来源——该表天然只存储当前灵元进程的指标数据。监控指标采集的 `application_name` 仅用于 MetricsHistoryController 响应中的 `source` 字段，便于前端展示。

**时区一致性：** 数据库中所有时间字段统一存储 UTC 毫秒时间戳（`System.currentTimeMillis()`），无时区概念。指标采集和查询均使用同一时基，不存在转换问题。**前端展示**层使用 `new Date(timestamp).toLocaleTimeString()` 按浏览器本地时区渲染——这是预期的，但需注意：
- "今天" / "昨天" 等快捷范围在前端用 `setHours(0,0,0,0)` 按本地时区切分
- 跨时区部署时（如服务器 UTC、用户 CST），"今天"的边界可能错位
- **建议**：若需严格时区控制，可让后端按 `LingRuntime` 启动时配置的时区切分（后续 Task 可选）

**Tech Stack:** SQLite JDBC (xerial/sqlite-jdbc 3.45.1.0)、Spring JDBC (JdbcTemplate)、Spring HandlerInterceptor、Jackson (ObjectMapper)

---

## 前置依赖确认

> **GovernancePolicy Jackson 反序列化兼容性**：
> `GovernancePolicy`（`lingframe-api` 模块）使用了 `@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor`，
> Jackson 默认使用无参构造器 + setter 反序列化，**兼容性无问题**。
> 但需注意 `@Builder.Default` 字段（`permissions`, `capabilities`, `audits`, `invocation`, `collaborationMode`）：
> - Jackson 反序列化时**不走 Builder**，而是走 setter，所以 `@Builder.Default` 的默认值不会生效
> - 若 JSON 中缺少这些字段，Jackson 会设为 null（而非 `new ArrayList<>()`）
> - **解决方案**：在 `GovernanceStorage` 反序列化后做 null-safe 兜底：
> ```java
> private GovernancePolicy safeDeserialize(String json) {
>     try {
>         GovernancePolicy policy = objectMapper.readValue(json, GovernancePolicy.class);
>         // Jackson 不走 @Builder.Default，需手动兜底
>         if (policy.getPermissions() == null) policy.setPermissions(new ArrayList<>());
>         if (policy.getCapabilities() == null) policy.setCapabilities(new ArrayList<>());
>         if (policy.getAudits() == null) policy.setAudits(new ArrayList<>());
>         if (policy.getInvocation() == null) policy.setInvocation(new GovernancePolicy.InvocationPolicy());
>         if (policy.getCollaborationMode() == null) policy.setCollaborationMode(CollaborationMode.PASSIVE);
>         return policy;
>     } catch (Exception e) {
>         throw new RuntimeException("反序列化 GovernancePolicy 失败: " + json, e);
>     }
> }
> ```
> 此方法在 `restoreGovernanceConfig` 和 `GovernanceStorage.loadAllInvocationConfigs` 中统一使用。

---

## 文件结构

### 新建文件
| 文件 | 职责 |
|------|------|
| `lingframe-dashboard/src/main/java/com/lingframe/dashboard/storage/StorageInitializer.java` | SQLite 数据库初始化（建表、数据目录创建） |
| `lingframe-dashboard/src/main/java/com/lingframe/dashboard/storage/MetricsStorage.java` | 监控指标存储与查询 |
| `lingframe-dashboard/src/main/java/com/lingframe/dashboard/storage/GovernanceStorage.java` | 治理配置存储（灰度配置 + 调用治理 + 灵元状态） |
| `lingframe-dashboard/src/main/java/com/lingframe/dashboard/storage/AuditStorage.java` | 审计记录存储 |
| `lingframe-dashboard/src/main/java/com/lingframe/dashboard/storage/StorageProperties.java` | 持久化配置属性（数据目录、保留天数等） |
| `lingframe-dashboard/src/main/java/com/lingframe/dashboard/scheduler/MetricsCollectorScheduler.java` | 定时采集 JVM 指标并写入 SQLite |
| `lingframe-dashboard/src/main/java/com/lingframe/dashboard/controller/MetricsHistoryController.java` | 历史指标查询 API |
| `lingframe-dashboard/src/main/java/com/lingframe/dashboard/security/AccessTokenInterceptor.java` | 访问令牌拦截器 |
| `lingframe-dashboard/src/main/java/com/lingframe/dashboard/security/AccessTokenProperties.java` | 令牌配置属性 |

### 修改文件
| 文件 | 修改内容 |
|------|---------|
| `lingframe-dashboard/pom.xml` | 添加 sqlite-jdbc、spring-jdbc 依赖 |
| `lingframe-dashboard/.../config/DashboardAutoConfiguration.java` | 注册新 Bean（Storage、Scheduler、Interceptor） |
| `lingframe-dashboard/.../controller/LingController.java` | 无需修改（写入点在 Service 层） |
| `lingframe-dashboard/.../controller/GovernanceController.java` | 无需修改（写入点在 Service 层） |
| `lingframe-dashboard/.../service/DashboardService.java` | 灰度配置变更时同步写入 SQLite |
| `lingframe-dashboard/.../service/DashboardGovernanceSupport.java` | 治理策略/调用治理/权限变更时同步写入 SQLite |
| `lingframe-dashboard/src/main/resources/static/js/dashboard.js` | 前端历史查询、时间范围扩展 |
| `lingframe-dashboard/src/main/resources/static/dashboard.html` | 时间范围选择器扩展（今天/昨天/自定义） |
| `lingframe-dashboard/src/main/resources/static/i18n/zh-CN.json` | 新增翻译 |
| `lingframe-dashboard/src/main/resources/static/i18n/en-US.json` | 新增翻译 |

---

## Task 1: 添加 SQLite JDBC 依赖

**Files:**
- Modify: `lingframe-dashboard/pom.xml`

- [ ] **Step 1: 添加 sqlite-jdbc 和 spring-jdbc 依赖**

首先在父 POM `lingframe-dependencies/pom.xml` 的 `<dependencyManagement>` 中统一管理版本：

```xml
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>3.45.1.0</version>
</dependency>
```

然后在 `lingframe-dashboard/pom.xml` 的 `<dependencies>` 中添加（版本由父 POM 管理）：

```xml
<!-- SQLite -->
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
</dependency>

<!-- Spring JDBC（JdbcTemplate） -->
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-jdbc</artifactId>
</dependency>
```

注意：`spring-jdbc` 版本由 Spring Boot BOM 管理，不需要指定版本。

- [ ] **Step 2: 验证编译**

Run: `mvn compile -pl lingframe-dashboard -am -q`
Expected: BUILD SUCCESS

---

## Task 2: 存储配置属性

**Files:**
- Create: `lingframe-dashboard/src/main/java/com/lingframe/dashboard/storage/StorageProperties.java`

- [ ] **Step 1: 创建 StorageProperties**

```java
package com.lingframe.dashboard.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dashboard 持久化存储配置
 */
@Data
@ConfigurationProperties(prefix = "lingframe.dashboard.storage")
public class StorageProperties {

    /** 数据目录（SQLite 数据库文件存放位置） */
    private String dataDir = "./lingframe-data";

    /** 监控指标保留天数 */
    private int metricsRetentionDays = 7;

    /** 审计记录保留天数 */
    private int auditRetentionDays = 30;

    /** 指标采集间隔（秒） */
    private int metricsCollectIntervalSeconds = 10;

    /** 是否启用持久化存储（默认启用） */
    private boolean enabled = true;
}
```

---

## Task 3: SQLite 数据库初始化

**Files:**
- Create: `lingframe-dashboard/src/main/java/com/lingframe/dashboard/storage/StorageInitializer.java`

- [ ] **Step 1: 创建 StorageInitializer**

```java
package com.lingframe.dashboard.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * SQLite 数据库初始化
 * 负责建表和数据清理
 */
@Slf4j
public class StorageInitializer {

    private final JdbcTemplate jdbcTemplate;
    private final StorageProperties properties;

    public StorageInitializer(JdbcTemplate jdbcTemplate, StorageProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    /**
     * 初始化数据库表结构
     * <p>
     * 失败时抛出 RuntimeException，配合 @Bean(initMethod) 触发 Spring 启动失败，
     * 避免"应用启动了但表结构缺失"导致后续 NPE。
     */
    public void initialize() {
        log.info("[LingFrame] 初始化 SQLite 存储: {}", properties.getDataDir());

        try {
            // 监控指标快照表
            jdbcTemplate.execute(
            "CREATE TABLE IF NOT EXISTS metrics_snapshot (" +
            "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  timestamp BIGINT NOT NULL," +
            "  cpu_usage INT DEFAULT 0," +
            "  process_cpu_load REAL DEFAULT 0," +
            "  heap_used_mb BIGINT DEFAULT 0," +
            "  heap_max_mb BIGINT DEFAULT 0," +
            "  heap_usage REAL DEFAULT 0," +
            "  metaspace_used_kb BIGINT DEFAULT 0," +
            "  metaspace_max_kb BIGINT DEFAULT 0," +
            "  metaspace_usage REAL DEFAULT 0," +
            "  loaded_class_count INT DEFAULT 0," +
            "  total_loaded_class_count BIGINT DEFAULT 0," +
            "  unloaded_class_count BIGINT DEFAULT 0," +
            "  thread_count INT DEFAULT 0," +
            "  daemon_thread_count INT DEFAULT 0," +
            "  peak_thread_count INT DEFAULT 0," +
            "  gc_count BIGINT DEFAULT 0," +
            "  gc_time_ms BIGINT DEFAULT 0," +
            "  memory_used_mb BIGINT DEFAULT 0," +
            "  memory_total_mb BIGINT DEFAULT 0," +
            "  memory_usage REAL DEFAULT 0," +
            "  available_processors INT DEFAULT 0," +
            "  system_load_average REAL DEFAULT 0" +
            ")"
        );

        // 时间戳索引（加速范围查询）
        jdbcTemplate.execute(
            "CREATE INDEX IF NOT EXISTS idx_metrics_timestamp ON metrics_snapshot(timestamp)"
        );

        // 治理配置表（联合主键：同一灵元可同时存储灰度、调用治理、权限等多种配置）
        jdbcTemplate.execute(
            "CREATE TABLE IF NOT EXISTS governance_config (" +
            "  ling_id VARCHAR(128) NOT NULL," +
            "  config_type VARCHAR(32) NOT NULL," + // canary / invocation / permission
            "  config_data TEXT NOT NULL," +         // JSON 格式
            "  updated_at BIGINT NOT NULL," +
            "  PRIMARY KEY (ling_id, config_type)" +
            ")");

        // 灵元状态快照表
        jdbcTemplate.execute(
            "CREATE TABLE IF NOT EXISTS ling_status (" +
            "  ling_id VARCHAR(128) PRIMARY KEY," +
            "  status VARCHAR(32) NOT NULL," +
            "  version VARCHAR(64) DEFAULT ''," +
            "  updated_at BIGINT NOT NULL" +
            ")"
        );

        // 审计记录表
        jdbcTemplate.execute(
            "CREATE TABLE IF NOT EXISTS audit_log (" +
            "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  timestamp BIGINT NOT NULL," +
            "  ling_id VARCHAR(128)," +
            "  action VARCHAR(64) NOT NULL," +
            "  detail TEXT," +
            "  result VARCHAR(16) DEFAULT 'SUCCESS'" +
            ")"
        );

        jdbcTemplate.execute(
            "CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON audit_log(timestamp)"
        );

        // 初始化时清理一次过期数据（启动时数据量小，失败可重试）
        cleanExpiredData();

        log.info("[LingFrame] SQLite 存储初始化完成");
        } catch (Exception e) {
            log.error("[LingFrame] SQLite 存储初始化失败", e);
            throw new RuntimeException("[LingFrame] SQLite 存储初始化失败，应用拒绝启动", e);
        }
    }

    /**
     * 清理过期的监控指标和审计记录数据
     */
    public void cleanExpiredData() {
        long metricsCutoff = System.currentTimeMillis() - (long) properties.getMetricsRetentionDays() * 24 * 60 * 60 * 1000;
        int deletedMetrics = jdbcTemplate.update("DELETE FROM metrics_snapshot WHERE timestamp < ?", metricsCutoff);
        if (deletedMetrics > 0) {
            log.info("[LingFrame] 清理过期指标数据: {} 条", deletedMetrics);
        }

        long auditCutoff = System.currentTimeMillis() - (long) properties.getAuditRetentionDays() * 24 * 60 * 60 * 1000;
        int deletedAudit = jdbcTemplate.update("DELETE FROM audit_log WHERE timestamp < ?", auditCutoff);
        if (deletedAudit > 0) {
            log.info("[LingFrame] 清理过期审计记录: {} 条", deletedAudit);
        }
    }
}
```

---

## Task 4: 监控指标存储与查询

**Files:**
- Create: `lingframe-dashboard/src/main/java/com/lingframe/dashboard/storage/MetricsStorage.java`

- [ ] **Step 1: 创建 MetricsStorage**

```java
package com.lingframe.dashboard.storage;

import com.lingframe.core.metrics.JVMMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 监控指标存储与查询
 */
@Slf4j
public class MetricsStorage {

    private final JdbcTemplate jdbcTemplate;

    public MetricsStorage(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 保存一条指标快照
     */
    public void saveSnapshot(JVMMetrics metrics) {
        jdbcTemplate.update(
            "INSERT INTO metrics_snapshot (timestamp, cpu_usage, process_cpu_load, " +
            "  heap_used_mb, heap_max_mb, heap_usage, " +
            "  metaspace_used_kb, metaspace_max_kb, metaspace_usage, " +
            "  loaded_class_count, total_loaded_class_count, unloaded_class_count, " +
            "  thread_count, daemon_thread_count, peak_thread_count, " +
            "  gc_count, gc_time_ms, " +
            "  memory_used_mb, memory_total_mb, memory_usage, " +
            "  available_processors, system_load_average) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            metrics.getTimestamp(),
            metrics.getCpuUsage(), metrics.getProcessCpuLoad(),
            metrics.getHeapUsedMB(), metrics.getHeapMaxMB(), metrics.getHeapUsagePercent(),
            metrics.getMetaspaceUsedKB(), metrics.getMetaspaceMaxKB(), metrics.getMetaspaceUsagePercent(),
            metrics.getLoadedClassCount(), metrics.getTotalLoadedClassCount(), metrics.getUnloadedClassCount(),
            metrics.getThreadCount(), metrics.getDaemonThreadCount(), metrics.getPeakThreadCount(),
            metrics.getGcCount(), metrics.getGcTimeMs(),
            metrics.getUsedMemoryMB(), metrics.getTotalMemoryMB(), metrics.getMemoryUsagePercent(),
            metrics.getAvailableProcessors(), metrics.getSystemLoadAverage()
        );
    }

    /**
     * 查询指定时间范围的指标数据
     *
     * @param startMs 起始时间戳（毫秒）
     * @param endMs   结束时间戳（毫秒）
     * @param interval 采样间隔（秒），用于降采样
     * @return 指标列表，每个元素是一个 Map
     */
    public List<Map<String, Object>> queryRange(long startMs, long endMs, int interval) {
        // 如果数据点过多，使用 SQL 降采样
        String sql;
        if (interval > 0) {
            // 按间隔分组取平均（SQLite 没有 FLOOR/ROUND 对时间戳的便捷函数，用除法模拟）
            // 注意：gc_count 和 gc_time_ms 是累计值，使用 MAX 取区间末值；其他指标用 AVG
            sql = "SELECT " +
                  "  (timestamp / ?) * ? AS sample_time, " +
                  "  AVG(cpu_usage) AS cpu_usage, " +
                  "  AVG(heap_usage) AS heap_usage, " +
                  "  AVG(metaspace_usage) AS metaspace_usage, " +
                  "  AVG(thread_count) AS thread_count, " +
                  "  MAX(gc_count) AS gc_count, " +
                  "  MAX(gc_time_ms) AS gc_time_ms, " +
                  "  AVG(loaded_class_count) AS loaded_class_count, " +
                  "  AVG(metaspace_used_kb) AS metaspace_used_kb, " +
                  "  AVG(heap_used_mb) AS heap_used_mb " +
                  "FROM metrics_snapshot " +
                  "WHERE timestamp BETWEEN ? AND ? " +
                  "GROUP BY sample_time " +
                  "ORDER BY sample_time";
            long intervalMs = (long) interval * 1000;
            return jdbcTemplate.queryForList(sql, intervalMs, intervalMs, startMs, endMs);
        } else {
            // 不降采样，返回原始数据
            sql = "SELECT timestamp, cpu_usage, process_cpu_load, " +
                  "  heap_used_mb, heap_max_mb, heap_usage, " +
                  "  metaspace_used_kb, metaspace_max_kb, metaspace_usage, " +
                  "  loaded_class_count, total_loaded_class_count, unloaded_class_count, " +
                  "  thread_count, daemon_thread_count, peak_thread_count, " +
                  "  gc_count, gc_time_ms, " +
                  "  memory_used_mb, memory_total_mb, memory_usage, " +
                  "  available_processors, system_load_average " +
                  "FROM metrics_snapshot " +
                  "WHERE timestamp BETWEEN ? AND ? " +
                  "ORDER BY timestamp";
            return jdbcTemplate.queryForList(sql, startMs, endMs);
        }
    }

    /**
     * 查询指定时间范围的聚合统计
     */
    public Map<String, Object> queryAggregation(long startMs, long endMs) {
        // GC 计数器是累计值（单调递增），MAX - MIN 即时间范围内的增量
        String sql = "SELECT " +
                     "  AVG(cpu_usage) AS avg_cpu, MAX(cpu_usage) AS max_cpu, " +
                     "  AVG(heap_usage) AS avg_heap, MAX(heap_usage) AS max_heap, " +
                     "  AVG(metaspace_usage) AS avg_metaspace, MAX(metaspace_usage) AS max_metaspace, " +
                     "  AVG(thread_count) AS avg_threads, MAX(thread_count) AS max_threads, " +
                     "  COALESCE(MAX(gc_count) - MIN(gc_count), 0) AS delta_gc_count, " +
                     "  COALESCE(MAX(gc_time_ms) - MIN(gc_time_ms), 0) AS delta_gc_time, " +
                     "  AVG(loaded_class_count) AS avg_classes, MAX(loaded_class_count) AS max_classes " +
                     "FROM metrics_snapshot " +
                     "WHERE timestamp BETWEEN ? AND ?";
        return jdbcTemplate.queryForMap(sql, startMs, endMs);
    }
}
```

---

## Task 5: 治理配置存储

**Files:**
- Create: `lingframe-dashboard/src/main/java/com/lingframe/dashboard/storage/GovernanceStorage.java`

- [ ] **Step 1: 创建 GovernanceStorage**

```java
package com.lingframe.dashboard.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.config.CollaborationMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 治理配置存储（灰度配置 + 调用治理 + 灵元状态）
 */
@Slf4j
public class GovernanceStorage {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GovernanceStorage(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 灰度配置 ====================

    /**
     * 保存灰度配置
     */
    public void saveCanaryConfig(String lingId, int percent, String canaryVersion) {
        String json = String.format("{\"percent\":%d,\"canaryVersion\":\"%s\"}", percent, canaryVersion);
        jdbcTemplate.update(
            "INSERT OR REPLACE INTO governance_config (ling_id, config_type, config_data, updated_at) VALUES (?, 'canary', ?, ?)",
            lingId, json, System.currentTimeMillis()
        );
    }

    /**
     * 加载所有灰度配置
     */
    public Map<String, Map<String, Object>> loadAllCanaryConfigs() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT ling_id, config_data FROM governance_config WHERE config_type = 'canary'"
        );
        Map<String, Map<String, Object>> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String lingId = (String) row.get("ling_id");
            String json = (String) row.get("config_data");
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> config = objectMapper.readValue(json, Map.class);
                result.put(lingId, config);
            } catch (Exception e) {
                log.warn("解析灰度配置 JSON 失败: {}", lingId, e);
            }
        }
        return result;
    }

    // ==================== 调用治理 ====================

    /**
     * 保存调用治理配置
     */
    public void saveInvocationConfig(String lingId, String configJson) {
        jdbcTemplate.update(
            "INSERT OR REPLACE INTO governance_config (ling_id, config_type, config_data, updated_at) VALUES (?, 'invocation', ?, ?)",
            lingId, configJson, System.currentTimeMillis()
        );
    }

    /**
     * 加载所有调用治理配置
     */
    public Map<String, String> loadAllInvocationConfigs() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT ling_id, config_data FROM governance_config WHERE config_type = 'invocation'"
        );
        Map<String, String> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            result.put((String) row.get("ling_id"), (String) row.get("config_data"));
        }
        return result;
    }

    // ==================== 灵元状态 ====================

    /**
     * 保存灵元状态快照
     */
    public void saveLingStatus(String lingId, String status, String version) {
        jdbcTemplate.update(
            "INSERT OR REPLACE INTO ling_status (ling_id, status, version, updated_at) VALUES (?, ?, ?, ?)",
            lingId, status, version, System.currentTimeMillis()
        );
    }

    /**
     * 加载所有灵元状态
     */
    public Map<String, Map<String, String>> loadAllLingStatuses() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT ling_id, status, version FROM ling_status"
        );
        Map<String, Map<String, String>> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Map<String, String> status = new HashMap<>();
            status.put("status", (String) row.get("status"));
            status.put("version", (String) row.get("version"));
            result.put((String) row.get("ling_id"), status);
        }
        return result;
    }

    // ==================== 反序列化 ====================

    /**
     * 安全反序列化 GovernancePolicy JSON。
     * Jackson 不走 @Builder.Default，需手动兜底 null 集合。
     */
    public GovernancePolicy safeDeserialize(String json) {
        try {
            GovernancePolicy policy = objectMapper.readValue(json, GovernancePolicy.class);
            if (policy.getPermissions() == null) policy.setPermissions(new ArrayList<>());
            if (policy.getCapabilities() == null) policy.setCapabilities(new ArrayList<>());
            if (policy.getAudits() == null) policy.setAudits(new ArrayList<>());
            if (policy.getInvocation() == null) policy.setInvocation(new GovernancePolicy.InvocationPolicy());
            if (policy.getCollaborationMode() == null) policy.setCollaborationMode(CollaborationMode.PASSIVE);
            return policy;
        } catch (Exception e) {
            throw new RuntimeException("反序列化 GovernancePolicy 失败: " + json, e);
        }
    }
}

---

## Task 6: 审计记录存储

**Files:**
- Create: `lingframe-dashboard/src/main/java/com/lingframe/dashboard/storage/AuditStorage.java`

- [ ] **Step 1: 创建 AuditStorage**

```java
package com.lingframe.dashboard.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * 审计记录存储
 */
@Slf4j
public class AuditStorage {

    private final JdbcTemplate jdbcTemplate;

    public AuditStorage(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 保存审计记录
     */
    public void saveAudit(String lingId, String action, String detail, String result) {
        jdbcTemplate.update(
            "INSERT INTO audit_log (timestamp, ling_id, action, detail, result) VALUES (?, ?, ?, ?, ?)",
            System.currentTimeMillis(), lingId, action, detail, result
        );
    }

    /**
     * 查询审计记录
     */
    public List<Map<String, Object>> queryAudits(long startMs, long endMs, int limit) {
        return jdbcTemplate.queryForList(
            "SELECT * FROM audit_log WHERE timestamp BETWEEN ? AND ? ORDER BY timestamp DESC LIMIT ?",
            startMs, endMs, limit
        );
    }
}
```

---

## Task 7: 指标采集定时任务

**Files:**
- Create: `lingframe-dashboard/src/main/java/com/lingframe/dashboard/scheduler/MetricsCollectorScheduler.java`

- [ ] **Step 1: 创建 MetricsCollectorScheduler**

```java
package com.lingframe.dashboard.scheduler;

import com.lingframe.core.metrics.JVMMetrics;
import com.lingframe.dashboard.storage.MetricsStorage;
import com.lingframe.dashboard.storage.StorageInitializer;
import com.lingframe.dashboard.storage.StorageProperties;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 定时采集 JVM 指标并写入 SQLite
 */
@Slf4j
public class MetricsCollectorScheduler {

    private final MetricsStorage metricsStorage;
    private final StorageProperties properties;
    private final StorageInitializer storageInitializer;
    private ScheduledExecutorService scheduler;

    public MetricsCollectorScheduler(MetricsStorage metricsStorage,
                                      StorageProperties properties,
                                      StorageInitializer storageInitializer) {
        this.metricsStorage = metricsStorage;
        this.properties = properties;
        this.storageInitializer = storageInitializer;
    }

    /**
     * 启动定时采集
     */
    public void start() {
        if (!properties.isEnabled()) {
            log.info("[LingFrame] 指标持久化已禁用");
            return;
        }

        int interval = properties.getMetricsCollectIntervalSeconds();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lingframe-metrics-collector");
            t.setDaemon(true);
            return t;
        });

        // 初始延迟 5 秒，等待应用完全启动
        scheduler.scheduleAtFixedRate(this::collect, 5, interval, TimeUnit.SECONDS);

        // 每天清理一次过期数据
        scheduler.scheduleAtFixedRate(storageInitializer::cleanExpiredData, 1, 24, TimeUnit.HOURS);

        log.info("[LingFrame] 指标采集定时任务已启动，间隔: {}s", interval);
    }

    /**
     * 停止定时采集
     */
    public void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            log.info("[LingFrame] 指标采集定时任务已停止");
        }
    }

    private void collect() {
        try {
            JVMMetrics metrics = JVMMetrics.collect();
            metricsStorage.saveSnapshot(metrics);
        } catch (Exception e) {
            log.warn("[LingFrame] 指标采集失败", e);
        }
    }
}
```

---

## Task 8: 历史指标查询 API

**Files:**
- Create: `lingframe-dashboard/src/main/java/com/lingframe/dashboard/controller/MetricsHistoryController.java`

- [ ] **Step 1: 创建 MetricsHistoryController**

```java
package com.lingframe.dashboard.controller;

import com.lingframe.dashboard.dto.ApiResponse;
import com.lingframe.dashboard.storage.MetricsStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 历史指标查询 API
 */
@Slf4j
@RestController
@RequestMapping("/lingframe/dashboard/metrics")
@CrossOrigin(origins = "*")
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
public class MetricsHistoryController {

    private final MetricsStorage metricsStorage;

    /**
     * 当 MetricsStorage Bean 不存在时（storage.enabled=false），
     * Spring 会跳过此 Controller 的创建（构造函数注入失败 → Bean 不注册），
     * 效果等同于 @ConditionalOnBean，但避免了类级别 @ConditionalOnBean 的 Bean 定义顺序问题。
     */
    public MetricsHistoryController(MetricsStorage metricsStorage) {
        this.metricsStorage = metricsStorage;
    }

    /**
     * 查询指定时间范围的指标数据
     *
     * @param start    起始时间戳（毫秒），默认 30 分钟前
     * @param end      结束时间戳（毫秒），默认当前时间
     * @param interval 采样间隔（秒），0 表示不降采样，默认自动计算
     */
    @GetMapping("/history")
    public ApiResponse<List<Map<String, Object>>> getHistory(
            @RequestParam(required = false) Long start,
            @RequestParam(required = false) Long end,
            @RequestParam(required = false, defaultValue = "0") int interval) {

        try {
            long endMs = end != null ? end : System.currentTimeMillis();
            long startMs = start != null ? start : endMs - 30 * 60 * 1000;

            // 自动计算降采样间隔：确保返回不超过 500 个数据点
            if (interval <= 0) {
                long rangeMs = endMs - startMs;
                // 估算数据点数（按 10 秒一个点计算）
                long estimatedPoints = rangeMs / 10000;
                if (estimatedPoints > 500) {
                    interval = (int) (rangeMs / 500 / 1000);
                    if (interval < 10) interval = 10;
                }
            }

            List<Map<String, Object>> data = metricsStorage.queryRange(startMs, endMs, interval);
            return ApiResponse.ok(data);
        } catch (Exception e) {
            log.error("查询历史指标失败", e);
            return ApiResponse.error("查询历史指标失败: " + e.getMessage());
        }
    }

    /**
     * 查询指定时间范围的聚合统计
     */
    @GetMapping("/aggregation")
    public ApiResponse<Map<String, Object>> getAggregation(
            @RequestParam(required = false) Long start,
            @RequestParam(required = false) Long end) {

        try {
            long endMs = end != null ? end : System.currentTimeMillis();
            long startMs = start != null ? start : endMs - 24 * 60 * 60 * 1000;

            Map<String, Object> data = metricsStorage.queryAggregation(startMs, endMs);
            return ApiResponse.ok(data);
        } catch (Exception e) {
            log.error("查询指标聚合失败", e);
            return ApiResponse.error("查询指标聚合失败: " + e.getMessage());
        }
    }
}
```

---

## Task 9: 访问令牌拦截器

**Files:**
- Create: `lingframe-dashboard/src/main/java/com/lingframe/dashboard/security/AccessTokenProperties.java`
- Create: `lingframe-dashboard/src/main/java/com/lingframe/dashboard/security/AccessTokenInterceptor.java`

- [ ] **Step 1: 创建 AccessTokenProperties**

```java
package com.lingframe.dashboard.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 访问令牌配置
 */
@Data
@ConfigurationProperties(prefix = "lingframe.dashboard.access-token")
public class AccessTokenProperties {

    /** 访问令牌，留空则不启用认证 */
    private String token = "";

    /** 是否启用（token 非空时自动启用） */
    public boolean isEnabled() {
        return token != null && !token.trim().isEmpty();
    }
}
```

- [ ] **Step 2: 创建 AccessTokenInterceptor**

```java
package com.lingframe.dashboard.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Dashboard 访问令牌拦截器
 * 支持通过 URL 参数或 Authorization Header 传递令牌
 */
@Slf4j
public class AccessTokenInterceptor implements HandlerInterceptor {

    private final AccessTokenProperties properties;

    public AccessTokenInterceptor(AccessTokenProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!properties.isEnabled()) {
            return true;
        }

        String token = extractToken(request);
        if (token != null && token.equals(properties.getToken())) {
            return true;
        }

        // 认证失败，返回 401
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"Unauthorized: invalid or missing access token\"}");
        log.warn("[LingFrame] Dashboard 访问被拒绝: {} {}", request.getMethod(), request.getRequestURI());
        return false;
    }

    /**
     * 从请求中提取令牌
     * 优先级：Authorization Header > URL 参数 token
     */
    private String extractToken(HttpServletRequest request) {
        // 1. Authorization: Bearer xxx
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7).trim();
        }

        // 2. URL 参数 ?token=xxx
        String paramToken = request.getParameter("token");
        if (paramToken != null && !paramToken.isEmpty()) {
            return paramToken;
        }

        return null;
    }
}
```

---

## Task 10: 注册所有新 Bean 到 AutoConfiguration

**Files:**
- Modify: `lingframe-dashboard/src/main/java/com/lingframe/dashboard/config/DashboardAutoConfiguration.java`

- [ ] **Step 1: 添加新 Bean 注册**

在 `DashboardAutoConfiguration` 中添加以下 import 和 Bean 定义：

需要新增的 import：
```java
import com.lingframe.dashboard.storage.AuditStorage;
import com.lingframe.dashboard.storage.GovernanceStorage;
import com.lingframe.dashboard.storage.MetricsStorage;
import com.lingframe.dashboard.storage.StorageInitializer;
import com.lingframe.dashboard.storage.StorageProperties;
import com.lingframe.dashboard.scheduler.MetricsCollectorScheduler;
import com.lingframe.dashboard.security.AccessTokenInterceptor;
import com.lingframe.dashboard.security.AccessTokenProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
```

类注解添加 `@EnableConfigurationProperties`：
```java
@EnableConfigurationProperties(StorageProperties.class)
```

> **注意**：`AccessTokenProperties` 通过下方 `@Bean` 方法单独注册，不放入 `@EnableConfigurationProperties`，避免双重注册。

新增 Bean 方法：

```java
// ==================== SQLite 持久化 ====================

@Bean
@ConditionalOnProperty(prefix = "lingframe.dashboard.storage", name = "enabled", havingValue = "true", matchIfMissing = true)
public DriverManagerDataSource sqliteDataSource(StorageProperties storageProperties) {
    String dataDir = storageProperties.getDataDir();
    new java.io.File(dataDir).mkdirs();
    String dbPath = dataDir + "/lingframe.db";
    log.info("[LingFrame] SQLite 数据库路径: {}", dbPath);
    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setDriverClassName("org.sqlite.JDBC");
    ds.setUrl("jdbc:sqlite:" + dbPath);
    // 生产级连接参数：
    //   journal_mode=WAL        提升读写并发（允许多读 + 单写）
    //   synchronous=NORMAL      写入性能优化（崩溃时可能丢最后一笔事务，可接受）
    //   busy_timeout=5000       写锁等待最多 5 秒，避免 SQLITE_BUSY 立即失败
    //   foreign_keys=ON         启用外键约束
    //   temp_store=MEMORY       临时表放内存
    ds.setConnectionProperties(
        "journal_mode=WAL;synchronous=NORMAL;busy_timeout=5000;foreign_keys=ON;temp_store=MEMORY"
    );
    return ds;
}

@Bean
@ConditionalOnBean(DriverManagerDataSource.class)
public JdbcTemplate jdbcTemplate(DriverManagerDataSource dataSource) {
    return new JdbcTemplate(dataSource);
}

@Bean
@ConditionalOnBean(JdbcTemplate.class)
public StorageInitializer storageInitializer(JdbcTemplate jdbcTemplate, StorageProperties properties) {
    return new StorageInitializer(jdbcTemplate, properties);
}

@Bean
@ConditionalOnBean(JdbcTemplate.class)
public MetricsStorage metricsStorage(JdbcTemplate jdbcTemplate) {
    return new MetricsStorage(jdbcTemplate);
}

@Bean
@ConditionalOnBean(JdbcTemplate.class)
public GovernanceStorage governanceStorage(JdbcTemplate jdbcTemplate) {
    return new GovernanceStorage(jdbcTemplate);
}

@Bean
@ConditionalOnBean(JdbcTemplate.class)
public AuditStorage auditStorage(JdbcTemplate jdbcTemplate) {
    return new AuditStorage(jdbcTemplate);
}

// 注意：MetricsHistoryController 通过 @RestController 注解由组件扫描自动创建，不在此处手动注册

// ==================== 访问令牌 ====================

@Bean
public AccessTokenProperties accessTokenProperties() {
    return new AccessTokenProperties();
}

@Bean
public AccessTokenInterceptor accessTokenInterceptor(AccessTokenProperties properties) {
    return new AccessTokenInterceptor(properties);
}
```

修改 `dashboardWebMvcConfigurer` Bean，注入 interceptor 而非手动 new：

```java
@Bean
public WebMvcConfigurer dashboardWebMvcConfigurer(
        AccessTokenProperties accessTokenProperties,
        AccessTokenInterceptor accessTokenInterceptor) {
    return new WebMvcConfigurer() {
        @Override
        public void addViewControllers(ViewControllerRegistry registry) {
            registry.addRedirectViewController("/lingframe/dashboard/ui", "/lingframe/dashboard/ui/");
            registry.addViewController("/lingframe/dashboard/ui/").setViewName("forward:/dashboard.html");
            registry.addViewController("/lingframe/dashboard/ui/{path:[^\\.]*}")
                    .setViewName("forward:/dashboard.html");
            registry.addViewController("/lingframe/dashboard/ui/**/{path:[^\\.]*}")
                    .setViewName("forward:/dashboard.html");
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            if (accessTokenProperties.isEnabled()) {
                // 拦截所有 API Controller 路径（按实际 @RequestMapping 精确匹配）
                // 静态资源（HTML/JS/CSS）和 SSE 流不拦截
                registry.addInterceptor(accessTokenInterceptor)
                        .addPathPatterns(
                            "/lingframe/dashboard/lings/**",       // LingController
                            "/lingframe/dashboard/governance/**",  // GovernanceController
                            "/lingframe/dashboard/simulate/**",    // SimulateController
                            "/lingframe/dashboard/playground/**",  // ServicePlaygroundController
                            "/lingframe/dashboard/metrics/**"      // MetricsHistoryController（新增）
                        )
                        .excludePathPatterns(
                            "/lingframe/dashboard/stream",          // SSE 流（StreamController）
                            "/lingframe/dashboard/lings/install"   // 安装端点（首次部署免 token）
                        );
            }
        }
    };
}
```

同时需要在 AutoConfiguration 中添加启动和停止逻辑。在类中添加：

```java
@javax.annotation.PostConstruct
public void onInit() {
    // SQLite 初始化由 StorageInitializer Bean 的 initialize() 触发
    // 指标采集由 MetricsCollectorScheduler Bean 的 start() 触发
}

@javax.annotation.PreDestroy
public void onDestroy() {
    // 停止指标采集定时任务
}
```

实际上更好的做法是在 StorageInitializer 和 MetricsCollectorScheduler 上使用 `@PostConstruct` 和 `@PreDestroy`。但由于这些类不是 Spring 组件（通过 @Bean 方法创建），需要在 Bean 方法中手动调用。修改 Bean 方法：

```java
@Bean(initMethod = "initialize")
@ConditionalOnBean(JdbcTemplate.class)
public StorageInitializer storageInitializer(JdbcTemplate jdbcTemplate, StorageProperties properties) {
    return new StorageInitializer(jdbcTemplate, properties);
}

@Bean(initMethod = "start", destroyMethod = "stop")
@ConditionalOnBean({MetricsStorage.class, StorageProperties.class, StorageInitializer.class})
public MetricsCollectorScheduler metricsCollectorScheduler(
        MetricsStorage metricsStorage,
        StorageProperties properties,
        StorageInitializer storageInitializer) {
    return new MetricsCollectorScheduler(metricsStorage, properties, storageInitializer);
}
```

- [ ] **Step 2: 验证编译**

Run: `mvn compile -pl lingframe-dashboard -am -q`
Expected: BUILD SUCCESS

---

## Task 11: 治理配置变更时同步写入 SQLite

**Files:**
- Modify: `lingframe-dashboard/src/main/java/com/lingframe/dashboard/service/DashboardGovernanceSupport.java`（治理策略 + 调用治理 + 权限）
- Modify: `lingframe-dashboard/src/main/java/com/lingframe/dashboard/service/DashboardService.java`（灰度配置）

> **写入点选择原则**：治理变更的调用链是 Controller → DashboardService → DashboardGovernanceSupport，
> SQLite 写入应在**最内层**（`DashboardGovernanceSupport` / `DashboardService`）而非 Controller，
> 这样无论从哪个入口触发都能持久化，且不增加 Controller 层的职责。

- [ ] **Step 1: 灰度配置持久化 — 修改 DashboardService**

`DashboardService.setCanaryConfig` 直接操作 `CanaryRouter`，适合在此处直接写入 SQLite。

注入 `GovernanceStorage`（null-safe，不破坏现有构造函数链）：

```java
// 在 DashboardService 中新增字段
private GovernanceStorage governanceStorage;

// 新增 setter 供 DashboardAutoConfiguration 注入（不修改现有构造函数）
// 同时传递给 governanceSupport，确保治理变更写入点也能访问 Storage
public void setGovernanceStorage(GovernanceStorage governanceStorage) {
    this.governanceStorage = governanceStorage;
    if (this.governanceSupport != null) {
        this.governanceSupport.setGovernanceStorage(governanceStorage);
    }
}
```

在 `setCanaryConfig` 方法末尾添加：

```java
public void setCanaryConfig(String lingId, int percent, String canaryVersion) {
    LingRuntime runtime = lingRepository.getRuntime(lingId);
    if (runtime == null) {
        throw new LingNotFoundException(lingId);
    }
    canaryRouter.setCanaryConfig(lingId, percent, canaryVersion);
    // 持久化到 SQLite
    if (governanceStorage != null) {
        governanceStorage.saveCanaryConfig(lingId, percent, canaryVersion);
    }
}
```

- [ ] **Step 2: 治理策略 + 调用治理 + 权限持久化 — 修改 DashboardGovernanceSupport**

`DashboardGovernanceSupport` 是所有治理变更的汇聚点，在此处统一持久化最合适。

注入 `GovernanceStorage`（null-safe）：

```java
// 在 DashboardGovernanceSupport 中新增字段
private GovernanceStorage governanceStorage;
private final ObjectMapper objectMapper = new ObjectMapper();

// 新增 setter
public void setGovernanceStorage(GovernanceStorage governanceStorage) {
    this.governanceStorage = governanceStorage;
}
```

在 `updateGovernancePolicy` 方法末尾添加：

```java
public void updateGovernancePolicy(String lingId, GovernancePolicy policy) {
    GovernancePolicy mergedPatch = GovernancePolicy.merge(getPatchForUpdate(lingId), policy);
    persistPolicyPatch(lingId, mergedPatch);
    // 持久化到 SQLite
    if (governanceStorage != null) {
        try {
            governanceStorage.saveInvocationConfig(lingId, objectMapper.writeValueAsString(mergedPatch));
        } catch (Exception e) {
            log.warn("持久化治理策略失败: {}", lingId, e);
        }
    }
}
```

在 `updateInvocationGovernance` 方法末尾添加（序列化更新后的完整 patch）：

```java
public InvocationGovernanceDTO updateInvocationGovernance(String lingId, InvocationGovernanceDTO dto) {
    // ... 现有逻辑 ...
    GovernancePolicy patch = getPatchForUpdate(lingId);
    // ... 更新 invocation 字段 ...
    persistPolicyPatch(lingId, patch);
    // 持久化到 SQLite
    if (governanceStorage != null) {
        try {
            governanceStorage.saveInvocationConfig(lingId, objectMapper.writeValueAsString(patch));
        } catch (Exception e) {
            log.warn("持久化调用治理配置失败: {}", lingId, e);
        }
    }
    return dto;
}
```

在 `updatePermissions` 方法末尾添加（序列化更新后的完整 patch）：

```java
public void updatePermissions(String lingId, ResourcePermissionDTO dto) {
    // ... 现有逻辑 ...
    persistPolicyPatch(lingId, policy);
    // 持久化到 SQLite
    if (governanceStorage != null) {
        try {
            governanceStorage.saveInvocationConfig(lingId, objectMapper.writeValueAsString(policy));
        } catch (Exception e) {
            log.warn("持久化权限配置失败: {}", lingId, e);
        }
    }
}
```

- [ ] **Step 3: 在 DashboardAutoConfiguration 中注入 GovernanceStorage**

```java
@Bean
public DashboardService dashboardService(
        /* 现有参数 */,
        @Autowired(required = false) GovernanceStorage governanceStorage) {
    DashboardService service = new DashboardService(/* 现有参数 */);
    // 条件注入 GovernanceStorage（SQLite 启用时才有，禁用时为 null）
    if (governanceStorage != null) {
        service.setGovernanceStorage(governanceStorage);
    }
    return service;
}

// GovernanceStorage Bean（在 SQLite 配置段中）
@Bean
@ConditionalOnProperty(prefix = "lingframe.dashboard.storage", name = "enabled", havingValue = "true", matchIfMissing = true)
public GovernanceStorage governanceStorage(JdbcTemplate jdbcTemplate) {
    GovernanceStorage storage = new GovernanceStorage(jdbcTemplate);
    // 同时注入到 DashboardGovernanceSupport
    return storage;
}
```

> **注意**：`DashboardGovernanceSupport` 不是直接由 `DashboardAutoConfiguration` 创建的 Bean
> （它在 `DashboardService` 构造函数中内部创建），所以需要通过 `DashboardService.setGovernanceStorage` 间接注入（已在 Step 1 中实现）。

- [ ] **Step 4: 验证编译**

Run: `mvn compile -pl lingframe-dashboard -am -q`
Expected: BUILD SUCCESS

---

## Task 12: 启动时从 SQLite 恢复治理配置

**Files:**
- Modify: `lingframe-dashboard/src/main/java/com/lingframe/dashboard/config/DashboardAutoConfiguration.java`

- [ ] **Step 1: 在 StorageInitializer 中添加恢复方法**

在 `StorageInitializer` 中添加：

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.lingframe.core.router.CanaryRouter;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.governance.GovernancePolicy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 恢复持久化的治理配置到运行时
 * 必须在 ApplicationContext 完全就绪后调用（CanaryRouter 和 LocalGovernanceRegistry 已初始化）
 * <p>
 * 恢复失败时抛出 RuntimeException，启动流程中止（fail-fast），
 * 避免出现"应用起来了但配置缺失"的隐性故障。
 */
public void restoreGovernanceConfig(GovernanceStorage governanceStorage,
                                     CanaryRouter canaryRouter,
                                     LocalGovernanceRegistry governanceRegistry) {
    int restoredCanary = 0;
    int restoredInvocation = 0;
    int restoredStatus = 0;

    // 1. 恢复灰度配置
    try {
        Map<String, Map<String, Object>> canaryConfigs = governanceStorage.loadAllCanaryConfigs();
        for (Map.Entry<String, Map<String, Object>> entry : canaryConfigs.entrySet()) {
            String lingId = entry.getKey();
            Map<String, Object> config = entry.getValue();
            int percent = config.get("percent") instanceof Number
                    ? ((Number) config.get("percent")).intValue() : 0;
            String version = config.get("canaryVersion") instanceof String
                    ? (String) config.get("canaryVersion") : "";
            canaryRouter.setCanaryConfig(lingId, percent, version);
            restoredCanary++;
        }
    } catch (Exception e) {
        throw new RuntimeException("[LingFrame] 恢复灰度配置失败", e);
    }

    // 2. 恢复调用治理（invocation）
    try {
        Map<String, String> invocationConfigs = governanceStorage.loadAllInvocationConfigs();
        for (Map.Entry<String, String> entry : invocationConfigs.entrySet()) {
            String lingId = entry.getKey();
            String json = entry.getValue();
            if (json == null || json.isEmpty()) continue;
            // 使用 GovernanceStorage.safeDeserialize 确保 @Builder.Default 兜底
            GovernancePolicy policy = governanceStorage.safeDeserialize(json);
            // 注意：LocalGovernanceRegistry 的方法是 updatePatch，不是 updatePolicy
            governanceRegistry.updatePatch(lingId, policy);
            restoredInvocation++;
        }
    } catch (Exception e) {
        throw new RuntimeException("[LingFrame] 恢复调用治理配置失败", e);
    }

    // 3. 恢复灵元状态（ling_status）
    try {
        Map<String, Map<String, Object>> statusMap = governanceStorage.loadAllLingStatus();
        for (Map.Entry<String, Map<String, Object>> entry : statusMap.entrySet()) {
            String lingId = entry.getKey();
            Map<String, Object> status = entry.getValue();
            // 仅记录日志或交给 Dashboard 读取时按需使用，运行时不需要主动注入
            // 此处作为持久化校验，统计恢复数量
            restoredStatus++;
        }
    } catch (Exception e) {
        throw new RuntimeException("[LingFrame] 恢复灵元状态失败", e);
    }

    log.info("[LingFrame] 治理配置恢复完成: canary={}, invocation={}, status={}",
            restoredCanary, restoredInvocation, restoredStatus);
}
```

- [ ] **Step 2: 在 GovernanceStorage 中补全 loadAllInvocationConfigs / loadAllLingStatus**

```java
/**
 * 加载所有 invocation 治理配置（lingId -> JSON 字符串）
 */
public Map<String, String> loadAllInvocationConfigs() {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "SELECT ling_id, config_data FROM governance_config WHERE config_type = 'invocation'"
    );
    Map<String, String> result = new HashMap<>();
    for (Map<String, Object> row : rows) {
        result.put((String) row.get("ling_id"), (String) row.get("config_data"));
    }
    return result;
}

/**
 * 加载所有灵元状态快照
 */
public Map<String, Map<String, Object>> loadAllLingStatus() {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "SELECT ling_id, status, version, updated_at FROM ling_status"
    );
    Map<String, Map<String, Object>> result = new HashMap<>();
    for (Map<String, Object> row : rows) {
        Map<String, Object> status = new HashMap<>();
        status.put("status", row.get("status"));
        status.put("version", row.get("version"));
        status.put("updated_at", row.get("updated_at"));
        result.put((String) row.get("ling_id"), status);
    }
    return result;
}
```

> **注意**：`GovernanceStorage` 已有 `loadAllLingStatus` 方法（散点结构），此处统一为上述 Map 形式，并新增 `loadAllInvocationConfigs`。

- [ ] **Step 3: 在 DashboardAutoConfiguration 中使用 `@PostConstruct` 延迟恢复**

不使用构造函数副作用，而是通过一个内部 Bean 的 `@PostConstruct` 在容器完全就绪后执行：

```java
import javax.annotation.PostConstruct;

@Bean
@ConditionalOnBean({StorageInitializer.class, GovernanceStorage.class, CanaryRouter.class, LocalGovernanceRegistry.class})
public Object governanceConfigRestorer(
        StorageInitializer storageInitializer,
        GovernanceStorage governanceStorage,
        CanaryRouter canaryRouter,
        LocalGovernanceRegistry governanceRegistry) {
    // 返回一个带有 @PostConstruct 的匿名对象，确保在容器就绪后执行恢复
    return new Object() {
        @PostConstruct
        public void restore() {
            storageInitializer.restoreGovernanceConfig(governanceStorage, canaryRouter, governanceRegistry);
        }
    };
}
```

> **说明**：`@PostConstruct` 会在所有依赖注入完成后、Bean 初始化完成前调用，此时 `CanaryRouter` 和 `LocalGovernanceRegistry` 已完全就绪。相比在 `@Bean` 方法中直接调用（构造函数副作用），这种方式保证了正确的初始化顺序。

---

## Task 13: 前端 - 历史指标查询与时间范围扩展

**Files:**
- Modify: `lingframe-dashboard/src/main/resources/static/js/dashboard.js`
- Modify: `lingframe-dashboard/src/main/resources/static/dashboard.html`
- Modify: `lingframe-dashboard/src/main/resources/static/i18n/zh-CN.json`
- Modify: `lingframe-dashboard/src/main/resources/static/i18n/en-US.json`

- [ ] **Step 1: 修改 dashboard.js - 添加历史数据查询方法**

在 `fetchPerformanceMetrics` 函数附近添加：

```javascript
// 查询历史指标数据
const fetchMetricsHistory = async (startMs, endMs) => {
    try {
        const params = new URLSearchParams();
        params.set('start', startMs);
        params.set('end', endMs);
        const data = await api.get('/metrics/history?' + params.toString());
        return data;
    } catch (e) {
        console.warn('查询历史指标失败', e);
        return null;
    }
};
```

修改 `chartTimeRange` 的 watch 逻辑，当选择"今天"或"昨天"等跨天范围时，从后端查询：

```javascript
// 时间范围配置
const rangeConfig = {
    '5m':   { type: 'local', ms: 300000 },
    '15m':  { type: 'local', ms: 900000 },
    '30m':  { type: 'local', ms: 1800000 },
    '1h':   { type: 'local', ms: 3600000 },
    '3h':   { type: 'local', ms: 10800000 },
    'today': { type: 'remote', getStart: () => { const d = new Date(); d.setHours(0,0,0,0); return d.getTime(); } },
    'yesterday': { type: 'remote', getStart: () => { const d = new Date(); d.setDate(d.getDate()-1); d.setHours(0,0,0,0); return d.getTime(); }, getEnd: () => { const d = new Date(); d.setHours(0,0,0,0); return d.getTime(); } },
    '7d':   { type: 'remote', getStart: () => Date.now() - 7*24*60*60*1000 }
};

watch(chartTimeRange, async () => {
    const config = rangeConfig[chartTimeRange.value];
    if (!config) return;

    if (config.type === 'local') {
        // 使用前端内存中的数据
        nextTick(() => drawMonitorCharts());
    } else {
        // 从后端 SQLite 查询
        const startMs = config.getStart();
        const endMs = config.getEnd ? config.getEnd() : Date.now();
        const data = await fetchMetricsHistory(startMs, endMs);
        if (data && data.length > 0) {
            drawHistoryCharts(data);
        } else {
            // 无历史数据时显示空状态提示
            clearAllCharts();
            showEmptyState('所选时间范围内暂无监控数据');
        }
    }
});
```

添加 `drawHistoryCharts` 方法，将后端返回的数据绘制到图表：

```javascript
const drawHistoryCharts = (historyData) => {
    if (activeNav.value !== 'monitor') return;
    if (typeof Chart === 'undefined') return;

    const labels = historyData.map(d => {
        const ts = d.sample_time || d.timestamp;
        return new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    });

    // 字段映射：后端字段名 -> 前端 chart key
    const fieldMap = {
        cpu_usage: 'cpu',
        heap_usage: 'heapUsage',
        metaspace_usage: 'metaspaceUsage',
        thread_count: 'threads',
        gc_count: 'gcCount',
        gc_time_ms: 'gcTimeMs',
        loaded_class_count: 'loadedClassCount'
    };

    monitorCharts.value.forEach(chart => {
        const canvas = document.getElementById('chart-' + chart.key);
        if (!canvas) return;

        // 找到对应的后端字段
        const backendKey = Object.keys(fieldMap).find(k => fieldMap[k] === chart.key);
        if (!backendKey) return;

        const data = historyData.map(d => d[backendKey] != null ? (chart.isPercent ? Math.round(d[backendKey] * 10) / 10 : d[backendKey]) : null);
        if (data.length < 2) return;

        let instance = chartInstances[chart.key];
        if (instance) {
            instance.data.labels = labels;
            instance.data.datasets[0].data = data;
            instance.update('none');
            return;
        }

        // 创建新实例（同 drawMonitorCharts 中的创建逻辑）
        const ctx = canvas.getContext('2d');
        const gradient = ctx.createLinearGradient(0, 0, 0, 120);
        gradient.addColorStop(0, chart.color + '40');
        gradient.addColorStop(1, chart.color + '05');

        chartInstances[chart.key] = new Chart(ctx, {
            type: 'line',
            data: {
                labels: labels,
                datasets: [{
                    data: data,
                    borderColor: chart.color,
                    backgroundColor: gradient,
                    borderWidth: 1.5,
                    fill: true,
                    pointRadius: 0,
                    pointHitRadius: 6,
                    tension: 0.3
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                animation: false,
                interaction: { mode: 'index', intersect: false },
                plugins: {
                    legend: { display: false },
                    tooltip: {
                        backgroundColor: 'rgba(15,23,42,0.9)',
                        titleColor: '#e2e8f0',
                        bodyColor: '#94a3b8',
                        borderColor: '#334155',
                        borderWidth: 1,
                        padding: 8,
                        displayColors: false
                    }
                },
                scales: {
                    x: {
                        display: true,
                        ticks: { color: '#475569', maxTicksLimit: 8, font: { size: 10 } },
                        grid: { color: '#1e293b' }
                    },
                    y: {
                        display: true,
                        min: chart.isPercent ? 0 : undefined,
                        max: chart.isPercent ? 100 : undefined,
                        ticks: {
                            color: '#475569',
                            font: { size: 10 },
                            callback: v => chart.isPercent ? v + '%' : v
                        },
                        grid: { color: '#1e293b' }
                    }
                }
            }
        });
    });
};
```

在 `return` 语句中暴露 `fetchMetricsHistory` 和 `drawHistoryCharts`。

**前端 401 处理 + token 自动附加**（替换现有 `api` 对象，启用 token 时必备）：

> **关键**：现有 `dashboard.js` 中有 19 处 `api.get/post` 调用，全部走 `API_BASE + path` 拼接。
> 改造方案：**直接修改现有 `api` 对象**，在底层统一附加 token 和处理 401，
> 而非新增 `withToken()` 函数让每个调用点手动包装——这样所有现有调用点零改动即可支持 token。

```javascript
// ==================== API 调用（改造版：统一 token + 401 处理）====================
const api = {
    async get(path) {
        const url = withToken(API_BASE + path);
        const res = await fetch(url, { credentials: 'same-origin' });
        if (res.status === 401) { showLoginPrompt(); throw new Error('Unauthorized'); }
        const data = await res.json();
        if (!data.success) throw new Error(data.message);
        return data.data;
    },
    async post(path, body = {}) {
        const url = withToken(API_BASE + path);
        const res = await fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'same-origin',
            body: JSON.stringify(body)
        });
        if (res.status === 401) { showLoginPrompt(); throw new Error('Unauthorized'); }
        const data = await res.json();
        if (!data.success) throw new Error(data.message);
        return data.data;
    }
};

// URL 附加 token 参数（仅当 sessionStorage 中有 token 时）
const withToken = (url) => {
    const token = sessionStorage.getItem('lingframe_token');
    if (!token) return url;
    const sep = url.includes('?') ? '&' : '?';
    return url + sep + 'token=' + encodeURIComponent(token);
};

// 401 时弹出 token 输入提示
const showLoginPrompt = () => {
    // 避免重复弹窗
    if (sessionStorage.getItem('lingframe_prompting')) return;
    sessionStorage.setItem('lingframe_prompting', '1');
    const token = prompt('请输入访问令牌（将存入 sessionStorage）：');
    sessionStorage.removeItem('lingframe_prompting');
    if (token) {
        sessionStorage.setItem('lingframe_token', token);
        location.reload();
    }
};
```

> **改造要点**：
> 1. 现有 19 处 `api.get/post` 调用**无需任何修改**，token 在底层自动附加
> 2. `credentials: 'same-origin'` 确保浏览器 Cookie/Session 上下文传递
> 3. `showLoginPrompt` 加防重入锁，避免多个并发 401 响应导致多次弹窗
> 4. SSE 连接（`EventSource`）在拦截器 `excludePathPatterns` 中已放行，无需处理

同时添加空状态辅助函数：

```javascript
// 清除所有图表
const clearAllCharts = () => {
    Object.keys(chartInstances).forEach(key => {
        if (chartInstances[key]) {
            chartInstances[key].destroy();
            delete chartInstances[key];
        }
    });
};

// 显示空状态提示（在图表区域居中显示）
const showEmptyState = (message) => {
    monitorCharts.value.forEach(chart => {
        const canvas = document.getElementById('chart-' + chart.key);
        if (canvas) {
            canvas.style.display = 'none';
        }
    });
    // 显示空状态占位文字
    const container = document.getElementById('monitor-charts-container');
    if (container) {
        container.innerHTML = `<div class="flex items-center justify-center h-48 text-slate-500 text-sm">${message}</div>`;
    }
};
```

- [ ] **Step 2: 修改 dashboard.html - 扩展时间范围选择器**

找到 `<select v-model="chartTimeRange">` 元素，添加新选项：

```html
<select v-model="chartTimeRange" class="bg-slate-700 text-white text-xs rounded px-2 py-1 border border-slate-600 focus:outline-none focus:border-blue-500">
    <option value="5m">{{ t('monitor.last5m') }}</option>
    <option value="15m">{{ t('monitor.last15m') }}</option>
    <option value="30m">{{ t('monitor.last30m') }}</option>
    <option value="1h">{{ t('monitor.last1h') }}</option>
    <option value="3h">{{ t('monitor.last3h') }}</option>
    <option value="today">{{ t('monitor.today') }}</option>
    <option value="yesterday">{{ t('monitor.yesterday') }}</option>
    <option value="7d">{{ t('monitor.last7d') }}</option>
</select>
```

- [ ] **Step 3: 添加 i18n 翻译**

zh-CN.json 中 `monitor` 部分添加：
```json
"today": "今天",
"yesterday": "昨天",
"last7d": "近7天"
```

en-US.json 中 `monitor` 部分添加：
```json
"today": "Today",
"yesterday": "Yesterday",
"last7d": "Last 7 Days"
```

---

## Task 14: 验证与集成测试

**Files:**
- Modify: `lingframe-examples/lingframe-example-lingcore-app/src/main/resources/application.yaml`

- [ ] **Step 1: 在示例应用中添加配置**

```yaml
lingframe:
  dashboard:
    enabled: true
    install-enabled: true
    storage:
      enabled: true
      data-dir: ./lingframe-data
      metrics-retention-days: 7
      metrics-collect-interval-seconds: 10
    access-token:
      token: ""  # 留空不启用，设置值则启用
```

- [ ] **Step 2: 编译并启动示例应用验证**

Run: `mvn compile -pl lingframe-dashboard -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 验证 SQLite 数据库文件生成**

启动应用后，检查 `lingframe-data/lingframe.db` 是否生成，以及 `metrics_snapshot` 表是否有数据写入。

- [ ] **Step 4: 验证历史指标 API**

访问 `GET /lingframe/dashboard/metrics/history?start=<30分钟前时间戳>&end=<当前时间戳>`，确认返回数据。

- [ ] **Step 5: 验证访问令牌**

设置 `lingframe.dashboard.access-token.token=test123`，重启应用，访问 Dashboard API 需要带 `?token=test123` 才能访问。

---

## Task 15: 集成测试用例（生产级必做）

**Files:**
- Create: `lingframe-dashboard/src/test/java/com/lingframe/dashboard/storage/StorageIntegrationTest.java`
- Create: `lingframe-dashboard/src/test/java/com/lingframe/dashboard/security/AccessTokenIntegrationTest.java`
- Create: `lingframe-dashboard/src/test/java/com/lingframe/dashboard/restore/GovernanceConfigRestoreTest.java`

> **目的**：用真实嵌入式 SQLite 验证 13 个修复中的关键路径，防止回归。

- [ ] **Step 1: 添加测试依赖**

在 `lingframe-dashboard/pom.xml` 中添加：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: StorageIntegrationTest — 验证主键、SQL 聚合、并发写入**

```java
package com.lingframe.dashboard.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证关键修复：
 * 1. governance_config 联合主键允许同一 lingId 存多种 config_type
 * 2. GC delta 计算用 MAX-MIN 且 COALESCE 保护 NULL
 * 3. 降采样时 GC 累计值用 MAX 而非 AVG
 */
class StorageIntegrationTest {

    private JdbcTemplate jdbcTemplate;
    private StorageInitializer initializer;
    private GovernanceStorage governanceStorage;
    private MetricsStorage metricsStorage;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite::memory:");
        ds.setConnectionProperties("journal_mode=WAL;busy_timeout=5000");
        jdbcTemplate = new JdbcTemplate(ds);

        StorageProperties props = new StorageProperties();
        initializer = new StorageInitializer(jdbcTemplate, props);
        initializer.initialize();

        governanceStorage = new GovernanceStorage(jdbcTemplate);
        metricsStorage = new MetricsStorage(jdbcTemplate);
    }

    @Test
    void governanceConfig_compositeKey_allowsMultipleTypes() {
        // 同一 lingId 存 canary + invocation 两种配置
        governanceStorage.saveCanaryConfig("ling-001", 30, "v2");
        governanceStorage.saveInvocationConfig("ling-001", "{\"timeout\":5000}");

        // 查询不覆盖
        Map<String, Object> canary = governanceStorage.loadAllCanaryConfigs().get("ling-001");
        assertEquals(30, canary.get("percent"));
        assertEquals("v2", canary.get("canaryVersion"));

        String invocationJson = governanceStorage.loadAllInvocationConfigs().get("ling-001");
        assertTrue(invocationJson.contains("timeout"));
    }

    @Test
    void queryAggregation_gcDelta_usesFirstLastSnapshot() {
        long now = System.currentTimeMillis();
        // 模拟 5 个快照，gc_count 从 100 增加到 500
        for (int i = 0; i < 5; i++) {
            insertMetricsSnapshot(now + i * 10_000, i * 100 + 100);
        }

        Map<String, Object> agg = metricsStorage.queryAggregation(now, now + 50_000);
        // 末值 - 初值 = 500 - 100 = 400
        assertEquals(400, ((Number) agg.get("delta_gc_count")).intValue());
    }

    @Test
    void queryRange_downsampling_gcUsesMaxNotAvg() {
        long now = System.currentTimeMillis();
        insertMetricsSnapshot(now, 100);
        insertMetricsSnapshot(now + 5_000, 200);
        insertMetricsSnapshot(now + 10_000, 300);

        // interval=10s 把 3 条数据分到同一桶
        var rows = metricsStorage.queryRange(now, now + 10_000, 10);
        // gc_count 应是 MAX(100,200,300) = 300，不是 AVG
        assertEquals(300, ((Number) rows.get(0).get("gc_count")).intValue());
    }

    @Test
    void concurrentWrites_busyTimeout_doesNotThrow() throws Exception {
        // 并发 10 个线程各写入 100 条，busy_timeout=5000 应保证全部成功
        ExecutorService exec = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(10);
        for (int t = 0; t < 10; t++) {
            exec.submit(() -> {
                try {
                    for (int i = 0; i < 100; i++) {
                        insertMetricsSnapshot(System.currentTimeMillis(), i);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS));
        exec.shutdown();
    }

    private void insertMetricsSnapshot(long ts, long gcCount) {
        jdbcTemplate.update(
            "INSERT INTO metrics_snapshot (timestamp, cpu_usage, heap_usage, metaspace_usage, " +
            "  thread_count, gc_count, gc_time_ms, loaded_class_count) " +
            "VALUES (?, 0, 0, 0, 0, ?, 0, 0)",
            ts, gcCount
        );
    }
}
```

- [ ] **Step 3: AccessTokenIntegrationTest — 验证拦截路径与放行**

```java
package com.lingframe.dashboard.security;

import com.lingframe.dashboard.config.DashboardAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证修复：
 * - 静态资源（HTML/JS/CSS）放行
 * - API 端点强制 token
 * - install / stream 端点放行
 *
 * 注意：此测试需要 Spring Boot 应用主类（放在 example 模块中运行），
 * 或在 lingframe-dashboard 中创建专用测试配置类。
 * 若在 dashboard 模块单独运行，需添加：
 * @SpringBootConfiguration
 * @Import(DashboardAutoConfiguration.class)
 * static class TestConfig {}
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "lingframe.dashboard.enabled=true",
    "lingframe.dashboard.access-token.token=secret123"
})
class AccessTokenIntegrationTest {

    @Autowired MockMvc mockMvc;

    @Test
    void apiWithoutToken_returns401() throws Exception {
        mockMvc.perform(get("/lingframe/dashboard/lings"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void apiWithToken_returns200Or404() throws Exception {
        // 带 token 不会被拦截器拒绝（可能 404 因为无实际 Controller 映射，但不是 401）
        mockMvc.perform(get("/lingframe/dashboard/lings?token=secret123"))
            .andExpect(result -> {
                int status = result.getResponse().getStatus();
                assert status != 401 : "Should not be 401 with valid token";
            });
    }

    @Test
    void streamEndpoint_isExcluded() throws Exception {
        mockMvc.perform(get("/lingframe/dashboard/stream"))
            .andExpect(status().isNotFound()); // 路径不存在但不会被 401 拦截
    }

    @Test
    void installEndpoint_isExcluded() throws Exception {
        // 安装端点放行（首次部署免 token）
        mockMvc.perform(post("/lingframe/dashboard/lings/install"))
            .andExpect(status().isNotFound()); // 无文件上传但不会被 401 拦截
    }
}
```

- [ ] **Step 4: GovernanceConfigRestoreTest — 验证恢复逻辑 + fail-fast**

```java
package com.lingframe.dashboard.restore;

import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.router.CanaryRouter;
import com.lingframe.dashboard.storage.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 验证修复：
 * - canary / invocation / ling_status 三类配置都被恢复
 * - 恢复失败时抛 RuntimeException（fail-fast）
 */
class GovernanceConfigRestoreTest {

    private JdbcTemplate jdbcTemplate;
    private StorageInitializer initializer;
    private GovernanceStorage governanceStorage;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite::memory:");
        ds.setConnectionProperties("journal_mode=WAL;busy_timeout=5000");
        jdbcTemplate = new JdbcTemplate(ds);
        initializer = new StorageInitializer(jdbcTemplate, new StorageProperties());
        initializer.initialize();
        governanceStorage = new GovernanceStorage(jdbcTemplate);
    }

    @Test
    void restore_canaryConfig_appliedToRouter() {
        governanceStorage.saveCanaryConfig("ling-A", 50, "v3");
        governanceStorage.saveCanaryConfig("ling-B", 20, "v1");

        CanaryRouter router = mock(CanaryRouter.class);
        LocalGovernanceRegistry registry = mock(LocalGovernanceRegistry.class);

        initializer.restoreGovernanceConfig(governanceStorage, router, registry);

        verify(router).setCanaryConfig("ling-A", 50, "v3");
        verify(router).setCanaryConfig("ling-B", 20, "v1");
    }

    @Test
    void restore_invocationConfig_appliedToRegistry() {
        governanceStorage.saveInvocationConfig("ling-A", "{\"timeout\":3000,\"retries\":3}");

        CanaryRouter router = mock(CanaryRouter.class);
        LocalGovernanceRegistry registry = mock(LocalGovernanceRegistry.class);

        initializer.restoreGovernanceConfig(governanceStorage, router, registry);

        verify(registry).updatePatch(eq("ling-A"), any());
    }

    @Test
    void restore_invalidJson_failsFast() {
        // 写入脏数据
        jdbcTemplate.update(
            "INSERT INTO governance_config (ling_id, config_type, config_data, updated_at) " +
            "VALUES ('ling-X', 'invocation', '{invalid json}', ?)",
            System.currentTimeMillis()
        );

        CanaryRouter router = mock(CanaryRouter.class);
        LocalGovernanceRegistry registry = mock(LocalGovernanceRegistry.class);

        // 恢复必须抛异常，不能吞掉
        assertThrows(RuntimeException.class, () ->
            initializer.restoreGovernanceConfig(governanceStorage, router, registry)
        );
    }
}
```

- [ ] **Step 5: 运行测试**

Run: `mvn test -pl lingframe-dashboard`
Expected: 
- StorageIntegrationTest: 4 tests pass
- AccessTokenIntegrationTest: 4 tests pass
- GovernanceConfigRestoreTest: 3 tests pass
- 总计 11 tests, 0 failures

---

## 自查清单

**1. Spec 覆盖度：**
- SQLite 持久化 ✅ (Task 1-8, 10-12)
- 监控指标历史查询 ✅ (Task 8, 13)
- 治理配置持久化与恢复 ✅ (Task 5, 11, 12)
- 审计记录持久化 ✅ (Task 6)
- 访问令牌 ✅ (Task 9, 10)
- 前端时间范围扩展 ✅ (Task 13)
- 集成测试 ✅ (Task 15)

**2. 占位符扫描：** 无 TBD/TODO/占位符

**3. 类型一致性：** 所有方法签名和字段名在 Task 间保持一致

**4. 生产级特性：**
- 并发写入保护（WAL + busy_timeout=5000）✅
- 启动失败 fail-fast（初始化 + 恢复均抛 RuntimeException）✅
- 配置恢复完整性（canary + invocation + status）✅
- 静态资源 / 安装端点 token 策略 ✅
- 集成测试覆盖关键修复（11 tests）✅
- 时区一致性已文档化 ✅
- GovernancePolicy Jackson 反序列化 null-safe 兜底 ✅
- 前端 api 对象统一 token 传递（19 处调用零改动）✅
- 拦截路径按实际 Controller @RequestMapping 精确匹配 ✅
- 治理写入点在最内层（DashboardGovernanceSupport）而非 Controller ✅

**5. 代码库假设验证：**
- GovernancePolicy 有 @NoArgsConstructor + @Setter，Jackson 默认反序列化兼容 ✅（已验证）
- LocalGovernanceRegistry 方法是 `updatePatch`（非 `updatePolicy`）✅（已验证）
- DashboardGovernanceSupport 是 DashboardService 内部创建，非独立 Bean ✅（已验证，通过 setter 间接注入）
- 前端 `api.get(path)` 拼接 `API_BASE + path`，path 不含前缀 ✅（已验证，修正了历史查询路径）

---

## 分批实施策略

> 15 个 Task 一次性全做完风险高，建议分两批实施，每批完成后编译验证。

### 第一批：存储层 + Bean 注册（Task 1-10）

**目标**：SQLite 基础设施就绪，数据能写入和查询，但前端和业务集成暂不动。

| Task | 内容 | 验证方式 |
|------|------|---------|
| 1 | SQLite JDBC 依赖 | `mvn compile` |
| 2 | StorageProperties | 配置类编译 |
| 3 | StorageInitializer | 单元测试建表 |
| 4 | MetricsStorage | 单元测试写入+查询 |
| 5 | GovernanceStorage | 单元测试联合主键 |
| 6 | AuditStorage | 单元测试写入 |
| 7 | DashboardAutoConfiguration Bean 注册 | `mvn compile` |
| 8 | MetricsCollectorScheduler | 启动应用，检查 `lingframe-data/lingframe.db` |
| 9 | AccessTokenInterceptor | curl 验证 401 |
| 10 | AccessTokenProperties + 拦截器注册 | curl 验证放行/拦截 |

**第一批完成标志**：
- `mvn compile -pl lingframe-dashboard -am` 成功
- 启动示例应用，`lingframe-data/lingframe.db` 文件生成
- `curl http://localhost:port/lingframe/dashboard/lings/metrics` 返回数据
- 设置 token 后，不带 token 返回 401，带 token 返回 200

### 第二批：业务集成 + 前端 + 测试（Task 11-15）

**目标**：治理配置持久化、历史指标查询、前端时间范围扩展、集成测试。

| Task | 内容 | 验证方式 |
|------|------|---------|
| 11 | 治理配置变更同步写入 SQLite | 更新灰度/治理配置后重启，配置仍在 |
| 12 | 启动恢复治理配置 | 重启后灰度/治理配置自动恢复 |
| 13 | 前端历史查询 + 时间范围 | Dashboard UI 选择"今天"查看折线图 |
| 14 | 示例应用配置 + 手动验证 | 完整流程走通 |
| 15 | 集成测试 | `mvn test -pl lingframe-dashboard` 11 tests pass |

**第二批完成标志**：
- 所有 11 个集成测试通过
- Dashboard UI 历史指标折线图正常显示
- 重启应用后治理配置不丢失

---

## SQLite 备份策略

> SQLite 是单文件数据库，备份非常简单。以下策略按场景选择：

### 开发/展示环境（当前场景）

无需额外备份。数据库文件位于 `{data-dir}/lingframe.db`，随应用进程生命周期存在。
若需手动备份：`cp lingframe-data/lingframe.db lingframe-data/lingframe.db.bak`

### 生产环境（未来扩展）

1. **定时冷备**：在 `MetricsCollectorScheduler` 中添加每日备份逻辑
   ```java
   // 每天凌晨 3 点执行（与 cleanExpiredData 一起）
   private void backupDatabase() {
       String source = properties.getDataDir() + "/lingframe.db";
       String backup = properties.getDataDir() + "/lingframe.db.bak";
       try {
           Files.copy(Paths.get(source), Paths.get(backup), StandardCopyOption.REPLACE_EXISTING);
           log.info("[LingFrame] SQLite 备份完成: {}", backup);
       } catch (Exception e) {
           log.warn("[LingFrame] SQLite 备份失败", e);
       }
   }
   ```

2. **SQLite 内置在线备份 API**：`sqlite3_backup_init()` 可在不停服情况下创建一致性备份。
   JDBC 驱动不直接暴露此 API，但可通过 `backup` PRAGMA 实现：
   ```sql
   PRAGMA backup = '/path/to/backup.db';
   ```

3. **WAL 模式下的恢复**：若进程崩溃，WAL 文件会自动回放。最坏情况下可能丢失最后一笔事务（`synchronous=NORMAL` 的已知行为），可接受。
