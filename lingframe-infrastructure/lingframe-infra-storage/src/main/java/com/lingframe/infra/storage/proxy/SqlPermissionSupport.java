package com.lingframe.infra.storage.proxy;

import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * SQL 权限辅助工具。
 * 第一阶段先支持“表级 capability 优先，通用 capability 兜底”的兼容式治理收敛。
 */
@Slf4j
final class SqlPermissionSupport {

    static final String GENERIC_SQL_CAPABILITY = "storage:sql";

    private SqlPermissionSupport() {
    }

    static SqlPermissionPlan analyze(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return new SqlPermissionPlan(AccessType.EXECUTE, List.of());
        }

        try {
            Statement statement = CCJSqlParserUtil.parse(sql.trim());
            return new SqlPermissionPlan(resolveAccessType(statement), extractTableCapabilities(statement));
        } catch (JSQLParserException e) {
            // 解析失败时维持保守策略，仍回退到通用 capability + EXECUTE 权限要求。
            log.error("[SQL Parse Error] Rejecting ambiguous SQL: {}", sql);
            return new SqlPermissionPlan(AccessType.EXECUTE, List.of());
        }
    }

    static ResolvedCapability resolveCapability(PermissionService permissionService,
                                                String callerLingId,
                                                SqlPermissionPlan plan) {
        Objects.requireNonNull(permissionService, "permissionService");

        List<String> specificCapabilities = plan.getCapabilities();
        if (!specificCapabilities.isEmpty()) {
            boolean allAllowed = true;
            for (String capability : specificCapabilities) {
                if (!permissionService.isAllowed(callerLingId, capability, plan.getAccessType())) {
                    allAllowed = false;
                }
            }
            if (allAllowed) {
                return new ResolvedCapability(joinCapabilities(specificCapabilities), true);
            }
        }

        boolean allowed = permissionService.isAllowed(callerLingId, GENERIC_SQL_CAPABILITY, plan.getAccessType());
        return new ResolvedCapability(GENERIC_SQL_CAPABILITY, allowed);
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

    private static List<String> extractTableCapabilities(Statement statement) {
        TablesNamesFinder finder = new TablesNamesFinder();
        Set<String> capabilities = new LinkedHashSet<>();
        for (String tableName : finder.getTableList(statement)) {
            String normalized = normalizeTableName(tableName);
            if (normalized == null) {
                continue;
            }
            capabilities.add("storage:sql:table:" + normalized);
            int separator = normalized.lastIndexOf('.');
            if (separator > 0 && separator < normalized.length() - 1) {
                capabilities.add("storage:sql:table:" + normalized.substring(separator + 1));
            }
        }
        return new ArrayList<>(capabilities);
    }

    private static String normalizeTableName(String tableName) {
        if (tableName == null) {
            return null;
        }
        String normalized = tableName.trim();
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

        SqlPermissionPlan(AccessType accessType, List<String> capabilities) {
            this.accessType = accessType;
            this.capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        }

        AccessType getAccessType() {
            return accessType;
        }

        List<String> getCapabilities() {
            return capabilities;
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
