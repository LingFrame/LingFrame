package com.lingframe.infra.storage.proxy;

import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("LingPreparedStatementProxy 测试")
class LingPreparedStatementProxyTest {

    private static final String SELECT_SQL = "select * from users where id = ?";
    private static final String SCHEMA_SELECT_SQL = "select * from public.users where id = ?";
    private static final String JOIN_SELECT_SQL =
            "select u.id, o.id from users u join orders o on u.id = o.user_id where u.id = ?";
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
        @DisplayName("启用表级治理时 generic + 表级均通过应放行并写入解析缓存")
        void shouldAllowSelectWhenReadPermissionGranted() throws SQLException {
            PreparedStatement target = mock(PreparedStatement.class);
            ResultSet resultSet = mock(ResultSet.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "storage:sql", AccessType.READ)).thenReturn(true);
            when(permissionService.hasCapabilityPrefix("ling-a", "storage:sql:table:")).thenReturn(true);
            when(permissionService.isAllowed("ling-a", "storage:sql:table:users", AccessType.READ)).thenReturn(true);
            when(target.executeQuery()).thenReturn(resultSet);

            LingCallContext.setLingId("ling-a");
            LingPreparedStatementProxy proxy = new LingPreparedStatementProxy(target, permissionService, SELECT_SQL, null);

            // executeQuery() 返回 LingResultSetProxy 包装
            assertInstanceOf(LingResultSetProxy.class, proxy.executeQuery());
            verify(permissionService).isAllowed("ling-a", "storage:sql", AccessType.READ);
            verify(permissionService).hasCapabilityPrefix("ling-a", "storage:sql:table:");
            verify(permissionService).isAllowed("ling-a", "storage:sql:table:users", AccessType.READ);
            // SELECT 表为读表，audit capability 带 :read 后缀
            verify(permissionService).audit("ling-a", "storage:sql:table:users:read", SELECT_SQL, true);
            assertEquals(AccessType.READ, SqlParseCache.get("ling-a", SELECT_SQL).getAccessType());
        }

        @Test
        @DisplayName("DELETE 且缺少 generic 写权限时应拒绝执行")
        void shouldRejectDeleteWhenWritePermissionDenied() throws SQLException {
            PreparedStatement target = mock(PreparedStatement.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "storage:sql", AccessType.WRITE)).thenReturn(false);

            LingCallContext.setLingId("ling-a");
            LingPreparedStatementProxy proxy = new LingPreparedStatementProxy(target, permissionService, DELETE_SQL, null);

            SQLException ex = assertThrows(SQLException.class, proxy::executeUpdate);
            assertInstanceOf(PermissionDeniedException.class, ex.getCause());
            verify(permissionService).isAllowed("ling-a", "storage:sql", AccessType.WRITE);
            verify(permissionService).audit("ling-a", "storage:sql", DELETE_SQL, false);
            verify(target, never()).executeUpdate();
            assertEquals(AccessType.WRITE, SqlParseCache.get("ling-a", DELETE_SQL).getAccessType());
        }

        @Test
        @DisplayName("未启用表级治理时只看 generic 总开关")
        void shouldOnlyCheckGenericWhenTableLevelNotEnabled() throws SQLException {
            PreparedStatement target = mock(PreparedStatement.class);
            ResultSet resultSet = mock(ResultSet.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "storage:sql", AccessType.READ)).thenReturn(true);
            // hasCapabilityPrefix 默认 false → 未启用表级治理，跳过表级检查
            when(target.executeQuery()).thenReturn(resultSet);

            LingCallContext.setLingId("ling-a");
            LingPreparedStatementProxy proxy =
                    new LingPreparedStatementProxy(target, permissionService, SCHEMA_SELECT_SQL, null);

            // executeQuery() 返回 LingResultSetProxy 包装
            assertInstanceOf(LingResultSetProxy.class, proxy.executeQuery());
            verify(permissionService).isAllowed("ling-a", "storage:sql", AccessType.READ);
            verify(permissionService).hasCapabilityPrefix("ling-a", "storage:sql:table:");
            verify(permissionService).audit("ling-a", "storage:sql", SCHEMA_SELECT_SQL, true);
        }

        @Test
        @DisplayName("启用表级治理时多表语句需所有表级 capability 均通过（AND 语义）")
        void shouldRequireAllTableCapabilitiesForMultiTableSql() throws SQLException {
            PreparedStatement target = mock(PreparedStatement.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "storage:sql", AccessType.READ)).thenReturn(true);
            when(permissionService.hasCapabilityPrefix("ling-a", "storage:sql:table:")).thenReturn(true);
            when(permissionService.isAllowed("ling-a", "storage:sql:table:users", AccessType.READ)).thenReturn(true);
            when(permissionService.isAllowed("ling-a", "storage:sql:table:orders", AccessType.READ)).thenReturn(false);

            LingCallContext.setLingId("ling-a");
            LingPreparedStatementProxy proxy = new LingPreparedStatementProxy(target, permissionService, JOIN_SELECT_SQL, null);

            SQLException ex = assertThrows(SQLException.class, proxy::executeQuery);
            assertInstanceOf(PermissionDeniedException.class, ex.getCause());
            verify(permissionService).isAllowed("ling-a", "storage:sql", AccessType.READ);
            verify(permissionService).hasCapabilityPrefix("ling-a", "storage:sql:table:");
            verify(permissionService).isAllowed("ling-a", "storage:sql:table:users", AccessType.READ);
            verify(permissionService).isAllowed("ling-a", "storage:sql:table:orders", AccessType.READ);
            // JOIN SELECT 的表为读表，拒绝时审计 capability 为实际校验的权限键（无 :read 后缀）
            verify(permissionService).audit("ling-a", "storage:sql:table:orders", JOIN_SELECT_SQL, false);
            verify(target, never()).executeQuery();
        }

        @Test
        @DisplayName("SQL 解析失败时应 fail-closed 拒绝执行")
        void shouldRejectWhenSqlParseFailed() throws SQLException {
            PreparedStatement target = mock(PreparedStatement.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "storage:sql", AccessType.EXECUTE)).thenReturn(true);

            LingCallContext.setLingId("ling-a");
            LingPreparedStatementProxy proxy = new LingPreparedStatementProxy(target, permissionService, MALFORMED_SQL, null);

            SQLException ex = assertThrows(SQLException.class, proxy::executeQuery);
            assertInstanceOf(PermissionDeniedException.class, ex.getCause());
            verify(target, never()).executeQuery();
        }

        @Test
        @DisplayName("无上下文且灵核治理开启时应拒绝执行")
        void shouldRejectWhenNoContextAndLingCoreGovernanceEnabled() throws SQLException {
            PreparedStatement target = mock(PreparedStatement.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isLingCoreGovernanceEnabled()).thenReturn(true);

            LingPreparedStatementProxy proxy = new LingPreparedStatementProxy(target, permissionService, SELECT_SQL, null);

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

            LingPreparedStatementProxy proxy = new LingPreparedStatementProxy(target, permissionService, DELETE_SQL, null);

            assertEquals(1, proxy.executeUpdate());
            verify(permissionService).isLingCoreGovernanceEnabled();
            verify(target).executeUpdate();
        }
    }

    @Nested
    @DisplayName("缓存路径")
    class CachePathTests {

        @Test
        @DisplayName("缓存命中时应复用已有访问类型（合法 SQL）")
        void shouldReuseCachedAccessTypeWhenPresent() throws SQLException {
            PreparedStatement target = mock(PreparedStatement.class);
            ResultSet resultSet = mock(ResultSet.class);
            PermissionService permissionService = mock(PermissionService.class);
            SqlParseCache.put("ling-a", SELECT_SQL,
                    new SqlPermissionSupport.SqlPermissionPlan(AccessType.READ, Collections.emptyList()));
            when(permissionService.isAllowed("ling-a", "storage:sql", AccessType.READ)).thenReturn(true);
            when(target.executeQuery()).thenReturn(resultSet);

            LingCallContext.setLingId("ling-a");
            LingPreparedStatementProxy proxy = new LingPreparedStatementProxy(target, permissionService, SELECT_SQL, null);

            // executeQuery() 返回 LingResultSetProxy 包装
            assertInstanceOf(LingResultSetProxy.class, proxy.executeQuery());
            verify(permissionService).isAllowed("ling-a", "storage:sql", AccessType.READ);
            verify(permissionService).audit("ling-a", "storage:sql", SELECT_SQL, true);
        }
    }

    @Nested
    @DisplayName("PreparedStatement 委托转发测试")
    class PreparedStatementDelegateTests {

        @Test
        @DisplayName("覆盖所有直接转发的接口方法和 setter")
        void testAllForwardingMethods() throws Exception {
            PreparedStatement target = mock(PreparedStatement.class);
            PermissionService permissionService = mock(PermissionService.class);
            // 传入 lingConnection 以验证 getConnection() 返回代理而非原生 Connection
            LingConnectionProxy lingConnection = mock(LingConnectionProxy.class);
            LingPreparedStatementProxy proxy = new LingPreparedStatementProxy(target, permissionService, "select 1", lingConnection);

            proxy.close();
            verify(target).close();

            proxy.setString(1, "str");
            verify(target).setString(1, "str");

            proxy.setInt(1, 10);
            verify(target).setInt(1, 10);

            proxy.setLong(1, 100L);
            verify(target).setLong(1, 100L);

            SQLType sqlType = mock(SQLType.class);
            proxy.setObject(1, "obj", sqlType, 2);
            verify(target).setObject(1, "obj", sqlType, 2);

            proxy.setObject(1, "obj", sqlType);
            verify(target).setObject(1, "obj", sqlType);

            // unwrap / isWrapperFor
            assertSame(proxy, proxy.unwrap(LingPreparedStatementProxy.class));
            assertSame(proxy, proxy.unwrap(PreparedStatement.class));
            // unwrap 到非代理接口时拒绝暴露原生实现，防止绕过治理
            assertThrows(SQLException.class, () -> proxy.unwrap(String.class));

            assertTrue(proxy.isWrapperFor(PreparedStatement.class));
            when(target.isWrapperFor(String.class)).thenReturn(false);
            assertFalse(proxy.isWrapperFor(String.class));

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

            // addBatch(String sql) 现在抛 SQLException，防止通过新 SQL 绕过预编译权限治理
            assertThrows(SQLException.class, () -> proxy.addBatch("sql"));
            verify(target, never()).addBatch("sql");

            proxy.clearBatch();
            verify(target).clearBatch();

            // getConnection() 返回 lingConnection 代理，不调用 target.getConnection()
            assertSame(lingConnection, proxy.getConnection());
            verify(target, never()).getConnection();

            proxy.getMoreResults(1);
            verify(target).getMoreResults(1);

            proxy.getGeneratedKeys();
            verify(target).getGeneratedKeys();

            proxy.getResultSetHoldability();
            verify(target).getResultSetHoldability();

            proxy.isClosed();
            verify(target).isClosed();

            proxy.setPoolable(true);
            verify(target).setPoolable(true);

            proxy.isPoolable();
            verify(target).isPoolable();

            proxy.closeOnCompletion();
            verify(target).closeOnCompletion();

            proxy.isCloseOnCompletion();
            verify(target).isCloseOnCompletion();

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

            proxy.setNull(1, 1);
            verify(target).setNull(1, 1);

            proxy.setBoolean(1, true);
            verify(target).setBoolean(1, true);

            proxy.setByte(1, (byte)1);
            verify(target).setByte(1, (byte)1);

            proxy.setShort(1, (short)1);
            verify(target).setShort(1, (short)1);

            proxy.setFloat(1, 1.0f);
            verify(target).setFloat(1, 1.0f);

            proxy.setDouble(1, 1.0);
            verify(target).setDouble(1, 1.0);

            BigDecimal bd = new BigDecimal("1.0");
            proxy.setBigDecimal(1, bd);
            verify(target).setBigDecimal(1, bd);

            proxy.setBytes(1, new byte[0]);
            verify(target).setBytes(1, new byte[0]);

            Date date = new Date(0L);
            proxy.setDate(1, date);
            verify(target).setDate(1, date);

            Time time = new Time(0L);
            proxy.setTime(1, time);
            verify(target).setTime(1, time);

            Timestamp ts = new Timestamp(0L);
            proxy.setTimestamp(1, ts);
            verify(target).setTimestamp(1, ts);

            InputStream is = mock(InputStream.class);
            proxy.setAsciiStream(1, is, 1);
            verify(target).setAsciiStream(1, is, 1);

            proxy.setUnicodeStream(1, is, 1);
            verify(target).setUnicodeStream(1, is, 1);

            proxy.setBinaryStream(1, is, 1);
            verify(target).setBinaryStream(1, is, 1);

            proxy.clearParameters();
            verify(target).clearParameters();

            proxy.setObject(1, "obj", 1);
            verify(target).setObject(1, "obj", 1);

            proxy.setObject(1, "obj");
            verify(target).setObject(1, "obj");

            Reader reader = mock(Reader.class);
            proxy.setCharacterStream(1, reader, 1);
            verify(target).setCharacterStream(1, reader, 1);

            Ref ref = mock(Ref.class);
            proxy.setRef(1, ref);
            verify(target).setRef(1, ref);

            Blob blob = mock(Blob.class);
            proxy.setBlob(1, blob);
            verify(target).setBlob(1, blob);

            Clob clob = mock(Clob.class);
            proxy.setClob(1, clob);
            verify(target).setClob(1, clob);

            Array array = mock(Array.class);
            proxy.setArray(1, array);
            verify(target).setArray(1, array);

            proxy.getMetaData();
            verify(target).getMetaData();

            Calendar cal = Calendar.getInstance();
            proxy.setDate(1, date, cal);
            verify(target).setDate(1, date, cal);

            proxy.setTime(1, time, cal);
            verify(target).setTime(1, time, cal);

            proxy.setTimestamp(1, ts, cal);
            verify(target).setTimestamp(1, ts, cal);

            proxy.setNull(1, 1, "name");
            verify(target).setNull(1, 1, "name");

            URL url = new URL("http://localhost");
            proxy.setURL(1, url);
            verify(target).setURL(1, url);

            proxy.getParameterMetaData();
            verify(target).getParameterMetaData();

            RowId rowId = mock(RowId.class);
            proxy.setRowId(1, rowId);
            verify(target).setRowId(1, rowId);

            proxy.setNString(1, "str");
            verify(target).setNString(1, "str");

            proxy.setNCharacterStream(1, reader, 1L);
            verify(target).setNCharacterStream(1, reader, 1L);

            NClob nclob = mock(NClob.class);
            proxy.setNClob(1, nclob);
            verify(target).setNClob(1, nclob);

            proxy.setClob(1, reader, 1L);
            verify(target).setClob(1, reader, 1L);

            proxy.setBlob(1, is, 1L);
            verify(target).setBlob(1, is, 1L);

            proxy.setNClob(1, reader, 1L);
            verify(target).setNClob(1, reader, 1L);

            SQLXML xml = mock(SQLXML.class);
            proxy.setSQLXML(1, xml);
            verify(target).setSQLXML(1, xml);

            proxy.setObject(1, "obj", 1, 1);
            verify(target).setObject(1, "obj", 1, 1);

            proxy.setAsciiStream(1, is, 1L);
            verify(target).setAsciiStream(1, is, 1L);

            proxy.setBinaryStream(1, is, 1L);
            verify(target).setBinaryStream(1, is, 1L);

            proxy.setCharacterStream(1, reader, 1L);
            verify(target).setCharacterStream(1, reader, 1L);

            proxy.setAsciiStream(1, is);
            verify(target).setAsciiStream(1, is);

            proxy.setBinaryStream(1, is);
            verify(target).setBinaryStream(1, is);

            proxy.setCharacterStream(1, reader);
            verify(target).setCharacterStream(1, reader);

            proxy.setNCharacterStream(1, reader);
            verify(target).setNCharacterStream(1, reader);

            proxy.setClob(1, reader);
            verify(target).setClob(1, reader);

            proxy.setBlob(1, is);
            verify(target).setBlob(1, is);

            proxy.setNClob(1, reader);
            verify(target).setNClob(1, reader);
        }

        @Test
        @DisplayName("测试 checkPermission 保护的方法")
        void testPermissionMethods() throws Exception {
            PreparedStatement target = mock(PreparedStatement.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isLingCoreGovernanceEnabled()).thenReturn(false);

            LingPreparedStatementProxy proxy = new LingPreparedStatementProxy(target, permissionService, "select 1", null);

            proxy.execute();
            verify(target).execute();

            proxy.executeLargeUpdate();
            verify(target).executeLargeUpdate();

            proxy.executeBatch();
            verify(target).executeBatch();

            proxy.addBatch();
            verify(target).addBatch();

            // 以下 String sql 重载方法均应抛 SQLException：预编译语句禁止新 SQL 绕过权限治理
            assertThrows(SQLException.class, () -> proxy.executeUpdate("update users set x=1", 1));
            verify(target, never()).executeUpdate("update users set x=1", 1);

            assertThrows(SQLException.class, () -> proxy.executeUpdate("update users set x=1", new int[0]));
            verify(target, never()).executeUpdate("update users set x=1", new int[0]);

            assertThrows(SQLException.class, () -> proxy.executeUpdate("update users set x=1", new String[0]));
            verify(target, never()).executeUpdate("update users set x=1", new String[0]);

            assertThrows(SQLException.class, () -> proxy.execute("select 1", 1));
            verify(target, never()).execute("select 1", 1);

            assertThrows(SQLException.class, () -> proxy.execute("select 1", new int[0]));
            verify(target, never()).execute("select 1", new int[0]);

            assertThrows(SQLException.class, () -> proxy.execute("select 1", new String[0]));
            verify(target, never()).execute("select 1", new String[0]);

            assertThrows(SQLException.class, () -> proxy.executeUpdate("update users set x=1"));
            verify(target, never()).executeUpdate("update users set x=1");

            assertThrows(SQLException.class, () -> proxy.execute("select 1"));
            verify(target, never()).execute("select 1");

            assertThrows(SQLException.class, () -> proxy.executeQuery("select 1"));
            verify(target, never()).executeQuery("select 1");
        }
    }
}
