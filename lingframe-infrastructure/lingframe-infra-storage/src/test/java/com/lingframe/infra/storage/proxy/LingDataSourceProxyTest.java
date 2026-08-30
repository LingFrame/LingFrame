package com.lingframe.infra.storage.proxy;

import com.lingframe.api.security.PermissionService;
import com.lingframe.api.storage.LingTransactionContext;
import org.junit.jupiter.api.AfterEach;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("LingDataSourceProxy 测试")
class LingDataSourceProxyTest {

    @AfterEach
    void tearDown() {
        // 每个用例后清空穿透上下文，防止 ThreadLocal 跨用例残留污染
        LingTransactionContext.clear();
    }

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
        @DisplayName("带用户名密码的 getConnection 应被禁止——强制使用灵核配置的凭据")
        void shouldRejectGetConnectionWithCredentials() throws SQLException {
            DataSource target = mock(DataSource.class);
            PermissionService permissionService = mock(PermissionService.class);

            LingDataSourceProxy proxy = new LingDataSourceProxy(target, permissionService);

            assertThrows(SQLException.class, () -> proxy.getConnection("user", "pwd"));
            verify(target, never()).getConnection("user", "pwd");
        }
    }

    @Nested
    @DisplayName("身份门控：受管代理穿透复用")
    class ManagedIdentityGateTests {

        @Test
        @DisplayName("受管代理（dataSourceId 非 null）栈中有同 id 连接 → 复用并返回 NonCloseable，不向池借新连接")
        void managedProxyReusesStackConnection() throws SQLException {
            DataSource target = mock(DataSource.class);
            PermissionService permissionService = mock(PermissionService.class);
            LingDataSourceProxy proxy = new LingDataSourceProxy(target, permissionService, "default");

            // 穿透上下文栈中有同 id 连接（模拟 TransactionPropagationFilter 已压栈）
            Connection txConnection = mock(Connection.class);
            when(txConnection.isClosed()).thenReturn(false);
            LingTransactionContext.pushConnection("default", txConnection);

            Connection result = proxy.getConnection();

            assertInstanceOf(NonCloseableLingConnectionProxy.class, result);
            // 复用穿透连接，绝不向底层池借新连接
            verify(target, never()).getConnection();
            LingTransactionContext.popConnection();
        }

        @Test
        @DisplayName("受管代理栈中无同 id 连接 → 从池借出并返回 LingConnectionProxy")
        void managedProxyFallsBackToPoolWhenStackEmpty() throws SQLException {
            DataSource target = mock(DataSource.class);
            Connection poolConnection = mock(Connection.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(target.getConnection()).thenReturn(poolConnection);

            LingDataSourceProxy proxy = new LingDataSourceProxy(target, permissionService, "order-ds");

            Connection result = proxy.getConnection();

            assertInstanceOf(LingConnectionProxy.class, result);
            verify(target).getConnection();
        }

        @Test
        @DisplayName("受管代理按自身 id 精确查栈：其他源的连接不误用（防串库）")
        void managedProxyIgnoresOtherSourceConnections() throws SQLException {
            DataSource target = mock(DataSource.class);
            Connection poolConnection = mock(Connection.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(target.getConnection()).thenReturn(poolConnection);

            LingDataSourceProxy proxy = new LingDataSourceProxy(target, permissionService, "order-ds");

            // 栈中只有 "default" 源的连接，order-ds 代理必须无视它（防串库）
            Connection defaultConn = mock(Connection.class);
            LingTransactionContext.pushConnection("default", defaultConn);

            Connection result = proxy.getConnection();

            assertInstanceOf(LingConnectionProxy.class, result);
            verify(target).getConnection();
            LingTransactionContext.popConnection();
        }

        @Test
        @DisplayName("受管代理命中栈连接但连接已关闭 → 回退池借出")
        void managedProxyFallsBackWhenStackConnectionClosed() throws SQLException {
            DataSource target = mock(DataSource.class);
            Connection poolConnection = mock(Connection.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(target.getConnection()).thenReturn(poolConnection);

            LingDataSourceProxy proxy = new LingDataSourceProxy(target, permissionService, "default");

            Connection closedTx = mock(Connection.class);
            when(closedTx.isClosed()).thenReturn(true);
            LingTransactionContext.pushConnection("default", closedTx);

            Connection result = proxy.getConnection();

            assertInstanceOf(LingConnectionProxy.class, result);
            verify(target).getConnection();
            LingTransactionContext.popConnection();
        }
    }

    @Nested
    @DisplayName("身份门控：私有池代理永不查栈")
    class PrivatePoolGateTests {

        @Test
        @DisplayName("私有池代理（dataSourceId null）即使栈中有受管连接也永不复用（混合链路防串库）")
        void privateProxyNeverQueriesStack() throws SQLException {
            DataSource target = mock(DataSource.class);
            Connection poolConnection = mock(Connection.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(target.getConnection()).thenReturn(poolConnection);

            // 旧构造器 = dataSourceId null（模式 2 私有池路径）
            LingDataSourceProxy proxy = new LingDataSourceProxy(target, permissionService);

            // 栈中已有受管穿透连接（混合链路：受管事务中调用私有库灵元）
            Connection txConnection = mock(Connection.class);
            when(txConnection.isClosed()).thenReturn(false);
            LingTransactionContext.pushConnection("default", txConnection);

            Connection result = proxy.getConnection();

            // 私有代理永不查栈：即使栈非空也借自身池连接，串库路径被物理切断
            assertInstanceOf(LingConnectionProxy.class, result);
            verify(target).getConnection();
            LingTransactionContext.popConnection();
        }

        @Test
        @DisplayName("显式 null dataSourceId 构造器与旧构造器行为一致")
        void explicitNullIdBehavesLikeLegacyConstructor() throws SQLException {
            DataSource target = mock(DataSource.class);
            Connection poolConnection = mock(Connection.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(target.getConnection()).thenReturn(poolConnection);

            LingDataSourceProxy explicit = new LingDataSourceProxy(target, permissionService, null);
            LingDataSourceProxy legacy = new LingDataSourceProxy(target, permissionService);

            assertInstanceOf(LingConnectionProxy.class, explicit.getConnection());
            assertInstanceOf(LingConnectionProxy.class, legacy.getConnection());
            verify(target, org.mockito.Mockito.times(2)).getConnection();
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
            // unwrap 到非代理接口时拒绝暴露原生实现，防止绕过治理
            assertThrows(SQLException.class, () -> proxy.unwrap(String.class));

            assertTrue(proxy.isWrapperFor(DataSource.class));
            when(target.isWrapperFor(String.class)).thenReturn(false);
            assertFalse(proxy.isWrapperFor(String.class));
        }
    }
}
