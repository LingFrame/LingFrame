package com.lingframe.infra.storage.proxy;

import com.lingframe.api.security.PermissionService;
import lombok.RequiredArgsConstructor;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * 数据源代理：劫持 getConnection
 */
@RequiredArgsConstructor
public class LingDataSourceProxy implements DataSource {

    private final DataSource target;
    private final PermissionService permissionService;

    @Override
    public Connection getConnection() throws SQLException {
        // 可以在这里做“连接级”权限控制（例如：是否允许连接数据库）
        Connection connection = target.getConnection();
        return new LingConnectionProxy(connection, permissionService);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection connection = target.getConnection(username, password);
        return new LingConnectionProxy(connection, permissionService);
    }

    // --- 下面是必须实现的委托方法 ---
    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isAssignableFrom(getClass())) {
            return (T) this;
        }
        if (iface.isAssignableFrom(LingDataSourceProxy.class)) {
            return (T) this;
        }
        return target.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        if (iface.isAssignableFrom(getClass())) {
            return true;
        }
        return target.isWrapperFor(iface);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return target.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        target.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        target.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return target.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return target.getParentLogger();
    }
}