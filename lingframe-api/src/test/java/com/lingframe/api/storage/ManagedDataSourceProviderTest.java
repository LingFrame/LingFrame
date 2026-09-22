package com.lingframe.api.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 受管数据源供给 SPI 契约测试：默认 dataSourceId 语义、匿名实现供给与覆盖。
 * <p>
 * 接口无业务实现（api 契约层），本测试锁定接口自带默认方法行为与匿名实现契约，
 * 防止实现方误改默认身份语义（模式 1 恒为 "default"）。
 */
@DisplayName("ManagedDataSourceProvider 受管数据源供给契约")
class ManagedDataSourceProviderTest {

    @Nested
    @DisplayName("默认 dataSourceId 语义")
    class DefaultDataSourceId {

        @Test
        @DisplayName("未覆盖时默认身份为 default")
        void defaultIdIsDefault() {
            DataSource ds = new FakeDataSource();
            ManagedDataSourceProvider provider = () -> ds;

            assertEquals("default", provider.getDataSourceId());
        }

        @Test
        @DisplayName("模式 3 供给端可覆盖 dataSourceId 以区分多存储灵元")
        void idCanBeOverridden() {
            DataSource ds = new FakeDataSource();
            ManagedDataSourceProvider provider = new ManagedDataSourceProvider() {
                @Override
                public DataSource getDataSource() {
                    return ds;
                }

                @Override
                public String getDataSourceId() {
                    return "order-ds";
                }
            };

            assertEquals("order-ds", provider.getDataSourceId());
        }
    }

    @Nested
    @DisplayName("数据源供给契约")
    class DataSourceSupply {

        @Test
        @DisplayName("getDataSource 返回实现方提供的同一数据源实例")
        void suppliesSameDataSourceInstance() {
            DataSource ds = new FakeDataSource();
            ManagedDataSourceProvider provider = () -> ds;

            assertNotNull(provider.getDataSource());
            assertSame(ds, provider.getDataSource());
        }

        @Test
        @DisplayName("lambda 简写（灵核侧静态供给）与完整匿名类等价")
        void lambdaAndAnonymousClassAreEquivalent() {
            DataSource ds = new FakeDataSource();
            ManagedDataSourceProvider lambda = () -> ds;
            ManagedDataSourceProvider anonymous = new ManagedDataSourceProvider() {
                @Override
                public DataSource getDataSource() {
                    return ds;
                }
            };

            assertSame(ds, lambda.getDataSource());
            assertSame(ds, anonymous.getDataSource());
            assertEquals(lambda.getDataSourceId(), anonymous.getDataSourceId());
        }
    }

    /** 轻量 DataSource 替身（api 模块无 Mockito，实现空方法即可满足引用语义）。 */
    private static final class FakeDataSource implements DataSource {
        @Override
        public Connection getConnection() {
            return null;
        }

        @Override
        public Connection getConnection(String username, String password) {
            return null;
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return null;
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            return null;
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
