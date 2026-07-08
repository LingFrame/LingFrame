package com.lingframe.infra.storage.proxy;

import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * 数据库连接代理
 * 职责：劫持 createStatement / prepareStatement 等方法，
 * 驱动后续的 SQL 拦截与细粒度权限校验；
 * 并对事务边界方法（commit/rollback/setAutoCommit）施加治理。
 */
@Slf4j
@RequiredArgsConstructor
public class LingConnectionProxy implements Connection {

    private static final String TRANSACTION_CAPABILITY = "storage:sql:transaction";

    private final Connection target;
    private final PermissionService permissionService;

    /**
     * 事务操作的治理检查。
     * 与 SQL 执行检查对齐：无 LingContext 时按灵核治理开关决定放行/拒绝。
     */
    private void checkTransactionPermission(String operation) throws SQLException {
        String callerLingId = LingCallContext.getLingId();
        if (callerLingId == null) {
            if (permissionService.isLingCoreGovernanceEnabled()) {
                log.error("Security Alert: transaction [{}] without LingContext (LINGCORE governance ENABLED)",
                        operation);
                throw new SQLException("Access Denied: LINGCORE governance is enabled but no context provided for transaction: "
                        + operation);
            }
            return;
        }
        boolean allowed = permissionService.isAllowed(callerLingId, TRANSACTION_CAPABILITY, AccessType.WRITE);
        permissionService.audit(callerLingId, TRANSACTION_CAPABILITY, "transaction:" + operation, allowed);
        if (!allowed) {
            throw new SQLException(new PermissionDeniedException(callerLingId, TRANSACTION_CAPABILITY));
        }
    }

    @Override
    public Statement createStatement() throws SQLException {
        return new LingStatementProxy(target.createStatement(), permissionService);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        // PreparedStatement 在创建时就确定了 SQL，可以在这里提前拦截
        return new LingPreparedStatementProxy(target.prepareStatement(sql), permissionService, sql);
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
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        // 如果请求的是 Connection 接口或代理本身，返回代理本身
        if (iface.isAssignableFrom(getClass())) {
            return (T) this;
        }
        // 拒绝暴露原生 Connection 实现，防止绕过事务治理代理
        throw new SQLException("Cannot unwrap to " + iface.getName()
                + ": LingConnectionProxy only exposes the Connection interface");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        // 代理本身实现了 Connection 接口
        return iface.isAssignableFrom(getClass());
    }

    // ...
    @Override
    public void commit() throws SQLException {
        checkTransactionPermission("commit");
        target.commit();
    }

    @Override
    public void rollback() throws SQLException {
        checkTransactionPermission("rollback");
        target.rollback();
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        checkTransactionPermission("setAutoCommit");
        target.setAutoCommit(autoCommit);
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        return target.getAutoCommit();
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        return target.getMetaData();
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        target.setReadOnly(readOnly);
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return target.isReadOnly();
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        target.setCatalog(catalog);
    }

    @Override
    public String getCatalog() throws SQLException {
        return target.getCatalog();
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        target.setTransactionIsolation(level);
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        return target.getTransactionIsolation();
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
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        return new LingStatementProxy(target.createStatement(resultSetType, resultSetConcurrency), permissionService);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
            throws SQLException {
        return new LingPreparedStatementProxy(target.prepareStatement(sql, resultSetType, resultSetConcurrency),
                permissionService, sql);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        // return target.prepareCall(sql, resultSetType, resultSetConcurrency);
        throw new SQLFeatureNotSupportedException("CallableStatement is disabled.");
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        return target.getTypeMap();
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        target.setTypeMap(map);
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        target.setHoldability(holdability);
    }

    @Override
    public int getHoldability() throws SQLException {
        return target.getHoldability();
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        return target.setSavepoint();
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        return target.setSavepoint(name);
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        checkTransactionPermission("rollback(savepoint)");
        target.rollback(savepoint);
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        target.releaseSavepoint(savepoint);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        return new LingStatementProxy(target.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability),
                permissionService);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
            int resultSetHoldability) throws SQLException {
        return new LingPreparedStatementProxy(
                target.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability),
                permissionService, sql);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency,
            int resultSetHoldability) throws SQLException {
        // return target.prepareCall(sql, resultSetType, resultSetConcurrency,
        // resultSetHoldability);
        throw new SQLFeatureNotSupportedException("CallableStatement is disabled.");
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        return new LingPreparedStatementProxy(target.prepareStatement(sql, autoGeneratedKeys), permissionService, sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        return new LingPreparedStatementProxy(target.prepareStatement(sql, columnIndexes), permissionService, sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        return new LingPreparedStatementProxy(target.prepareStatement(sql, columnNames), permissionService, sql);
    }

    @Override
    public Clob createClob() throws SQLException {
        return target.createClob();
    }

    @Override
    public Blob createBlob() throws SQLException {
        return target.createBlob();
    }

    @Override
    public NClob createNClob() throws SQLException {
        return target.createNClob();
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        return target.createSQLXML();
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        return target.isValid(timeout);
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        target.setClientInfo(name, value);
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        target.setClientInfo(properties);
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        return target.getClientInfo(name);
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        return target.getClientInfo();
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        return target.createArrayOf(typeName, elements);
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        return target.createStruct(typeName, attributes);
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        target.setSchema(schema);
    }

    @Override
    public String getSchema() throws SQLException {
        return target.getSchema();
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        target.abort(executor);
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        target.setNetworkTimeout(executor, milliseconds);
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        return target.getNetworkTimeout();
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        // return target.prepareCall(sql);
        throw new SQLFeatureNotSupportedException(
                "CallableStatement (Stored Procedures) is disabled in LingFrame Security Mode to prevent privilege escalation.");
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        return target.nativeSQL(sql);
    }
}