package com.lingframe.infra.storage.proxy;

import com.lingframe.api.security.PermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
}
