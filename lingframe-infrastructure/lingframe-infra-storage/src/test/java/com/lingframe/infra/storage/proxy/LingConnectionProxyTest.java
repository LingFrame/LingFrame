package com.lingframe.infra.storage.proxy;

import com.lingframe.api.security.PermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
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

    @Nested
    @DisplayName("Connection 委托转发测试")
    class ConnectionDelegateTests {

        @Test
        @DisplayName("覆盖所有直接转发的接口方法")
        @SuppressWarnings("resource")
        void testAllForwardingMethods() throws Exception {
            Connection target = mock(Connection.class);
            PermissionService permissionService = mock(PermissionService.class);
            LingConnectionProxy proxy = new LingConnectionProxy(target, permissionService);

            // 调用所有直接转发的方法
            proxy.close();
            verify(target).close();

            when(target.isClosed()).thenReturn(true);
            assertTrue(proxy.isClosed());

            proxy.commit();
            verify(target).commit();

            proxy.rollback();
            verify(target).rollback();

            proxy.setAutoCommit(true);
            verify(target).setAutoCommit(true);

            when(target.getAutoCommit()).thenReturn(true);
            assertTrue(proxy.getAutoCommit());

            proxy.getMetaData();
            verify(target).getMetaData();

            proxy.setReadOnly(true);
            verify(target).setReadOnly(true);

            when(target.isReadOnly()).thenReturn(true);
            assertTrue(proxy.isReadOnly());

            proxy.setCatalog("cat");
            verify(target).setCatalog("cat");

            when(target.getCatalog()).thenReturn("cat");
            assertEquals("cat", proxy.getCatalog());

            proxy.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            verify(target).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

            when(target.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, proxy.getTransactionIsolation());

            proxy.getWarnings();
            verify(target).getWarnings();

            proxy.clearWarnings();
            verify(target).clearWarnings();

            Map<String, Class<?>> typeMap = new HashMap<>();
            when(target.getTypeMap()).thenReturn(typeMap);
            assertSame(typeMap, proxy.getTypeMap());

            proxy.setTypeMap(typeMap);
            verify(target).setTypeMap(typeMap);

            proxy.setHoldability(1);
            verify(target).setHoldability(1);

            when(target.getHoldability()).thenReturn(1);
            assertEquals(1, proxy.getHoldability());

            Savepoint sp = mock(Savepoint.class);
            when(target.setSavepoint()).thenReturn(sp);
            assertSame(sp, proxy.setSavepoint());

            when(target.setSavepoint("name")).thenReturn(sp);
            assertSame(sp, proxy.setSavepoint("name"));

            proxy.rollback(sp);
            verify(target).rollback(sp);

            proxy.releaseSavepoint(sp);
            verify(target).releaseSavepoint(sp);

            proxy.createClob();
            verify(target).createClob();

            proxy.createBlob();
            verify(target).createBlob();

            proxy.createNClob();
            verify(target).createNClob();

            proxy.createSQLXML();
            verify(target).createSQLXML();

            when(target.isValid(5)).thenReturn(true);
            assertTrue(proxy.isValid(5));

            proxy.setClientInfo("k", "v");
            verify(target).setClientInfo("k", "v");

            Properties props = new Properties();
            proxy.setClientInfo(props);
            verify(target).setClientInfo(props);

            when(target.getClientInfo("k")).thenReturn("v");
            assertEquals("v", proxy.getClientInfo("k"));

            when(target.getClientInfo()).thenReturn(props);
            assertSame(props, proxy.getClientInfo());

            proxy.createArrayOf("type", new Object[0]);
            verify(target).createArrayOf("type", new Object[0]);

            proxy.createStruct("type", new Object[0]);
            verify(target).createStruct("type", new Object[0]);

            proxy.setSchema("schema");
            verify(target).setSchema("schema");

            when(target.getSchema()).thenReturn("schema");
            assertEquals("schema", proxy.getSchema());

            Executor executor = mock(Executor.class);
            proxy.abort(executor);
            verify(target).abort(executor);

            proxy.setNetworkTimeout(executor, 100);
            verify(target).setNetworkTimeout(executor, 100);

            when(target.getNetworkTimeout()).thenReturn(100);
            assertEquals(100, proxy.getNetworkTimeout());

            when(target.nativeSQL("sql")).thenReturn("nativesql");
            assertEquals("nativesql", proxy.nativeSQL("sql"));

            // 禁用逻辑方法 (prepareCall)
            assertThrows(SQLFeatureNotSupportedException.class, () -> proxy.prepareCall("sql"));
            assertThrows(SQLFeatureNotSupportedException.class, () -> proxy.prepareCall("sql", 1, 1));
            assertThrows(SQLFeatureNotSupportedException.class, () -> proxy.prepareCall("sql", 1, 1, 1));

            // unwrap / isWrapperFor
            assertSame(proxy, proxy.unwrap(LingConnectionProxy.class));
            assertSame(proxy, proxy.unwrap(Connection.class));
            when(target.unwrap(String.class)).thenReturn("target");
            assertEquals("target", proxy.unwrap(String.class));

            assertTrue(proxy.isWrapperFor(Connection.class));
            when(target.isWrapperFor(String.class)).thenReturn(false);
            assertFalse(proxy.isWrapperFor(String.class));

            // 各种重载 createStatement / prepareStatement
            Statement st = mock(Statement.class);
            when(target.createStatement(1, 2)).thenReturn(st);
            assertInstanceOf(LingStatementProxy.class, proxy.createStatement(1, 2));

            when(target.createStatement(1, 2, 3)).thenReturn(st);
            assertInstanceOf(LingStatementProxy.class, proxy.createStatement(1, 2, 3));

            PreparedStatement ps = mock(PreparedStatement.class);
            when(target.prepareStatement("sql", 1, 2)).thenReturn(ps);
            assertInstanceOf(LingPreparedStatementProxy.class, proxy.prepareStatement("sql", 1, 2));

            when(target.prepareStatement("sql", 1, 2, 3)).thenReturn(ps);
            assertInstanceOf(LingPreparedStatementProxy.class, proxy.prepareStatement("sql", 1, 2, 3));

            when(target.prepareStatement("sql", 1)).thenReturn(ps);
            assertInstanceOf(LingPreparedStatementProxy.class, proxy.prepareStatement("sql", 1));

            when(target.prepareStatement("sql", new int[0])).thenReturn(ps);
            assertInstanceOf(LingPreparedStatementProxy.class, proxy.prepareStatement("sql", new int[0]));

            when(target.prepareStatement("sql", new String[0])).thenReturn(ps);
            assertInstanceOf(LingPreparedStatementProxy.class, proxy.prepareStatement("sql", new String[0]));
        }
    }
}
