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
 * 数据源代理
 * 职责：劫持 getConnection 方法，为返回的 Connection 实例自动套上治理代理，
 * 从而实现对 JDBC 执行层面的权限管控和审计映射。
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
        // 禁止灵元用任意凭据连接，强制使用灵核配置的连接池凭据
        throw new SQLException("getConnection(username, password) is forbidden on governed DataSource, "
                + "use getConnection() with configured credentials");
    }

    // --- 下面是必须实现的委托方法 ---
    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isAssignableFrom(getClass())) {
            return (T) this;
        }
        // 拒绝暴露原生数据源实现（如 HikariDataSource），防止绕过治理代理
        throw new SQLException("Cannot unwrap to " + iface.getName()
                + ": LingDataSourceProxy only exposes the DataSource interface");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isAssignableFrom(getClass());
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