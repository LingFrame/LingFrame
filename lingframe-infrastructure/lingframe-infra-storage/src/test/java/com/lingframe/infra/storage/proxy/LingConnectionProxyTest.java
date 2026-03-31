package com.lingframe.infra.storage.proxy;

import com.lingframe.api.security.PermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("LingConnectionProxy 测试")
class LingConnectionProxyTest {

    private static final String SQL = "select * from users";

    @Nested
    @DisplayName("代理链穿透")
    class ProxyChainTests {

        @Test
        @DisplayName("createStatement 应返回 LingStatementProxy")
        void shouldReturnStatementProxy() throws SQLException {
            Connection target = mock(Connection.class);
            Statement statement = mock(Statement.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(target.createStatement()).thenReturn(statement);

            LingConnectionProxy proxy = new LingConnectionProxy(target, permissionService);

            assertInstanceOf(LingStatementProxy.class, proxy.createStatement());
            verify(target).createStatement();
        }

        @Test
        @DisplayName("prepareStatement 应返回 LingPreparedStatementProxy")
        void shouldReturnPreparedStatementProxy() throws SQLException {
            Connection target = mock(Connection.class);
            PreparedStatement preparedStatement = mock(PreparedStatement.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(target.prepareStatement(SQL)).thenReturn(preparedStatement);

            LingConnectionProxy proxy = new LingConnectionProxy(target, permissionService);

            assertInstanceOf(LingPreparedStatementProxy.class, proxy.prepareStatement(SQL));
            verify(target).prepareStatement(SQL);
        }
    }
}
