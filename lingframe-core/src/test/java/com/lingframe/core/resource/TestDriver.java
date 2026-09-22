package com.lingframe.core.resource;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * 测试专用 JDBC Driver 实现，供 {@link JdbcDriverUnloadHookSupplementTest}
 * 通过 URLClassLoader 隔离加载后注册到 DriverManager，验证卸载钩子清理逻辑。
 * <p>
 * 该类需保持自包含（不引用其他测试类），便于被 parent=null 的 URLClassLoader 加载。
 */
public class TestDriver implements Driver {

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        return null;
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return false;
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }
}
