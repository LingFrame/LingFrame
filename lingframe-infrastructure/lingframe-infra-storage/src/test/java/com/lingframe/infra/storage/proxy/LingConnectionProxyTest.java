package com.lingframe.infra.storage.proxy;

import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("LingConnectionProxy 测试")
class LingConnectionProxyTest {

    private static final String SQL = "select * from users";

    @AfterEach
    void tearDown() {
        LingCallContext.clear();
    }

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

            // getMetaData 现在返回 LingDatabaseMetaDataProxy 包装
            when(target.getMetaData()).thenReturn(mock(DatabaseMetaData.class));
            assertInstanceOf(LingDatabaseMetaDataProxy.class, proxy.getMetaData());
            verify(target).getMetaData();

            proxy.setReadOnly(true);
            verify(target).setReadOnly(true);

            when(target.isReadOnly()).thenReturn(true);
            assertTrue(proxy.isReadOnly());

            // setCatalog/setSchema 已禁止灵元切换，独立测试覆盖拒绝行为
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

            // setSchema / abort 已禁止灵元破坏连接，独立测试覆盖拒绝行为
            when(target.getSchema()).thenReturn("schema");
            assertEquals("schema", proxy.getSchema());

            Executor executor = mock(Executor.class);
            // abort 不得下发到原生连接（可用性攻击面）
            assertThrows(SQLException.class, () -> proxy.abort(executor));
            verify(target, never()).abort(org.mockito.ArgumentMatchers.any());

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
            // unwrap 到非代理接口时拒绝暴露原生实现，防止绕过治理
            assertThrows(SQLException.class, () -> proxy.unwrap(String.class));

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

    @Nested
    @DisplayName("连接级破坏性操作")
    class DestructiveOpsTests {

        @Test
        @DisplayName("abort 必须被禁止，不得下发到原生连接")
        void abortMustBeForbidden() throws SQLException {
            Connection target = mock(Connection.class);
            PermissionService permissionService = mock(PermissionService.class);
            LingConnectionProxy proxy = new LingConnectionProxy(target, permissionService);

            SQLException ex = assertThrows(SQLException.class, () -> proxy.abort(mock(Executor.class)));
            assertTrue(ex.getMessage().contains("abort is forbidden"));
            verify(target, never()).abort(org.mockito.ArgumentMatchers.any());
        }
    }

    @Nested
    @DisplayName("事务权限治理")
    class TransactionPermissionTests {

        @Test
        @DisplayName("灵核治理开启时，无 LingContext 的事务操作应被拒绝")
        void shouldRejectTransactionWithoutContextWhenGovernanceEnabled() throws SQLException {
            Connection target = mock(Connection.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isLingCoreGovernanceEnabled()).thenReturn(true);

            LingConnectionProxy proxy = new LingConnectionProxy(target, permissionService);

            assertThrows(SQLException.class, proxy::commit);
            assertThrows(SQLException.class, proxy::rollback);
            assertThrows(SQLException.class, () -> proxy.setAutoCommit(true));

            // 确保目标方法未被调用
            verify(target, never()).commit();
            verify(target, never()).rollback();
            verify(target, never()).setAutoCommit(true);
        }

        @Test
        @DisplayName("灵核治理关闭时，无 LingContext 的事务操作应放行")
        void shouldAllowTransactionWithoutContextWhenGovernanceDisabled() throws SQLException {
            Connection target = mock(Connection.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isLingCoreGovernanceEnabled()).thenReturn(false);

            LingConnectionProxy proxy = new LingConnectionProxy(target, permissionService);

            proxy.commit();
            proxy.rollback();
            proxy.setAutoCommit(true);

            verify(target).commit();
            verify(target).rollback();
            verify(target).setAutoCommit(true);
        }

        @Test
        @DisplayName("rollback(Savepoint) 也应受事务权限治理")
        void shouldRejectRollbackWithSavepointWhenGovernanceEnabled() throws SQLException {
            Connection target = mock(Connection.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isLingCoreGovernanceEnabled()).thenReturn(true);

            LingConnectionProxy proxy = new LingConnectionProxy(target, permissionService);
            Savepoint sp = mock(Savepoint.class);

            assertThrows(SQLException.class, () -> proxy.rollback(sp));
            verify(target, never()).rollback(sp);
        }
    }

    @Nested
    @DisplayName("灵元级事务权限治理")
    class LingLevelTransactionPermissionTests {

        @Test
        @DisplayName("灵元被授予事务权限时，commit/rollback/setAutoCommit 应放行")
        void shouldAllowTransactionWhenLingHasPermission() throws SQLException {
            Connection target = mock(Connection.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "storage:sql:transaction", AccessType.WRITE))
                    .thenReturn(true);

            LingCallContext.setLingId("ling-a");
            LingConnectionProxy proxy = new LingConnectionProxy(target, permissionService);

            proxy.commit();
            proxy.rollback();
            proxy.setAutoCommit(true);

            verify(target).commit();
            verify(target).rollback();
            verify(target).setAutoCommit(true);
            // 审计应记录三次，每次 allowed=true
            verify(permissionService).audit("ling-a", "storage:sql:transaction", "transaction:commit", true);
            verify(permissionService).audit("ling-a", "storage:sql:transaction", "transaction:rollback", true);
            verify(permissionService).audit("ling-a", "storage:sql:transaction", "transaction:setAutoCommit", true);
        }

        @Test
        @DisplayName("灵元未被授予事务权限时，commit 应被拒绝且不转发到 target")
        void shouldRejectCommitWhenLingLacksPermission() throws SQLException {
            Connection target = mock(Connection.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "storage:sql:transaction", AccessType.WRITE))
                    .thenReturn(false);

            LingCallContext.setLingId("ling-a");
            LingConnectionProxy proxy = new LingConnectionProxy(target, permissionService);

            assertThrows(SQLException.class, proxy::commit);
            verify(target, never()).commit();
            // 审计应记录 allowed=false
            verify(permissionService).audit("ling-a", "storage:sql:transaction", "transaction:commit", false);
        }

        @Test
        @DisplayName("灵元未被授予事务权限时，rollback 应被拒绝且不转发到 target")
        void shouldRejectRollbackWhenLingLacksPermission() throws SQLException {
            Connection target = mock(Connection.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "storage:sql:transaction", AccessType.WRITE))
                    .thenReturn(false);

            LingCallContext.setLingId("ling-a");
            LingConnectionProxy proxy = new LingConnectionProxy(target, permissionService);

            assertThrows(SQLException.class, proxy::rollback);
            verify(target, never()).rollback();
            verify(permissionService).audit("ling-a", "storage:sql:transaction", "transaction:rollback", false);
        }

        @Test
        @DisplayName("灵元未被授予事务权限时，setAutoCommit 应被拒绝且不转发到 target")
        void shouldRejectSetAutoCommitWhenLingLacksPermission() throws SQLException {
            Connection target = mock(Connection.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "storage:sql:transaction", AccessType.WRITE))
                    .thenReturn(false);

            LingCallContext.setLingId("ling-a");
            LingConnectionProxy proxy = new LingConnectionProxy(target, permissionService);

            assertThrows(SQLException.class, () -> proxy.setAutoCommit(true));
            verify(target, never()).setAutoCommit(true);
            verify(permissionService).audit("ling-a", "storage:sql:transaction", "transaction:setAutoCommit", false);
        }

        @Test
        @DisplayName("灵元未被授予事务权限时，rollback(Savepoint) 应被拒绝")
        void shouldRejectRollbackWithSavepointWhenLingLacksPermission() throws SQLException {
            Connection target = mock(Connection.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "storage:sql:transaction", AccessType.WRITE))
                    .thenReturn(false);

            LingCallContext.setLingId("ling-a");
            LingConnectionProxy proxy = new LingConnectionProxy(target, permissionService);
            Savepoint sp = mock(Savepoint.class);

            assertThrows(SQLException.class, () -> proxy.rollback(sp));
            verify(target, never()).rollback(sp);
        }

        @Test
        @DisplayName("灵元有事务权限时，rollback(Savepoint) 应放行")
        void shouldAllowRollbackWithSavepointWhenLingHasPermission() throws SQLException {
            Connection target = mock(Connection.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "storage:sql:transaction", AccessType.WRITE))
                    .thenReturn(true);

            LingCallContext.setLingId("ling-a");
            LingConnectionProxy proxy = new LingConnectionProxy(target, permissionService);
            Savepoint sp = mock(Savepoint.class);

            proxy.rollback(sp);
            verify(target).rollback(sp);
        }
    }

    @Nested
    @DisplayName("数据库切换治理")
    class CatalogSchemaGuardTests {

        @Test
        @DisplayName("setCatalog 应被禁止——会绕过表级 capability")
        void shouldRejectSetCatalog() throws SQLException {
            Connection target = mock(Connection.class);
            PermissionService permissionService = mock(PermissionService.class);
            LingConnectionProxy proxy = new LingConnectionProxy(target, permissionService);

            assertThrows(SQLException.class, () -> proxy.setCatalog("other_db"));
            verify(target, never()).setCatalog("other_db");
        }

        @Test
        @DisplayName("setSchema 应被禁止——会绕过表级 capability")
        void shouldRejectSetSchema() throws SQLException {
            Connection target = mock(Connection.class);
            PermissionService permissionService = mock(PermissionService.class);
            LingConnectionProxy proxy = new LingConnectionProxy(target, permissionService);

            assertThrows(SQLException.class, () -> proxy.setSchema("other_schema"));
            verify(target, never()).setSchema("other_schema");
        }

        @Test
        @DisplayName("getCatalog 应正常转发——读操作不切换数据库")
        void shouldForwardGetCatalog() throws SQLException {
            Connection target = mock(Connection.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(target.getCatalog()).thenReturn("current_db");
            LingConnectionProxy proxy = new LingConnectionProxy(target, permissionService);

            assertEquals("current_db", proxy.getCatalog());
            verify(target).getCatalog();
        }

        @Test
        @DisplayName("getSchema 应正常转发——读操作不切换数据库")
        void shouldForwardGetSchema() throws SQLException {
            Connection target = mock(Connection.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(target.getSchema()).thenReturn("current_schema");
            LingConnectionProxy proxy = new LingConnectionProxy(target, permissionService);

            assertEquals("current_schema", proxy.getSchema());
            verify(target).getSchema();
        }
    }
}
