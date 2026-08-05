package com.lingframe.infra.storage.proxy;

import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Slf4j
public class LingPreparedStatementProxy implements PreparedStatement {

    private final PreparedStatement target;
    private final PermissionService permissionService;

    // 🔥 不再强引用长原始 SQL，避免大数据量下的 FullGC 风险
    // private final String sql;

    // 审计用的精简标识 (仅取前100字符)
    private final String sqlAuditSummary;

    // 预解析的结果
    private final AccessType preParsedAccessType;
    private final List<String> preParsedCapabilities;
    private final boolean preParsedParseable;
    // 预编译语句 SQL 涉及的可更新表集合，供 executeQuery 返回的 ResultSet 表级写治理
    private final Set<String> updatableTables;

    /**
     * 产生此 PreparedStatement 的 Connection 代理引用。
     * getConnection() 直接返回它，避免暴露原生 Connection 导致 SQL 治理被绕过。
     */
    private final LingConnectionProxy lingConnection;

    // SQL解析结果缓存 (LRU缓存)

    public LingPreparedStatementProxy(PreparedStatement target, PermissionService permissionService, String sql,
            LingConnectionProxy lingConnection) {
        this.target = target;
        this.permissionService = permissionService;
        this.lingConnection = lingConnection;

        // 生成审计短摘要，防止 OOM
        this.sqlAuditSummary = sql != null && sql.length() > 100
                ? sql.substring(0, 100) + "..."
                : sql;

        // 在构造时预解析SQL类型，完成后即扔掉对长 SQL 的强引用
        SqlPermissionSupport.SqlPermissionPlan plan = SqlParseCache.getOrAnalyze(LingCallContext.getLingId(), sql);
        this.preParsedAccessType = plan.getAccessType();
        this.preParsedCapabilities = plan.getCapabilities();
        this.preParsedParseable = plan.isParseable();
        // 预编译语句的 SQL 在 prepareStatement 时已确定，提取表名供 executeQuery 返回的 ResultSet 表级写治理
        this.updatableTables = SqlPermissionSupport.extractNormalizedTables(sql);
    }

    // --- 核心鉴权逻辑 ---
    private void checkPermission() throws SQLException {
        // 1. 获取当前调用者（业务灵元ID）
        // 这里依赖我们在 Runtime 层实现的 ThreadLocal Holder
        String callerLingId = LingCallContext.getLingId();
        if (callerLingId == null) {
            // 检查是否启用了灵核治理
            if (!SqlPermissionSupport.checkLingCoreGovernance(permissionService, sqlAuditSummary)) {
                // 灵核治理开启：拒绝无上下文的操作
                throw new SQLException("Access Denied: LINGCORE governance is enabled but no context provided.");
            }
            // 灵核治理关闭：默认放行 (LINGCORE Privilege)
            return;
        }

        // 2. 使用预解析的结果
        SqlPermissionSupport.ResolvedCapability resolvedCapability = SqlPermissionSupport.resolveCapability(
                permissionService,
                callerLingId,
                new SqlPermissionSupport.SqlPermissionPlan(preParsedAccessType, preParsedCapabilities, preParsedParseable));

        // 3. 上报审计 (异步)
        permissionService.audit(callerLingId,
                resolvedCapability.getCapability(),
                sqlAuditSummary,
                resolvedCapability.isAllowed());

        if (!resolvedCapability.isAllowed()) {
            throw new SQLException(new PermissionDeniedException(
                    "Ling [" + callerLingId + "] denied access to SQL. Summary: " + sqlAuditSummary));
        }
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        checkPermission();
        // 包装返回的 ResultSet，防止灵元通过可更新 ResultSet 绕过 SQL 治理
        // 预编译语句的 SQL 在 prepareStatement 时已确定，复用构造时提取的 updatableTables 做表级写治理；
        // 传 this 避免 getStatement() 泄露原生 Statement
        return new LingResultSetProxy(target.executeQuery(), permissionService, this, updatableTables);
    }

    @Override
    public int executeUpdate() throws SQLException {
        checkPermission();
        return target.executeUpdate();
    }

    @Override
    public boolean execute() throws SQLException {
        checkPermission();
        return target.execute();
    }

    @Override
    public void close() throws SQLException {
        target.close();
    }

    @Override
    public void setString(int parameterIndex, String x) throws SQLException {
        target.setString(parameterIndex, x);
    }

    @Override
    public void setInt(int parameterIndex, int x) throws SQLException {
        target.setInt(parameterIndex, x);
    }

    @Override
    public void setLong(int parameterIndex, long x) throws SQLException {
        target.setLong(parameterIndex, x);
    }

    @Override
    public void setObject(int parameterIndex, Object x, SQLType targetSqlType, int scaleOrLength) throws SQLException {
        target.setObject(parameterIndex, x, targetSqlType, scaleOrLength);
    }

    @Override
    public void setObject(int parameterIndex, Object x, SQLType targetSqlType) throws SQLException {
        target.setObject(parameterIndex, x, targetSqlType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isAssignableFrom(getClass())) {
            return (T) this;
        }
        // 拒绝暴露原生 PreparedStatement 实现，防止绕过 SQL 治理代理
        throw new SQLException("Cannot unwrap to " + iface.getName()
                + ": LingPreparedStatementProxy only exposes the PreparedStatement interface");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isAssignableFrom(getClass());
    }

    @Override
    public long executeLargeUpdate() throws SQLException {
        checkPermission();
        return target.executeLargeUpdate();
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        // 包装返回的 ResultSet，防止灵元通过可更新 ResultSet 绕过 SQL 治理
        // 传空集保持粗粒度；传 this 避免 getStatement() 泄露原生 Statement
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
        // PreparedStatement 禁止 addBatch(String sql)：预编译语句的 SQL 在 prepareStatement 时已校验，
        // 此处传入新 SQL 会绕过权限治理。应使用 addBatch() 配合 setXxx()。
        throw new SQLException("PreparedStatement does not support addBatch(String sql). Use addBatch() with setXxx().");
    }

    @Override
    public void clearBatch() throws SQLException {
        target.clearBatch();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        // 权限已在 addBatch() 时检查，此处无需重复检查，与 LingStatementProxy 策略一致
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
        // 传空集保持粗粒度；传 this 避免 getStatement() 泄露原生 Statement
        return new LingResultSetProxy(target.getGeneratedKeys(), permissionService, this, Collections.emptySet());
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        // PreparedStatement 禁止 executeUpdate(String sql, ...)：预编译语句的 SQL 在 prepareStatement 时已校验，
        // 此处传入新 SQL 会绕过权限治理。应使用 executeUpdate() 配合 setXxx()。
        throw new SQLException("PreparedStatement does not support executeUpdate(String sql, int). Use executeUpdate() with setXxx().");
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        throw new SQLException("PreparedStatement does not support executeUpdate(String sql, int[]). Use executeUpdate() with setXxx().");
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        throw new SQLException("PreparedStatement does not support executeUpdate(String sql, String[]). Use executeUpdate() with setXxx().");
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        // PreparedStatement 禁止 execute(String sql, ...)：预编译语句的 SQL 在 prepareStatement 时已校验，
        // 此处传入新 SQL 会绕过权限治理。应使用 execute() 配合 setXxx()。
        throw new SQLException("PreparedStatement does not support execute(String sql, int). Use execute() with setXxx().");
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        throw new SQLException("PreparedStatement does not support execute(String sql, int[]). Use execute() with setXxx().");
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        throw new SQLException("PreparedStatement does not support execute(String sql, String[]). Use execute() with setXxx().");
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        return target.getResultSetHoldability();
    }

    @Override
    public boolean isClosed() throws SQLException {
        return target.isClosed();
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
    public int executeUpdate(String sql) throws SQLException {
        // PreparedStatement 禁止 executeUpdate(String sql)：预编译语句的 SQL 在 prepareStatement 时已校验，
        // 此处传入新 SQL 会绕过权限治理。应使用 executeUpdate() 配合 setXxx()。
        throw new SQLException("PreparedStatement does not support executeUpdate(String sql). Use executeUpdate() with setXxx().");
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        // PreparedStatement 禁止 execute(String sql)：预编译语句的 SQL 在 prepareStatement 时已校验，
        // 此处传入新 SQL 会绕过权限治理。应使用 execute() 配合 setXxx()。
        throw new SQLException("PreparedStatement does not support execute(String sql). Use execute() with setXxx().");
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        // PreparedStatement 禁止 executeQuery(String sql)：预编译语句的 SQL 在 prepareStatement 时已校验，
        // 此处传入新 SQL 会绕过权限治理。应使用 executeQuery() 配合 setXxx()。
        throw new SQLException("PreparedStatement does not support executeQuery(String sql). Use executeQuery() with setXxx().");
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
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        target.setNull(parameterIndex, sqlType);
    }

    @Override
    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        target.setBoolean(parameterIndex, x);
    }

    @Override
    public void setByte(int parameterIndex, byte x) throws SQLException {
        target.setByte(parameterIndex, x);
    }

    @Override
    public void setShort(int parameterIndex, short x) throws SQLException {
        target.setShort(parameterIndex, x);
    }

    @Override
    public void setFloat(int parameterIndex, float x) throws SQLException {
        target.setFloat(parameterIndex, x);
    }

    @Override
    public void setDouble(int parameterIndex, double x) throws SQLException {
        target.setDouble(parameterIndex, x);
    }

    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        target.setBigDecimal(parameterIndex, x);
    }

    @Override
    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        target.setBytes(parameterIndex, x);
    }

    @Override
    public void setDate(int parameterIndex, Date x) throws SQLException {
        target.setDate(parameterIndex, x);
    }

    @Override
    public void setTime(int parameterIndex, Time x) throws SQLException {
        target.setTime(parameterIndex, x);
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
        target.setTimestamp(parameterIndex, x);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
        target.setAsciiStream(parameterIndex, x, length);
    }

    @Override
    public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
        target.setUnicodeStream(parameterIndex, x, length);
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
        target.setBinaryStream(parameterIndex, x, length);
    }

    @Override
    public void clearParameters() throws SQLException {
        target.clearParameters();
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        target.setObject(parameterIndex, x, targetSqlType);
    }

    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        target.setObject(parameterIndex, x);
    }

    @Override
    public void addBatch() throws SQLException {
        checkPermission();
        target.addBatch();
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
        target.setCharacterStream(parameterIndex, reader, length);
    }

    @Override
    public void setRef(int parameterIndex, Ref x) throws SQLException {
        target.setRef(parameterIndex, x);
    }

    @Override
    public void setBlob(int parameterIndex, Blob x) throws SQLException {
        target.setBlob(parameterIndex, x);
    }

    @Override
    public void setClob(int parameterIndex, Clob x) throws SQLException {
        target.setClob(parameterIndex, x);
    }

    @Override
    public void setArray(int parameterIndex, Array x) throws SQLException {
        target.setArray(parameterIndex, x);
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        return target.getMetaData();
    }

    @Override
    public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
        target.setDate(parameterIndex, x, cal);
    }

    @Override
    public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
        target.setTime(parameterIndex, x, cal);
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
        target.setTimestamp(parameterIndex, x, cal);
    }

    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        target.setNull(parameterIndex, sqlType, typeName);
    }

    @Override
    public void setURL(int parameterIndex, URL x) throws SQLException {
        target.setURL(parameterIndex, x);
    }

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        return target.getParameterMetaData();
    }

    @Override
    public void setRowId(int parameterIndex, RowId x) throws SQLException {
        target.setRowId(parameterIndex, x);
    }

    @Override
    public void setNString(int parameterIndex, String value) throws SQLException {
        target.setNString(parameterIndex, value);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
        target.setNCharacterStream(parameterIndex, value, length);
    }

    @Override
    public void setNClob(int parameterIndex, NClob value) throws SQLException {
        target.setNClob(parameterIndex, value);
    }

    @Override
    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        target.setClob(parameterIndex, reader, length);
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
        target.setBlob(parameterIndex, inputStream, length);
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        target.setNClob(parameterIndex, reader, length);
    }

    @Override
    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
        target.setSQLXML(parameterIndex, xmlObject);
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        target.setObject(parameterIndex, x, targetSqlType, scaleOrLength);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
        target.setAsciiStream(parameterIndex, x, length);
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
        target.setBinaryStream(parameterIndex, x, length);
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
        target.setCharacterStream(parameterIndex, reader, length);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
        target.setAsciiStream(parameterIndex, x);
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
        target.setBinaryStream(parameterIndex, x);
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        target.setCharacterStream(parameterIndex, reader);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
        target.setNCharacterStream(parameterIndex, value);
    }

    @Override
    public void setClob(int parameterIndex, Reader reader) throws SQLException {
        target.setClob(parameterIndex, reader);
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        target.setBlob(parameterIndex, inputStream);
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        target.setNClob(parameterIndex, reader);
    }
}
