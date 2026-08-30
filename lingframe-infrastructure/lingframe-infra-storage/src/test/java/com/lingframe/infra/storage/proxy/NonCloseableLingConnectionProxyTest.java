package com.lingframe.infra.storage.proxy;

import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import com.lingframe.api.storage.LingTransactionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 不可物理关闭连接代理测试：审计不降级——no-op 降级的只是物理行为，
 * 权限检查（checkTransactionPermission）与审计事件（downstream-*-suppressed）全部保留；
 * rollback 仅置 LingTransactionContext rollbackOnly 信号。
 */
@DisplayName("NonCloseableLingConnectionProxy 穿透连接非关闭代理")
class NonCloseableLingConnectionProxyTest {

    private static final String LING_ID = "demo-ling";

    private final Connection target = mock(Connection.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final NonCloseableLingConnectionProxy proxy =
            new NonCloseableLingConnectionProxy(target, permissionService);

    @AfterEach
    void tearDown() {
        LingCallContext.clear();
        LingTransactionContext.clear();
    }

    private void grantPermission() {
        when(permissionService.isAllowed(LING_ID, "storage:sql:transaction", null)).thenReturn(true);
    }

    @Nested
    @DisplayName("审计不降级：物理行为 no-op，权限门与审计保留")
    class AuditNotDegraded {

        @Test
        @DisplayName("commit 不物理提交，但权限检查与 suppressed 审计保留")
        void commitSuppressesPhysicalButKeepsGovernance() throws SQLException {
            LingCallContext.setLingId(LING_ID);
            when(permissionService.isAllowed(LING_ID, "storage:sql:transaction",
                    AccessType.WRITE)).thenReturn(true);

            proxy.commit();

            // 物理提交被抑制
            verify(target, never()).commit();
            // suppressed 审计事件产生（审计不降级）
            verify(permissionService).audit(LING_ID, "storage:sql:transaction",
                    "transaction:commit-suppressed", false);
        }

        @Test
        @DisplayName("commit 权限被拒时照常抛出——no-op 不豁免治理门")
        void commitRejectedWhenPermissionDenied() throws SQLException {
            LingCallContext.setLingId(LING_ID);
            when(permissionService.isAllowed(LING_ID, "storage:sql:transaction",
                    AccessType.WRITE)).thenReturn(false);

            assertThrows(SQLException.class, () -> proxy.commit());
            verify(target, never()).commit();
        }

        @Test
        @DisplayName("rollback 不物理回滚，仅置 rollbackOnly 信号，且权限检查保留")
        void rollbackMarksRollbackOnlyNotPhysical() throws SQLException {
            LingCallContext.setLingId(LING_ID);
            when(permissionService.isAllowed(LING_ID, "storage:sql:transaction",
                    AccessType.WRITE)).thenReturn(true);
            assertFalse(LingTransactionContext.isRollbackOnly());

            proxy.rollback();

            // 物理回滚被抑制
            verify(target, never()).rollback();
            // 信号置位（上行回传的写入端）
            assertTrue(LingTransactionContext.isRollbackOnly());
        }

        @Test
        @DisplayName("rollback 权限被拒时照常抛出，且不置 rollbackOnly")
        void rollbackRejectedWhenPermissionDenied() throws SQLException {
            LingCallContext.setLingId(LING_ID);
            when(permissionService.isAllowed(LING_ID, "storage:sql:transaction",
                    AccessType.WRITE)).thenReturn(false);

            assertThrows(SQLException.class, () -> proxy.rollback());
            assertFalse(LingTransactionContext.isRollbackOnly());
        }

        @Test
        @DisplayName("setAutoCommit 不物理执行，但权限检查保留")
        void setAutoCommitSuppressedWithPermissionCheck() throws SQLException {
            LingCallContext.setLingId(LING_ID);
            when(permissionService.isAllowed(LING_ID, "storage:sql:transaction",
                    AccessType.WRITE)).thenReturn(true);

            proxy.setAutoCommit(true);

            verify(target, never()).setAutoCommit(true);
            verify(permissionService).audit(LING_ID, "storage:sql:transaction",
                    "transaction:setAutoCommit-suppressed", false);
        }

        @Test
        @DisplayName("setAutoCommit 权限被拒时照常抛出")
        void setAutoCommitRejectedWhenPermissionDenied() throws SQLException {
            LingCallContext.setLingId(LING_ID);
            when(permissionService.isAllowed(LING_ID, "storage:sql:transaction",
                    AccessType.WRITE)).thenReturn(false);

            assertThrows(SQLException.class, () -> proxy.setAutoCommit(false));
            verify(target, never()).setAutoCommit(false);
        }
    }

    @Nested
    @DisplayName("根连接属性防篡改：no-op + 审计，无权限门（仅记录）")
    class RootPropertyTamperGuard {

        @Test
        @DisplayName("setTransactionIsolation 被抑制并记录审计，不物理执行")
        void isolationChangeSuppressed() throws SQLException {
            LingCallContext.setLingId(LING_ID);

            proxy.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

            verify(target, never()).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            verify(permissionService).audit(LING_ID, "storage:sql:transaction",
                    "transaction:isolation-suppressed", false);
        }

        @Test
        @DisplayName("setReadOnly 被抑制并记录审计，不物理执行")
        void readOnlyChangeSuppressed() throws SQLException {
            LingCallContext.setLingId(LING_ID);

            proxy.setReadOnly(true);

            verify(target, never()).setReadOnly(true);
            verify(permissionService).audit(LING_ID, "storage:sql:transaction",
                    "transaction:readonly-suppressed", false);
        }

        @Test
        @DisplayName("setHoldability 被抑制并记录审计，不物理执行")
        void holdabilityChangeSuppressed() throws SQLException {
            LingCallContext.setLingId(LING_ID);
            int holdability = ResultSet.HOLD_CURSORS_OVER_COMMIT;

            proxy.setHoldability(holdability);

            verify(target, never()).setHoldability(holdability);
            verify(permissionService).audit(LING_ID, "storage:sql:transaction",
                    "transaction:holdability-suppressed", false);
        }

        @Test
        @DisplayName("属性防篡改方法权限被拒不抛（无权限门）——隔离级别仍静默 no-op + 审计")
        void propertyTamperNoPermissionGate() throws SQLException {
            LingCallContext.setLingId(LING_ID);
            // 权限被拒：属性防篡改方法不设权限门，权限状态不影响 no-op 与审计
            when(permissionService.isAllowed(LING_ID, "storage:sql:transaction",
                    AccessType.WRITE)).thenReturn(false);

            proxy.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

            verify(target, never()).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            verify(permissionService).audit(LING_ID, "storage:sql:transaction",
                    "transaction:isolation-suppressed", false);
        }
    }

    @Nested
    @DisplayName("审计通道降级")
    class AuditChannelDegradation {

        @Test
        @DisplayName("rollback 置 rollbackOnly 信号，且产生 suppressed 审计事件（物理回滚被抑制须可观测）")
        void rollbackEmitsSuppressedAudit() throws SQLException {
            LingCallContext.setLingId(LING_ID);
            when(permissionService.isAllowed(LING_ID, "storage:sql:transaction",
                    AccessType.WRITE)).thenReturn(true);

            proxy.rollback();

            // 信号上行（回滚意图）——与 commit/setAutoCommit 一致，物理回滚被抑制记 suppressed 审计
            assertTrue(LingTransactionContext.isRollbackOnly());
            verify(permissionService).audit(LING_ID, "storage:sql:transaction",
                    "transaction:rollback-suppressed", false);
        }

        @Test
        @DisplayName("无灵元上下文时 suppressed 审计降级为 debug 日志，不调用 audit")
        void noLingContextSkipsAudit() throws SQLException {
            // 不设置 LingCallContext.lingId：auditSuppressed 只记 debug 日志，不产生审计事件
            LingCallContext.clear();

            proxy.setAutoCommit(true);

            verify(permissionService, never()).audit(any(String.class), any(String.class),
                    any(String.class), any(Boolean.class));
        }
    }

    @Nested
    @DisplayName("生命周期：close 为空实现")
    class Lifecycle {

        @Test
        @DisplayName("close 不物理关闭——生命周期交由上游事务发起方管辖")
        void closeIsNoOp() throws SQLException {
            proxy.close();
            verify(target, never()).close();
        }
    }

    @Nested
    @DisplayName("单层 Statement 委托（内层已治理，薄代理只修正视图）")
    class SingleLayerStatementDelegation {

        @Test
        @DisplayName("prepareStatement 直通内层：转发调用，getConnection 视图指向本代理（防绕过）")
        void prepareStatementDelegatesAndFixesView() throws Exception {
            PreparedStatement innerPs = mock(PreparedStatement.class);
            when(target.prepareStatement("select 1")).thenReturn(innerPs);

            PreparedStatement delegated = proxy.prepareStatement("select 1");

            // 视图修正：Statement.getConnection() 返回本代理（不可物理关闭）
            assertSame(proxy, delegated.getConnection());
            // 调用转发内层（治理由内层完成，不再重复包一层）
            delegated.executeQuery();
            verify(innerPs).executeQuery();
            // 内层工厂只被调用一次（委托直通，未重复包装）
            verify(target, times(1)).prepareStatement("select 1");
        }

        @Test
        @DisplayName("createStatement 直通内层，getConnection 视图修正")
        void createStatementDelegatesAndFixesView() throws Exception {
            Statement innerStmt = mock(Statement.class);
            when(target.createStatement()).thenReturn(innerStmt);

            Statement delegated = proxy.createStatement();

            assertSame(proxy, delegated.getConnection());
            delegated.execute("select 1");
            verify(innerStmt).execute("select 1");
        }

        @Test
        @DisplayName("prepareCall 直通内层，getConnection 视图修正")
        void prepareCallDelegatesAndFixesView() throws Exception {
            CallableStatement innerCs = mock(CallableStatement.class);
            when(target.prepareCall("{call p()}")).thenReturn(innerCs);

            CallableStatement delegated = proxy.prepareCall("{call p()}");

            assertSame(proxy, delegated.getConnection());
        }

        @Test
        @DisplayName("内层抛 SQLException 时原样上抛（不包 InvocationTargetException）")
        void innerSqlExceptionPropagatesAsIs() throws Exception {
            PreparedStatement innerPs = mock(PreparedStatement.class);
            when(target.prepareStatement("select 1")).thenReturn(innerPs);
            doThrow(new SQLException("driver boom")).when(innerPs).executeQuery();

            PreparedStatement delegated = proxy.prepareStatement("select 1");

            SQLException ex = assertThrows(SQLException.class, delegated::executeQuery);
            assertTrue(ex.getMessage().contains("driver boom"));
        }
    }
}
