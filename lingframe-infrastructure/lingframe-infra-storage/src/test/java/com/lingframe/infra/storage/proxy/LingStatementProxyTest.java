package com.lingframe.infra.storage.proxy;

import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("LingStatementProxy 测试")
class LingStatementProxyTest {

    private static final String SELECT_SQL = "select * from users";
    private static final String SCHEMA_SELECT_SQL = "select * from public.users";
    private static final String JOIN_SELECT_SQL =
            "select u.id, o.id from users u join orders o on u.id = o.user_id";
    private static final String DELETE_SQL = "delete from users where id = 1";
    private static final String MALFORMED_SQL = "not-valid-sql";

    @AfterEach
    void tearDown() {
        LingCallContext.clear();
        SqlParseCache.evictLing("ling-a");
        SqlParseCache.evictLing(null);
    }

    @Nested
    @DisplayName("权限路径")
    class PermissionTests {

        @Test
        @DisplayName("SELECT 且拥有读取权限时应放行并写入解析缓存")
        void shouldAllowSelectWhenReadPermissionGranted() throws SQLException {
            Statement target = mock(Statement.class);
            ResultSet resultSet = mock(ResultSet.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "storage:sql:table:users", AccessType.READ)).thenReturn(true);
            when(target.executeQuery(SELECT_SQL)).thenReturn(resultSet);

            LingCallContext.setLingId("ling-a");
            LingStatementProxy proxy = new LingStatementProxy(target, permissionService);

            assertSame(resultSet, proxy.executeQuery(SELECT_SQL));
            verify(permissionService).isAllowed("ling-a", "storage:sql:table:users", AccessType.READ);
            verify(permissionService).audit("ling-a", "storage:sql:table:users", SELECT_SQL, true);
            assertEquals(AccessType.READ, SqlParseCache.get("ling-a", SELECT_SQL));
        }

        @Test
        @DisplayName("DELETE 且缺少写权限时应拒绝执行")
        void shouldRejectDeleteWhenWritePermissionDenied() throws SQLException {
            Statement target = mock(Statement.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "storage:sql", AccessType.WRITE)).thenReturn(false);

            LingCallContext.setLingId("ling-a");
            LingStatementProxy proxy = new LingStatementProxy(target, permissionService);

            SQLException ex = assertThrows(SQLException.class, () -> proxy.executeUpdate(DELETE_SQL));
            assertInstanceOf(PermissionDeniedException.class, ex.getCause());
            verify(permissionService).isAllowed("ling-a", "storage:sql", AccessType.WRITE);
            verify(permissionService).audit("ling-a", "storage:sql", DELETE_SQL, false);
            verify(target, never()).executeUpdate(DELETE_SQL);
            assertEquals(AccessType.WRITE, SqlParseCache.get("ling-a", DELETE_SQL));
        }

        @Test
        @DisplayName("表级 capability 未命中时应回退到通用 SQL 权限")
        void shouldFallbackToGenericCapabilityWhenTableCapabilityDenied() throws SQLException {
            Statement target = mock(Statement.class);
            ResultSet resultSet = mock(ResultSet.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "storage:sql:table:public.users", AccessType.READ))
                    .thenReturn(false);
            when(permissionService.isAllowed("ling-a", "storage:sql:table:users", AccessType.READ))
                    .thenReturn(false);
            when(permissionService.isAllowed("ling-a", "storage:sql", AccessType.READ)).thenReturn(true);
            when(target.executeQuery(SCHEMA_SELECT_SQL)).thenReturn(resultSet);

            LingCallContext.setLingId("ling-a");
            LingStatementProxy proxy = new LingStatementProxy(target, permissionService);

            assertSame(resultSet, proxy.executeQuery(SCHEMA_SELECT_SQL));
            verify(permissionService).isAllowed("ling-a", "storage:sql:table:public.users", AccessType.READ);
            verify(permissionService).isAllowed("ling-a", "storage:sql:table:users", AccessType.READ);
            verify(permissionService).isAllowed("ling-a", "storage:sql", AccessType.READ);
            verify(permissionService).audit("ling-a", "storage:sql", SCHEMA_SELECT_SQL, true);
        }

        @Test
        @DisplayName("多表语句应要求所有表级 capability 均被允许")
        void shouldRequireAllTableCapabilitiesForMultiTableSql() throws SQLException {
            Statement target = mock(Statement.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "storage:sql:table:users", AccessType.READ)).thenReturn(true);
            when(permissionService.isAllowed("ling-a", "storage:sql:table:orders", AccessType.READ)).thenReturn(false);
            when(permissionService.isAllowed("ling-a", "storage:sql", AccessType.READ)).thenReturn(false);

            LingCallContext.setLingId("ling-a");
            LingStatementProxy proxy = new LingStatementProxy(target, permissionService);

            SQLException ex = assertThrows(SQLException.class, () -> proxy.executeQuery(JOIN_SELECT_SQL));
            assertInstanceOf(PermissionDeniedException.class, ex.getCause());
            verify(permissionService).isAllowed("ling-a", "storage:sql:table:users", AccessType.READ);
            verify(permissionService).isAllowed("ling-a", "storage:sql:table:orders", AccessType.READ);
            verify(permissionService).isAllowed("ling-a", "storage:sql", AccessType.READ);
            verify(permissionService).audit("ling-a", "storage:sql", JOIN_SELECT_SQL, false);
            verify(target, never()).executeQuery(JOIN_SELECT_SQL);
        }

        @Test
        @DisplayName("无上下文且灵核治理开启时应拒绝执行")
        void shouldRejectWhenNoContextAndLingCoreGovernanceEnabled() throws SQLException {
            Statement target = mock(Statement.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isLingCoreGovernanceEnabled()).thenReturn(true);

            LingStatementProxy proxy = new LingStatementProxy(target, permissionService);

            SQLException ex = assertThrows(SQLException.class, () -> proxy.executeQuery(SELECT_SQL));
            assertEquals("Access Denied: LINGCORE governance is enabled but no context provided.", ex.getMessage());
            verify(permissionService).isLingCoreGovernanceEnabled();
            verify(target, never()).executeQuery(SELECT_SQL);
        }

        @Test
        @DisplayName("无上下文且灵核治理关闭时应允许执行")
        void shouldAllowWhenNoContextAndLingCoreGovernanceDisabled() throws SQLException {
            Statement target = mock(Statement.class);
            ResultSet resultSet = mock(ResultSet.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isLingCoreGovernanceEnabled()).thenReturn(false);
            when(target.executeQuery(SELECT_SQL)).thenReturn(resultSet);

            LingStatementProxy proxy = new LingStatementProxy(target, permissionService);

            assertSame(resultSet, proxy.executeQuery(SELECT_SQL));
            verify(permissionService).isLingCoreGovernanceEnabled();
            verify(target).executeQuery(SELECT_SQL);
        }
    }

    @Nested
    @DisplayName("缓存路径")
    class CachePathTests {

        @Test
        @DisplayName("缓存命中时应直接复用已有访问类型")
        void shouldReuseCachedAccessTypeWhenPresent() throws SQLException {
            Statement target = mock(Statement.class);
            ResultSet resultSet = mock(ResultSet.class);
            PermissionService permissionService = mock(PermissionService.class);
            SqlParseCache.put("ling-a", MALFORMED_SQL, AccessType.READ);
            when(permissionService.isAllowed("ling-a", "storage:sql", AccessType.READ)).thenReturn(true);
            when(target.executeQuery(MALFORMED_SQL)).thenReturn(resultSet);

            LingCallContext.setLingId("ling-a");
            LingStatementProxy proxy = new LingStatementProxy(target, permissionService);

            assertSame(resultSet, proxy.executeQuery(MALFORMED_SQL));
            verify(permissionService).isAllowed("ling-a", "storage:sql", AccessType.READ);
            verify(permissionService).audit("ling-a", "storage:sql", MALFORMED_SQL, true);
        }
    }

    @Nested
    @DisplayName("Statement 委托转发测试")
    class StatementDelegateTests {

        @Test
        @DisplayName("覆盖所有直接转发的接口方法")
        void testAllForwardingMethods() throws Exception {
            Statement target = mock(Statement.class);
            PermissionService permissionService = mock(PermissionService.class);
            LingStatementProxy proxy = new LingStatementProxy(target, permissionService);

            proxy.close();
            verify(target).close();

            proxy.isClosed();
            verify(target).isClosed();

            proxy.getMaxFieldSize();
            verify(target).getMaxFieldSize();

            proxy.setMaxFieldSize(10);
            verify(target).setMaxFieldSize(10);

            proxy.getMaxRows();
            verify(target).getMaxRows();

            proxy.setMaxRows(10);
            verify(target).setMaxRows(10);

            proxy.setEscapeProcessing(true);
            verify(target).setEscapeProcessing(true);

            proxy.getQueryTimeout();
            verify(target).getQueryTimeout();

            proxy.setQueryTimeout(10);
            verify(target).setQueryTimeout(10);

            proxy.cancel();
            verify(target).cancel();

            proxy.getWarnings();
            verify(target).getWarnings();

            proxy.clearWarnings();
            verify(target).clearWarnings();

            proxy.setCursorName("name");
            verify(target).setCursorName("name");

            when(target.getResultSet()).thenReturn(mock(ResultSet.class));
            proxy.getResultSet();
            verify(target).getResultSet();

            proxy.getUpdateCount();
            verify(target).getUpdateCount();

            proxy.getMoreResults();
            verify(target).getMoreResults();

            proxy.setFetchDirection(1);
            verify(target).setFetchDirection(1);

            proxy.getFetchDirection();
            verify(target).getFetchDirection();

            proxy.setFetchSize(10);
            verify(target).setFetchSize(10);

            proxy.getFetchSize();
            verify(target).getFetchSize();

            proxy.getResultSetConcurrency();
            verify(target).getResultSetConcurrency();

            proxy.getResultSetType();
            verify(target).getResultSetType();

            proxy.clearBatch();
            verify(target).clearBatch();

            proxy.executeBatch();
            verify(target).executeBatch();

            proxy.getConnection();
            verify(target).getConnection();

            proxy.getMoreResults(1);
            verify(target).getMoreResults(1);

            proxy.getGeneratedKeys();
            verify(target).getGeneratedKeys();

            proxy.getResultSetHoldability();
            verify(target).getResultSetHoldability();

            proxy.setPoolable(true);
            verify(target).setPoolable(true);

            proxy.isPoolable();
            verify(target).isPoolable();

            proxy.closeOnCompletion();
            verify(target).closeOnCompletion();

            proxy.isCloseOnCompletion();
            verify(target).isCloseOnCompletion();

            // unwrap / isWrapperFor
            assertSame(proxy, proxy.unwrap(LingStatementProxy.class));
            assertSame(proxy, proxy.unwrap(Statement.class));
            when(target.unwrap(String.class)).thenReturn("target");
            assertEquals("target", proxy.unwrap(String.class));

            assertTrue(proxy.isWrapperFor(Statement.class));
            when(target.isWrapperFor(String.class)).thenReturn(false);
            assertFalse(proxy.isWrapperFor(String.class));
        }

        @Test
        @DisplayName("测试 checkPermission 保护的方法和重载")
        void testPermissionMethods() throws Exception {
            Statement target = mock(Statement.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isLingCoreGovernanceEnabled()).thenReturn(false);

            LingStatementProxy proxy = new LingStatementProxy(target, permissionService);

            proxy.addBatch("insert into users values(1)");
            verify(target).addBatch("insert into users values(1)");

            proxy.executeUpdate("update users set x=1", 1);
            verify(target).executeUpdate("update users set x=1", 1);

            proxy.executeUpdate("update users set x=1", new int[0]);
            verify(target).executeUpdate("update users set x=1", new int[0]);

            proxy.executeUpdate("update users set x=1", new String[0]);
            verify(target).executeUpdate("update users set x=1", new String[0]);

            proxy.execute("select 1", 1);
            verify(target).execute("select 1", 1);

            proxy.execute("select 1", new int[0]);
            verify(target).execute("select 1", new int[0]);

            proxy.execute("select 1", new String[0]);
            verify(target).execute("select 1", new String[0]);
        }
    }
}
