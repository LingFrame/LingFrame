package com.lingframe.infra.storage.proxy;

import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("LingPreparedStatementProxy 测试")
class LingPreparedStatementProxyTest {

    private static final String SELECT_SQL = "select * from users where id = ?";
    private static final String DELETE_SQL = "delete from users where id = ?";
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
            PreparedStatement target = mock(PreparedStatement.class);
            ResultSet resultSet = mock(ResultSet.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "storage:sql", AccessType.READ)).thenReturn(true);
            when(target.executeQuery()).thenReturn(resultSet);

            LingCallContext.setLingId("ling-a");
            LingPreparedStatementProxy proxy = new LingPreparedStatementProxy(target, permissionService, SELECT_SQL);

            assertSame(resultSet, proxy.executeQuery());
            verify(permissionService).isAllowed("ling-a", "storage:sql", AccessType.READ);
            verify(permissionService).audit("ling-a", "storage:sql", SELECT_SQL, true);
            assertEquals(AccessType.READ, SqlParseCache.get("ling-a", SELECT_SQL));
        }

        @Test
        @DisplayName("DELETE 且缺少写权限时应拒绝执行")
        void shouldRejectDeleteWhenWritePermissionDenied() throws SQLException {
            PreparedStatement target = mock(PreparedStatement.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "storage:sql", AccessType.WRITE)).thenReturn(false);

            LingCallContext.setLingId("ling-a");
            LingPreparedStatementProxy proxy = new LingPreparedStatementProxy(target, permissionService, DELETE_SQL);

            SQLException ex = assertThrows(SQLException.class, proxy::executeUpdate);
            assertInstanceOf(PermissionDeniedException.class, ex.getCause());
            verify(permissionService).isAllowed("ling-a", "storage:sql", AccessType.WRITE);
            verify(permissionService).audit("ling-a", "storage:sql", DELETE_SQL, false);
            verify(target, never()).executeUpdate();
            assertEquals(AccessType.WRITE, SqlParseCache.get("ling-a", DELETE_SQL));
        }

        @Test
        @DisplayName("无上下文且灵核治理开启时应拒绝执行")
        void shouldRejectWhenNoContextAndLingCoreGovernanceEnabled() throws SQLException {
            PreparedStatement target = mock(PreparedStatement.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isLingCoreGovernanceEnabled()).thenReturn(true);

            LingPreparedStatementProxy proxy = new LingPreparedStatementProxy(target, permissionService, SELECT_SQL);

            SQLException ex = assertThrows(SQLException.class, proxy::executeQuery);
            assertEquals("Access Denied: LINGCORE governance is enabled but no context provided.", ex.getMessage());
            verify(permissionService).isLingCoreGovernanceEnabled();
            verify(target, never()).executeQuery();
        }

        @Test
        @DisplayName("无上下文且灵核治理关闭时应允许执行")
        void shouldAllowWhenNoContextAndLingCoreGovernanceDisabled() throws SQLException {
            PreparedStatement target = mock(PreparedStatement.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isLingCoreGovernanceEnabled()).thenReturn(false);
            when(target.executeUpdate()).thenReturn(1);

            LingPreparedStatementProxy proxy = new LingPreparedStatementProxy(target, permissionService, DELETE_SQL);

            assertEquals(1, proxy.executeUpdate());
            verify(permissionService).isLingCoreGovernanceEnabled();
            verify(target).executeUpdate();
        }
    }

    @Nested
    @DisplayName("缓存路径")
    class CachePathTests {

        @Test
        @DisplayName("缓存命中时应直接复用已有访问类型")
        void shouldReuseCachedAccessTypeWhenPresent() throws SQLException {
            PreparedStatement target = mock(PreparedStatement.class);
            ResultSet resultSet = mock(ResultSet.class);
            PermissionService permissionService = mock(PermissionService.class);
            SqlParseCache.put("ling-a", MALFORMED_SQL, AccessType.READ);
            when(permissionService.isAllowed("ling-a", "storage:sql", AccessType.READ)).thenReturn(true);
            when(target.executeQuery()).thenReturn(resultSet);

            LingCallContext.setLingId("ling-a");
            LingPreparedStatementProxy proxy = new LingPreparedStatementProxy(target, permissionService, MALFORMED_SQL);

            assertSame(resultSet, proxy.executeQuery());
            verify(permissionService).isAllowed("ling-a", "storage:sql", AccessType.READ);
            verify(permissionService).audit("ling-a", "storage:sql", MALFORMED_SQL, true);
        }
    }
}
