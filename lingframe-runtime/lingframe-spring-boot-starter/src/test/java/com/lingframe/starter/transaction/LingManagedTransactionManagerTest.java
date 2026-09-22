package com.lingframe.starter.transaction;

import com.lingframe.api.storage.LingTransactionContext;
import com.lingframe.api.storage.LingTransactionRollbackException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 受管模式双路径事务管理器测试：根路径（灵元为事务根）与加入路径（栈非空，加入根事务），
 * REQUIRES_NEW 降级、非根 commit 检测 rollbackOnly、TSM 资源绑定全程不激活。
 */
@DisplayName("LingManagedTransactionManager 双路径事务管理器")
class LingManagedTransactionManagerTest {

    private static final String DATA_SOURCE_ID = "default";

    @AfterEach
    void tearDown() {
        LingTransactionContext.clear();
    }

    private DataSource mockDataSource(Connection conn) throws Exception {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(conn);
        return ds;
    }

    @Nested
    @DisplayName("根路径（灵元为事务根）")
    class RootPath {

        @Test
        @DisplayName("栈空时借连接 setAutoCommit(false) push，判为根事务")
        void beginsRootTransaction() throws Exception {
            Connection conn = mock(Connection.class);
            LingManagedTransactionManager tm =
                    new LingManagedTransactionManager(mockDataSource(conn), DATA_SOURCE_ID);

            TransactionStatus status = tm.getTransaction(TransactionDefinition.withDefaults());

            assertTrue(status.isNewTransaction());
            verify(conn).setAutoCommit(false);
            // 连接已 push 进穿透上下文
            assertTrue(LingTransactionContext.hasAnyConnection());
            LingTransactionContext.popConnection();
        }

        @Test
        @DisplayName("根路径 commit 物理提交并归还连接")
        void commitPhysicalForRoot() throws Exception {
            Connection conn = mock(Connection.class);
            LingManagedTransactionManager tm =
                    new LingManagedTransactionManager(mockDataSource(conn), DATA_SOURCE_ID);

            TransactionStatus status = tm.getTransaction(TransactionDefinition.withDefaults());
            tm.commit(status);

            verify(conn).commit();
            verify(conn).close();
            assertFalse(LingTransactionContext.hasAnyConnection());
        }

        @Test
        @DisplayName("归还后不手动复位 autoCommit/isolation（复位交由连接池 reset）")
        void poolResetNotManualOnReturn() throws Exception {
            Connection conn = mock(Connection.class);
            LingManagedTransactionManager tm =
                    new LingManagedTransactionManager(mockDataSource(conn), DATA_SOURCE_ID);

            TransactionStatus status = tm.getTransaction(TransactionDefinition.withDefaults());
            tm.commit(status);

            // 归还语义：TM 只 close 归还，不越权复位 autoCommit/isolation——由池自身 reset
            verify(conn, never()).setAutoCommit(true);
            verify(conn, never()).setTransactionIsolation(anyInt());
            verify(conn, never()).setReadOnly(anyBoolean());
        }

        @Test
        @DisplayName("根路径 rollback 物理回滚并归还连接")
        void rollbackPhysicalForRoot() throws Exception {
            Connection conn = mock(Connection.class);
            LingManagedTransactionManager tm =
                    new LingManagedTransactionManager(mockDataSource(conn), DATA_SOURCE_ID);

            TransactionStatus status = tm.getTransaction(TransactionDefinition.withDefaults());
            tm.rollback(status);

            verify(conn).rollback();
            verify(conn).close();
            assertFalse(LingTransactionContext.hasAnyConnection());
        }

        @Test
        @DisplayName("根路径借连接失败时抛 CannotCreateTransactionException")
        void rootBeginFailureThrows() throws Exception {
            DataSource ds = mock(DataSource.class);
            when(ds.getConnection()).thenThrow(new SQLException("pool exhausted"));
            LingManagedTransactionManager tm = new LingManagedTransactionManager(ds, DATA_SOURCE_ID);

            assertThrows(CannotCreateTransactionException.class,
                    () -> tm.getTransaction(TransactionDefinition.withDefaults()));
        }

        @Test
        @DisplayName("借出成功但属性设置失败 → 已借连接被归还（防池连接泄漏）")
        void rootBeginAttributeFailureReturnsBorrowedConnection() throws Exception {
            Connection conn = mock(Connection.class);
            // 借出成功，但 setAutoCommit 抛异常（模拟无事务权限/驱动异常路径）
            DataSource ds = mock(DataSource.class);
            when(ds.getConnection()).thenReturn(conn);
            doThrow(new SQLException("no transaction permission"))
                    .when(conn).setAutoCommit(false);
            LingManagedTransactionManager tm = new LingManagedTransactionManager(ds, DATA_SOURCE_ID);

            assertThrows(CannotCreateTransactionException.class,
                    () -> tm.getTransaction(TransactionDefinition.withDefaults()));

            // 关键断言：失败路径下连接必须归还池，不允许泄漏
            verify(conn).close();
            // 且未入穿透上下文（push 在 setAutoCommit 之后，失败时不应残留）
            assertFalse(LingTransactionContext.hasAnyConnection());
        }

        @Test
        @DisplayName("根路径 commit 连接缺失（上下文被清空/poisoned）→ 抛 TransactionSystemException 而非 NPE")
        void rootCommitMissingConnectionThrows() throws Exception {
            // 手动清空上下文，模拟 poisoned 废弃后残留状态（连接已 close 出栈）
            LingTransactionContext.clear();
            LingManagedTransactionManager tm =
                    new LingManagedTransactionManager(mockDataSource(mock(Connection.class)), DATA_SOURCE_ID);

            TransactionStatus status = tm.getTransaction(TransactionDefinition.withDefaults());
            LingTransactionContext.clear(); // 在 commit 前清空，制造连接缺失

            assertThrows(TransactionSystemException.class, () -> tm.commit(status));
        }

        @Test
        @DisplayName("根路径 rollback 连接缺失 → 抛 TransactionSystemException 而非 NPE")
        void rootRollbackMissingConnectionThrows() throws Exception {
            LingTransactionContext.clear();
            LingManagedTransactionManager tm =
                    new LingManagedTransactionManager(mockDataSource(mock(Connection.class)), DATA_SOURCE_ID);

            TransactionStatus status = tm.getTransaction(TransactionDefinition.withDefaults());
            LingTransactionContext.clear();

            assertThrows(TransactionSystemException.class, () -> tm.rollback(status));
        }
    }

    @Nested
    @DisplayName("加入路径（栈非空，加入根事务）")
    class JoinPath {

        @Test
        @DisplayName("栈非空时判为加入，不借连接不碰 TSM，不 push")
        void joinsExistingRoot() throws Exception {
            Connection rootConn = mock(Connection.class);
            DataSource ds = mock(DataSource.class);
            LingManagedTransactionManager tm = new LingManagedTransactionManager(ds, DATA_SOURCE_ID);

            // 模拟根事务已由更上层 push（灵核侧 Filter 压栈）
            LingTransactionContext.pushConnection(DATA_SOURCE_ID, rootConn);

            TransactionStatus status = tm.getTransaction(TransactionDefinition.withDefaults());

            assertFalse(status.isNewTransaction());
            // 不向池借连接（加入路径不碰连接）
            verify(ds, never()).getConnection();
            LingTransactionContext.popConnection();
        }

        @Test
        @DisplayName("加入路径 commit 无物理动作（物理提交权归根事务发起方）")
        void joinCommitNoPhysicalAction() throws Exception {
            Connection rootConn = mock(Connection.class);
            DataSource ds = mock(DataSource.class);
            LingManagedTransactionManager tm = new LingManagedTransactionManager(ds, DATA_SOURCE_ID);
            LingTransactionContext.pushConnection(DATA_SOURCE_ID, rootConn);

            TransactionStatus status = tm.getTransaction(TransactionDefinition.withDefaults());
            tm.commit(status);

            verify(rootConn, never()).commit();
            verify(rootConn, never()).close();
            verify(ds, never()).getConnection();
            LingTransactionContext.popConnection();
        }

        @Test
        @DisplayName("加入路径 rollback 仅置 rollbackOnly 信号，无物理回滚")
        void joinRollbackMarksSignalOnly() throws Exception {
            Connection rootConn = mock(Connection.class);
            DataSource ds = mock(DataSource.class);
            LingManagedTransactionManager tm = new LingManagedTransactionManager(ds, DATA_SOURCE_ID);
            LingTransactionContext.pushConnection(DATA_SOURCE_ID, rootConn);

            TransactionStatus status = tm.getTransaction(TransactionDefinition.withDefaults());
            tm.rollback(status);

            verify(rootConn, never()).rollback();
            assertTrue(LingTransactionContext.isRollbackOnly());
            LingTransactionContext.popConnection();
        }

        @Test
        @DisplayName("加入路径 commit 时 rollbackOnly 已置位 → 抛 LingTransactionRollbackException（UnexpectedRollback 语义）")
        void joinCommitDetectsRollbackOnly() throws Exception {
            Connection rootConn = mock(Connection.class);
            DataSource ds = mock(DataSource.class);
            LingManagedTransactionManager tm = new LingManagedTransactionManager(ds, DATA_SOURCE_ID);
            LingTransactionContext.pushConnection(DATA_SOURCE_ID, rootConn);

            TransactionStatus status = tm.getTransaction(TransactionDefinition.withDefaults());
            // 下游已声明回滚（吞异常场景）
            LingTransactionContext.setRollbackOnly();

            assertThrows(LingTransactionRollbackException.class, () -> tm.commit(status));
            LingTransactionContext.popConnection();
        }
    }

    @Nested
    @DisplayName("传播语义")
    class PropagationSemantics {

        @Test
        @DisplayName("REQUIRES_NEW 显式降级为加入并告警，不抛错")
        void requiresNewDemotedToRequired() throws Exception {
            Connection rootConn = mock(Connection.class);
            DataSource ds = mock(DataSource.class);
            LingManagedTransactionManager tm = new LingManagedTransactionManager(ds, DATA_SOURCE_ID);
            LingTransactionContext.pushConnection(DATA_SOURCE_ID, rootConn);

            // REQUIRES_NEW 物理不可达（共享单一物理连接），显式降级为加入
            TransactionDefinition definition = new TransactionDefinition() {
                @Override
                public int getPropagationBehavior() {
                    return TransactionDefinition.PROPAGATION_REQUIRES_NEW;
                }
            };
            TransactionStatus status = tm.getTransaction(definition);

            assertFalse(status.isNewTransaction());
            verify(ds, never()).getConnection();
            LingTransactionContext.popConnection();
        }

        @Test
        @DisplayName("NOT_SUPPORTED 显式拒绝：声明事务外运行，加入会反转意图（数据完整性受损）")
        void notSupportedRejected() {
            Connection rootConn = mock(Connection.class);
            DataSource ds = mock(DataSource.class);
            LingManagedTransactionManager tm = new LingManagedTransactionManager(ds, DATA_SOURCE_ID);
            LingTransactionContext.pushConnection(DATA_SOURCE_ID, rootConn);

            TransactionDefinition definition = new TransactionDefinition() {
                @Override
                public int getPropagationBehavior() {
                    return TransactionDefinition.PROPAGATION_NOT_SUPPORTED;
                }
            };

            assertThrows(IllegalTransactionStateException.class, () -> tm.getTransaction(definition));
            LingTransactionContext.popConnection();
        }

        @Test
        @DisplayName("NEVER 显式拒绝：存在活跃事务即抛错，静默加入违反契约")
        void neverRejected() {
            Connection rootConn = mock(Connection.class);
            DataSource ds = mock(DataSource.class);
            LingManagedTransactionManager tm = new LingManagedTransactionManager(ds, DATA_SOURCE_ID);
            LingTransactionContext.pushConnection(DATA_SOURCE_ID, rootConn);

            TransactionDefinition definition = new TransactionDefinition() {
                @Override
                public int getPropagationBehavior() {
                    return TransactionDefinition.PROPAGATION_NEVER;
                }
            };

            assertThrows(IllegalTransactionStateException.class, () -> tm.getTransaction(definition));
            LingTransactionContext.popConnection();
        }

        @Test
        @DisplayName("MANDATORY 栈空（无根事务可加入）→ 拒绝而非自开根事务")
        void mandatoryWithoutRootRejected() throws Exception {
            DataSource ds = mock(DataSource.class);
            LingManagedTransactionManager tm = new LingManagedTransactionManager(ds, DATA_SOURCE_ID);

            TransactionDefinition definition = new TransactionDefinition() {
                @Override
                public int getPropagationBehavior() {
                    return TransactionDefinition.PROPAGATION_MANDATORY;
                }
            };

            assertThrows(IllegalTransactionStateException.class, () -> tm.getTransaction(definition));
            // 栈空且被拒绝：不应向池借连接
            verify(ds, never()).getConnection();
        }
    }

    @Nested
    @DisplayName("构造参数防御")
    class ConstructorGuards {

        @Test
        @DisplayName("null managedDataSource 拒绝装配")
        void nullDataSourceRejected() {
            assertThrows(NullPointerException.class,
                    () -> new LingManagedTransactionManager(null, DATA_SOURCE_ID));
        }

        @Test
        @DisplayName("null dataSourceId 拒绝装配")
        void nullDataSourceIdRejected() {
            assertThrows(NullPointerException.class,
                    () -> new LingManagedTransactionManager(mock(DataSource.class), null));
        }
    }

    @Nested
    @DisplayName("根路径属性设置（隔离级别/readOnly 借出时生效）")
    class RootAttributes {

        @Test
        @DisplayName("根路径声明 SERIALIZABLE 隔离级别 → 借出时 setTransactionIsolation")
        void isolationLevelAppliedOnRootBegin() throws Exception {
            Connection conn = mock(Connection.class);
            LingManagedTransactionManager tm =
                    new LingManagedTransactionManager(mockDataSource(conn), DATA_SOURCE_ID);

            TransactionDefinition definition = new TransactionDefinition() {
                @Override
                public int getIsolationLevel() {
                    return Connection.TRANSACTION_SERIALIZABLE;
                }
            };
            tm.getTransaction(definition);

            verify(conn).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            LingTransactionContext.popConnection();
        }

        @Test
        @DisplayName("根路径声明 readOnly → 借出时 setReadOnly(true)")
        void readOnlyAppliedOnRootBegin() throws Exception {
            Connection conn = mock(Connection.class);
            LingManagedTransactionManager tm =
                    new LingManagedTransactionManager(mockDataSource(conn), DATA_SOURCE_ID);

            TransactionDefinition definition = new TransactionDefinition() {
                @Override
                public boolean isReadOnly() {
                    return true;
                }
            };
            tm.getTransaction(definition);

            verify(conn).setReadOnly(true);
            LingTransactionContext.popConnection();
        }

        @Test
        @DisplayName("加入路径不设置隔离级别/readOnly（属性由根事务决定）")
        void joinPathDoesNotSetAttributes() throws Exception {
            Connection rootConn = mock(Connection.class);
            DataSource ds = mock(DataSource.class);
            LingManagedTransactionManager tm = new LingManagedTransactionManager(ds, DATA_SOURCE_ID);
            LingTransactionContext.pushConnection(DATA_SOURCE_ID, rootConn);

            TransactionDefinition definition = new TransactionDefinition() {
                @Override
                public int getIsolationLevel() {
                    return Connection.TRANSACTION_SERIALIZABLE;
                }

                @Override
                public boolean isReadOnly() {
                    return true;
                }
            };
            tm.getTransaction(definition);

            verify(rootConn, never()).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            verify(rootConn, never()).setReadOnly(true);
            LingTransactionContext.popConnection();
        }
    }

    @Nested
    @DisplayName("timeout 不实现声明")
    class TimeoutNotImplemented {

        @Test
        @DisplayName("定义带 timeout 时 TM 不抛错、不启用超时（由流水线 resilience 治理兜底）")
        void timeoutIsIgnoredNotEnforced() throws Exception {
            Connection conn = mock(Connection.class);
            LingManagedTransactionManager tm =
                    new LingManagedTransactionManager(mockDataSource(conn), DATA_SOURCE_ID);

            // timeout 不由本管理器实现：即使声明了 1000ms 超时，getTransaction 也不抛错、
            // 不设置任何连接超时——超时语义由流水线 ResilienceGovernanceFilter 兜底
            TransactionDefinition definition = new TransactionDefinition() {
                @Override
                public int getTimeout() {
                    return 1000;
                }
            };
            TransactionStatus status = tm.getTransaction(definition);

            assertTrue(status.isNewTransaction());
            LingTransactionContext.popConnection();
        }
    }
}
