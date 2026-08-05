package com.lingframe.infra.storage.proxy;

import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.PermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.Collections;
import java.util.Set;

@Slf4j
@RequiredArgsConstructor
public class LingStatementProxy implements Statement {

    private final Statement target;
    private final PermissionService permissionService;
    /**
     * 产生此 Statement 的 Connection 代理引用。
     * getConnection() 直接返回它，避免暴露原生 Connection 导致 SQL 治理被绕过。
     */
    private final LingConnectionProxy lingConnection;

    // SQL解析结果缓存 (LRU缓存)

    // --- 鉴权逻辑：与 PreparedStatement 类似，只是 SQL 是参数传进来的 ---
    private void checkPermission(String sql) throws SQLException {
        String callerLingId = LingCallContext.getLingId();
        // 无上下文：灵核操作
        if (callerLingId == null) {
            // 检查是否启用了灵核治理
            if (!SqlPermissionSupport.checkLingCoreGovernance(permissionService, sql)) {
                // 灵核治理开启：拒绝无上下文的操作
                throw new SQLException("Access Denied: LINGCORE governance is enabled but no context provided.");
            }
            // 灵核治理关闭：默认放行 (LINGCORE Privilege)
            return;
        }

        SqlPermissionSupport.SqlPermissionPlan plan = SqlParseCache.getOrAnalyze(LingCallContext.getLingId(), sql);
        SqlPermissionSupport.ResolvedCapability resolvedCapability = SqlPermissionSupport.resolveCapability(
                permissionService,
                callerLingId,
                plan);
        permissionService.audit(callerLingId, resolvedCapability.getCapability(), sql, resolvedCapability.isAllowed());

        if (!resolvedCapability.isAllowed()) {
            throw new SQLException(new PermissionDeniedException("Access Denied: " + sqlAuditSummary(sql)));
        }
    }

    /**
     * 生成审计/异常用的脱敏 SQL 摘要：截断前 100 字符，避免完整 SQL 进入异常栈。
     */
    private static String sqlAuditSummary(String sql) {
        if (sql == null) {
            return null;
        }
        return sql.length() > 100 ? sql.substring(0, 100) + "..." : sql;
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        checkPermission(sql);
        // 提取 SQL 涉及的表集合，传给 ResultSet 用于表级写治理（A10）
        Set<String> updatableTables = SqlPermissionSupport.extractNormalizedTables(sql);
        // 包装返回的 ResultSet，防止灵元通过可更新 ResultSet 绕过 SQL 治理
        // 传入 this 作为 proxyStatement，避免 getStatement() 泄露原生 Statement（S2）
        return new LingResultSetProxy(target.executeQuery(sql), permissionService, this, updatableTables);
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        checkPermission(sql);
        return target.executeUpdate(sql);
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        checkPermission(sql);
        return target.execute(sql);
    }

    @Override
    public void close() throws SQLException {
        target.close();
    }

    @Override
    public boolean isClosed() throws SQLException {
        return target.isClosed();
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        return target.getMaxFieldSize();
    }

    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        target.setMaxFieldSize(max);
    }

    @Override
    public int getMaxRows() throws SQLException {
        return target.getMaxRows();
    }

    @Override
    public void setMaxRows(int max) throws SQLException {
        target.setMaxRows(max);
    }

    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        target.setEscapeProcessing(enable);
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        return target.getQueryTimeout();
    }

    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        target.setQueryTimeout(seconds);
    }

    @Override
    public void cancel() throws SQLException {
        target.cancel();
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return target.getWarnings();
    }

    @Override
    public void clearWarnings() throws SQLException {
        target.clearWarnings();
    }

    @Override
    public void setCursorName(String name) throws SQLException {
        target.setCursorName(name);
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        // 包装返回的 ResultSet，防止灵元通过可更新 ResultSet 绕过 SQL 治理
        // getResultSet 无对应 SQL 文本，传空集保持粗粒度；传 this 避免 getStatement() 泄露原生 Statement
        return new LingResultSetProxy(target.getResultSet(), permissionService, this, Collections.emptySet());
    }

    @Override
    public int getUpdateCount() throws SQLException {
        return target.getUpdateCount();
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        return target.getMoreResults();
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        target.setFetchDirection(direction);
    }

    @Override
    public int getFetchDirection() throws SQLException {
        return target.getFetchDirection();
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        target.setFetchSize(rows);
    }

    @Override
    public int getFetchSize() throws SQLException {
        return target.getFetchSize();
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        return target.getResultSetConcurrency();
    }

    @Override
    public int getResultSetType() throws SQLException {
        return target.getResultSetType();
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        // 【关键】在添加到批处理之前，必须检查权限
        checkPermission(sql);
        target.addBatch(sql);
    }

    @Override
    public void clearBatch() throws SQLException {
        target.clearBatch();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        // 权限已在 addBatch(sql) 时逐条检查，此处无需重复检查
        // Statement.addBatch(sql) 必须传入 SQL，已被拦截
        return target.executeBatch();
    }

    @Override
    public Connection getConnection() throws SQLException {
        // 返回 Connection 代理，避免暴露原生 Connection 导致 SQL 治理被绕过
        return lingConnection;
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        return target.getMoreResults(current);
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        // 包装返回的 ResultSet，防止灵元通过可更新 ResultSet 绕过 SQL 治理
        // getGeneratedKeys 无对应 SQL 文本，传空集保持粗粒度；传 this 避免 getStatement() 泄露原生 Statement
        return new LingResultSetProxy(target.getGeneratedKeys(), permissionService, this, Collections.emptySet());
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        checkPermission(sql);
        return target.executeUpdate(sql, autoGeneratedKeys);
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        checkPermission(sql);
        return target.executeUpdate(sql, columnIndexes);
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        checkPermission(sql);
        return target.executeUpdate(sql, columnNames);
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        checkPermission(sql);
        return target.execute(sql, autoGeneratedKeys);
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        checkPermission(sql);
        return target.execute(sql, columnIndexes);
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        checkPermission(sql);
        return target.execute(sql, columnNames);
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        return target.getResultSetHoldability();
    }

    @Override
    public void setPoolable(boolean poolable) throws SQLException {
        target.setPoolable(poolable);
    }

    @Override
    public boolean isPoolable() throws SQLException {
        return target.isPoolable();
    }

    @Override
    public void closeOnCompletion() throws SQLException {
        target.closeOnCompletion();
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        return target.isCloseOnCompletion();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isAssignableFrom(getClass())) {
            return (T) this;
        }
        // 拒绝暴露原生 Statement 实现，防止绕过 SQL 治理代理
        throw new SQLException("Cannot unwrap to " + iface.getName()
                + ": LingStatementProxy only exposes the Statement interface");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isAssignableFrom(getClass());
    }
}
