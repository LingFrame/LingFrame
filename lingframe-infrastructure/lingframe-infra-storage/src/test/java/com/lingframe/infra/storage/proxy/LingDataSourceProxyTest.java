package com.lingframe.infra.storage.proxy;

import com.lingframe.api.security.PermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import java.io.PrintWriter;
import java.util.logging.Logger;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("LingDataSourceProxy 测试")
class LingDataSourceProxyTest {

    @Nested
    @DisplayName("代理链穿透")
    class ProxyChainTests {

        @Test
        @DisplayName("getConnection 应返回 LingConnectionProxy")
        void shouldReturnConnectionProxy() throws SQLException {
            DataSource target = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(target.getConnection()).thenReturn(connection);

            LingDataSourceProxy proxy = new LingDataSourceProxy(target, permissionService);

            assertInstanceOf(LingConnectionProxy.class, proxy.getConnection());
            verify(target).getConnection();
        }

        @Test
        @DisplayName("带用户名密码的 getConnection 应返回 LingConnectionProxy")
        void shouldReturnConnectionProxyForCredentialPath() throws SQLException {
            DataSource target = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(target.getConnection("user", "pwd")).thenReturn(connection);

            LingDataSourceProxy proxy = new LingDataSourceProxy(target, permissionService);

            assertInstanceOf(LingConnectionProxy.class, proxy.getConnection("user", "pwd"));
            verify(target).getConnection("user", "pwd");
        }
    }

    @Nested
    @DisplayName("DataSource 委托转发测试")
    class DataSourceDelegateTests {

        @Test
        @DisplayName("覆盖所有直接转发的接口方法")
        void testAllForwardingMethods() throws Exception {
            DataSource target = mock(DataSource.class);
            PermissionService permissionService = mock(PermissionService.class);
            LingDataSourceProxy proxy = new LingDataSourceProxy(target, permissionService);

            PrintWriter writer = mock(PrintWriter.class);
            proxy.setLogWriter(writer);
            verify(target).setLogWriter(writer);

            when(target.getLogWriter()).thenReturn(writer);
            assertSame(writer, proxy.getLogWriter());

            proxy.setLoginTimeout(30);
            verify(target).setLoginTimeout(30);

            when(target.getLoginTimeout()).thenReturn(30);
            assertEquals(30, proxy.getLoginTimeout());

            Logger logger = Logger.getLogger("test");
            when(target.getParentLogger()).thenReturn(logger);
            assertSame(logger, proxy.getParentLogger());

            // unwrap / isWrapperFor
            assertSame(proxy, proxy.unwrap(LingDataSourceProxy.class));
            assertSame(proxy, proxy.unwrap(DataSource.class));
            when(target.unwrap(String.class)).thenReturn("target");
            assertEquals("target", proxy.unwrap(String.class));

            assertTrue(proxy.isWrapperFor(DataSource.class));
            when(target.isWrapperFor(String.class)).thenReturn(false);
            assertFalse(proxy.isWrapperFor(String.class));
        }
    }
}
