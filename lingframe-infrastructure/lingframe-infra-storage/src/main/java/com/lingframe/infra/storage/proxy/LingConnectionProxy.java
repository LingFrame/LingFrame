package com.lingframe.infra.storage.proxy;

import com.lingframe.api.security.PermissionService;
import lombok.RequiredArgsConstructor;

import java.sql.*;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * 连接代理：劫持 createStatement / prepareStatement
 */
@RequiredArgsConstructor
public class LingConnectionProxy implements Connection {

    private final Connection target;
    private final PermissionService permissionService;

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
        // 如果请求的是 Connection 接口，返回代理本身
        if (iface.isAssignableFrom(getClass())) {
            return (T) this;
        }
        // 如果请求的是 LingConnectionProxy 本身
        if (iface.isAssignableFrom(LingConnectionProxy.class)) {
            return (T) this;
        }
        // 其他情况委托给 target（可能是特定数据库驱动的接口）
        // 注意：这里可能暴露原始对象，但某些 ORM 框架需要此功能
        return target.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        // 代理本身实现了 Connection 接口
        if (iface.isAssignableFrom(getClass())) {
            return true;
        }
        return target.isWrapperFor(iface);
    }

    // ...
    @Override
    public void commit() throws SQLException {
        target.commit();
    }

    @Override
    public void rollback() throws SQLException {
        target.rollback();
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        target.setAutoCommit(autoCommit);
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        return target.getAutoCommit();
    }

    // ... 还有很多，务必补全
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