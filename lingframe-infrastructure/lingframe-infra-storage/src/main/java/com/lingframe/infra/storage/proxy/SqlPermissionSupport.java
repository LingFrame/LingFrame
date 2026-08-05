package com.lingframe.infra.storage.proxy;

import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.*;

/**
 * SQL 权限辅助工具。
 * <p>
 * 权限模型采用 AND 语义：
 * <ul>
 *   <li>{@code storage:sql}（generic）是"能否使用 SQL 能力"的总开关</li>
 *   <li>{@code storage:sql:table:X}（表级）是"能否操作表 X"的细粒度授权</li>
 *   <li>两者必须同时满足：generic 通过 AND 表级通过 → 允许</li>
 * </ul>
 * 显式启用原则：灵元配置了任何表级规则即视为启用表级白名单模式，
 * 所有表操作都必须有显式表级权限；未配置表级规则则只看 generic 总开关。
 * <p>
 * 读写表区分：写表（INSERT/UPDATE/DELETE 主表、DROP/TRUNCATE 等DDL 表）
 * capability 不带后缀，要求 WRITE；读表（SELECT 表、INSERT...SELECT 子查询表）
 * capability 带 {@value #READ_CAPABILITY_SUFFIX} 后缀，要求 READ。
 * 这样 {@code INSERT INTO A SELECT FROM B} 只要求 B 的 READ 权限，避免过度限制。
 * <p>
 * 解析失败 fail-closed：无法判断 SQL 能力时直接拒绝。
 */
@Slf4j
final class SqlPermissionSupport {

    static final String GENERIC_SQL_CAPABILITY = "storage:sql";
    static final String TABLE_CAPABILITY_PREFIX = "storage:sql:table:";
    // 读表 capability 后缀：用于区分读写表，避免对 SELECT 子查询表要求 WRITE
    private static final String READ_CAPABILITY_SUFFIX = ":read";

    private SqlPermissionSupport() {
    }

    /**
     * 检查无 LingContext 时的灵核治理：治理开启则拒绝（fail-closed），治理关闭则放行。
     *
     * @param permissionService 权限服务
     * @param auditSummary 审计摘要（SQL 或操作描述）
     * @return true=放行（治理关闭），false=拒绝（治理开启，已记录日志）
     */
    static boolean checkLingCoreGovernance(PermissionService permissionService, String auditSummary) {
        if (permissionService.isLingCoreGovernanceEnabled()) {
            log.error("Security Alert: operation without LingContext (LINGCORE governance ENABLED). Summary: {}",
                    auditSummary);
            return false;
        }
        log.debug("Operation without LingContext (LINGCORE governance disabled). ALLOWED. Summary: {}", auditSummary);
        return true;
    }

    static SqlPermissionPlan analyze(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            // 空 SQL 视为可解析，返回 EXECUTE + 无表
            return new SqlPermissionPlan(AccessType.EXECUTE, Collections.emptyList(), true);
        }

        try {
            Statement statement = CCJSqlParserUtil.parse(sql.trim());
            return new SqlPermissionPlan(resolveAccessType(statement), extractTableCapabilities(statement), true);
        } catch (JSQLParserException e) {
            // 解析失败 fail-closed：无法判断能力即拒绝
            log.warn("[SQL] Failed to parse SQL, access denied (fail-closed): {}", sql);
            return new SqlPermissionPlan(AccessType.EXECUTE, Collections.emptyList(), false);
        }
    }

    static ResolvedCapability resolveCapability(PermissionService permissionService,
                                                String callerLingId,
                                                SqlPermissionPlan plan) {
        Objects.requireNonNull(permissionService, "permissionService");

        // 解析失败 fail-closed：无法判断能力即拒绝
        if (!plan.isParseable()) {
            return new ResolvedCapability(GENERIC_SQL_CAPABILITY, false);
        }

        // 第一道：generic 总开关必须通过（fail-closed，无权限即拒绝）
        if (!permissionService.isAllowed(callerLingId, GENERIC_SQL_CAPABILITY, plan.getAccessType())) {
            return new ResolvedCapability(GENERIC_SQL_CAPABILITY, false);
        }

        // 第二道：若灵元显式启用了表级治理，则表级白名单必须通过（AND 语义）
        List<String> tableCapabilities = plan.getCapabilities();
        if (permissionService.hasCapabilityPrefix(callerLingId, TABLE_CAPABILITY_PREFIX)) {
            // 启用了表级治理但本 SQL 无表（如 SELECT 1），拒绝
            if (tableCapabilities.isEmpty()) {
                return new ResolvedCapability(GENERIC_SQL_CAPABILITY, false);
            }
            for (String tableCap : tableCapabilities) {
                // 带 :read 后缀的是读表（要求 READ），否则是写表（要求 WRITE）
                AccessType required;
                String capToCheck;
                if (tableCap.endsWith(READ_CAPABILITY_SUFFIX)) {
                    required = AccessType.READ;
                    capToCheck = tableCap.substring(0, tableCap.length() - READ_CAPABILITY_SUFFIX.length());
                } else {
                    required = AccessType.WRITE;
                    capToCheck = tableCap;
                }
                if (!permissionService.isAllowed(callerLingId, capToCheck, required)) {
                    // 返回 capToCheck（无 :read 后缀），保持审计 capability 与实际校验的权限 capability 一致
                    return new ResolvedCapability(capToCheck, false);
                }
            }
            // 所有表级权限通过
            return new ResolvedCapability(joinCapabilities(tableCapabilities), true);
        }

        // 未启用表级治理：只看 generic 总开关，通过
        return new ResolvedCapability(GENERIC_SQL_CAPABILITY, true);
    }

    private static AccessType resolveAccessType(Statement statement) {
        if (statement instanceof Select) {
            return AccessType.READ;
        }
        if (statement instanceof Insert || statement instanceof Update || statement instanceof Delete) {
            return AccessType.WRITE;
        }
        return AccessType.EXECUTE;
    }

    /**
     * 提取表级 capability，区分读表和写表。
     * <ul>
     *   <li>写表：INSERT/UPDATE/DELETE 的主表、DDL（DROP/TRUNCATE 等）涉及的表
     *       → capability 不带后缀，要求 WRITE</li>
     *   <li>读表：SELECT 的表、INSERT INTO A SELECT FROM B 的子查询表
     *       → capability 带 {@value #READ_CAPABILITY_SUFFIX} 后缀，要求 READ</li>
     * </ul>
     * 写表之外的其他表（如 INSERT...SELECT 子查询表、UPDATE 多表 join 的从表）
     * 自动归入读表，避免过度限制。
     */
    private static List<String> extractTableCapabilities(Statement statement) {
        TablesNamesFinder finder = new TablesNamesFinder();
        List<String> allTables = finder.getTableList(statement);

        // 归一化所有表名，保持顺序
        LinkedHashSet<String> allNormalized = new LinkedHashSet<>();
        for (String t : allTables) {
            String n = normalizeTableName(t);
            if (n != null) {
                allNormalized.add(n);
            }
        }

        // 按 Statement 类型分发：区分写表和读表
        LinkedHashSet<String> writeTables = new LinkedHashSet<>();
        LinkedHashSet<String> readTables = new LinkedHashSet<>();
        extractTablesByRole(statement, allNormalized, writeTables, readTables);

        LinkedHashSet<String> capabilities = new LinkedHashSet<>();
        for (String t : writeTables) {
            addTableCapability(capabilities, t, false);
        }
        for (String t : readTables) {
            addTableCapability(capabilities, t, true);
        }
        return new ArrayList<>(capabilities);
    }

    /**
     * 提取 SQL 涉及的全部表名（归一化为小写 short form，无 schema 前缀）。
     * <p>
     * 复用 {@link TablesNamesFinder} 与 {@link #normalizeTableName(String)}，
     * 用于 ResultSet 表级治理场景：可更新 ResultSet 的写操作需校验行所属表的 WRITE 权限。
     * 不区分读写角色（统一返回全部表），由调用方按 WRITE 校验。
     * <p>
     * 解析失败返回空集，调用方据此回退到粗粒度校验。
     *
     * @param sql SQL 语句
     * @return 归一化小写 short form 表名集合，解析失败或无表时为空集
     */
    static Set<String> extractNormalizedTables(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return Collections.emptySet();
        }
        try {
            Statement statement = CCJSqlParserUtil.parse(sql.trim());
            TablesNamesFinder finder = new TablesNamesFinder();
            List<String> allTables = finder.getTableList(statement);
            Set<String> normalized = new LinkedHashSet<>();
            for (String t : allTables) {
                String n = normalizeTableName(t);
                if (n == null) {
                    continue;
                }
                // 返回 short form（去掉 schema 前缀），与表级 capability 的 short form 对齐
                int separator = n.lastIndexOf('.');
                if (separator > 0 && separator < n.length() - 1) {
                    normalized.add(n.substring(separator + 1));
                } else {
                    normalized.add(n);
                }
            }
            return normalized;
        } catch (JSQLParserException e) {
            // 解析失败返回空集，调用方回退到粗粒度校验
            log.warn("[SQL] Failed to extract tables for ResultSet governance (fallback to coarse-grained): {}", sql);
            return Collections.emptySet();
        }
    }

    /**
     * 按 Statement 类型分发：提取写表和读表。
     * 写表为主操作目标表，读表为辅助查询表（子查询、JOIN 从表等）。
     */
    private static void extractTablesByRole(Statement statement,
                                            LinkedHashSet<String> allTables,
                                            Set<String> writeTables,
                                            Set<String> readTables) {
        if (statement instanceof Select) {
            // SELECT: 所有表都是读表
            readTables.addAll(allTables);
            return;
        }
        if (statement instanceof Insert) {
            collectWriteTable(((Insert) statement).getTable(), writeTables);
        } else if (statement instanceof Update) {
            // 多表 UPDATE（如 UPDATE t1 JOIN t2 SET t2.x=1）：主表与 JOIN 从表均为写表
            // JSqlParser 4.6 把 UPDATE...JOIN 的 JOIN 存在 startJoins 字段（getJoins() 对
            // UPDATE 始终返回 null），两者都检查以覆盖不同语法变体
            Update update = (Update) statement;
            collectWriteTable(update.getTable(), writeTables);
            collectJoinWriteTables(update.getStartJoins(), writeTables);
            collectJoinWriteTables(update.getJoins(), writeTables);
        } else if (statement instanceof Delete) {
            Delete delete = (Delete) statement;
            // 单表 DELETE 目标（DELETE FROM t WHERE ...）
            collectWriteTable(delete.getTable(), writeTables);
            // 多表 DELETE 目标（DELETE t1, t2 FROM ...）：均为写表
            // JSqlParser 4.6 Delete.getTables() 返回 DELETE 目标引用，可能为别名
            // （如 DELETE u FROM users u ... 返回 Table(name="u")），需解析别名到真实表名，
            // 否则生成 storage:sql:table:u 幽灵 capability，导致表级治理误拒
            Map<String, String> aliasToReal = buildAliasMap(delete.getTable(), delete.getJoins());
            List<Table> deleteTables = delete.getTables();
            if (deleteTables != null) {
                for (Table t : deleteTables) {
                    String name = normalizeTableName(t.getName());
                    if (name == null) {
                        continue;
                    }
                    String real = aliasToReal.get(name);
                    writeTables.add(real != null ? real : name);
                }
            }
            // 注意：DELETE 的 JOIN 从表与 USING 表用于行定位（读表），
            // 不归入 writeTables，会通过下方 allTables - writeTables 自动归入 readTables
        } else {
            // DDL（Drop/Truncate/Alter/Create 等）：所有表视为写表
            writeTables.addAll(allTables);
            return;
        }
        // 写表之外的所有表为读表（如 INSERT INTO A SELECT FROM B 中的 B、
        // UPDATE...FROM 中的 fromItem 表）
        for (String t : allTables) {
            if (!writeTables.contains(t)) {
                readTables.add(t);
            }
        }
    }

    private static void collectWriteTable(Table table, Set<String> writeTables) {
        if (table == null) {
            return;
        }
        String n = normalizeTableName(table.getName());
        if (n != null) {
            writeTables.add(n);
        }
    }

    /**
     * 收集 JOIN 从表到写表集合（用于多表 UPDATE/DELETE 的 JOIN 从表归入写表）。
     * JOIN 右侧若为普通表则归入写表；子查询等非 Table 从项忽略。
     */
    private static void collectJoinWriteTables(List<Join> joins, Set<String> writeTables) {
        if (joins == null) {
            return;
        }
        for (Join join : joins) {
            FromItem rightItem = join.getRightItem();
            if (rightItem instanceof Table) {
                collectWriteTable((Table) rightItem, writeTables);
            }
        }
    }

    /**
     * 构建 别名→真实表名 映射（均归一化小写），用于解析 DELETE 多表目标的别名引用。
     * 来源：FROM 主表 + JOIN 从表的 alias 字段。
     */
    private static Map<String, String> buildAliasMap(Table fromTable, List<Join> joins) {
        Map<String, String> aliasToReal = new HashMap<>();
        collectAlias(fromTable, aliasToReal);
        if (joins != null) {
            for (Join join : joins) {
                FromItem rightItem = join.getRightItem();
                if (rightItem instanceof Table) {
                    collectAlias((Table) rightItem, aliasToReal);
                }
            }
        }
        return aliasToReal;
    }

    private static void collectAlias(Table table, Map<String, String> aliasToReal) {
        if (table == null) {
            return;
        }
        String real = normalizeTableName(table.getName());
        if (real == null) {
            return;
        }
        Alias aliasObj = table.getAlias();
        if (aliasObj != null) {
            String alias = normalizeTableName(aliasObj.getName());
            if (alias != null) {
                aliasToReal.put(alias, real);
            }
        }
    }

    private static void addTableCapability(Set<String> capabilities, String normalized, boolean readOnly) {
        String suffix = readOnly ? READ_CAPABILITY_SUFFIX : "";
        capabilities.add(TABLE_CAPABILITY_PREFIX + normalized + suffix);
        // 同时添加 short form（去掉 schema 前缀），与原行为保持一致
        int separator = normalized.lastIndexOf('.');
        if (separator > 0 && separator < normalized.length() - 1) {
            capabilities.add(TABLE_CAPABILITY_PREFIX + normalized.substring(separator + 1) + suffix);
        }
    }

    private static String normalizeTableName(String tableName) {
        if (tableName == null) {
            return null;
        }
        // 统一转小写，确保 TABLE_A 与 table_a 权限等价
        // 已知限制：对带引号的大小写敏感标识符（如 PostgreSQL "MyTable"）不适用，
        // 此类标识符在 JSqlParser 解析后可能保留原始大小写，归一化会破坏其语义
        String normalized = tableName.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized;
    }

    private static String joinCapabilities(List<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return GENERIC_SQL_CAPABILITY;
        }
        if (capabilities.size() == 1) {
            return capabilities.get(0);
        }
        return String.join(", ", capabilities);
    }

    static final class SqlPermissionPlan {
        private final AccessType accessType;
        private final List<String> capabilities;
        private final boolean parseable;

        SqlPermissionPlan(AccessType accessType, List<String> capabilities) {
            this(accessType, capabilities, true);
        }

        SqlPermissionPlan(AccessType accessType, List<String> capabilities, boolean parseable) {
            this.accessType = accessType;
            this.capabilities = capabilities == null ? Collections.emptyList() : Collections.unmodifiableList(capabilities);
            this.parseable = parseable;
        }

        AccessType getAccessType() {
            return accessType;
        }

        List<String> getCapabilities() {
            return capabilities;
        }

        boolean isParseable() {
            return parseable;
        }
    }

    static final class ResolvedCapability {
        private final String capability;
        private final boolean allowed;

        ResolvedCapability(String capability, boolean allowed) {
            this.capability = capability;
            this.allowed = allowed;
        }

        String getCapability() {
            return capability;
        }

        boolean isAllowed() {
            return allowed;
        }
    }
}
