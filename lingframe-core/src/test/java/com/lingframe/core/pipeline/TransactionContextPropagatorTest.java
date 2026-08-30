package com.lingframe.core.pipeline;

import com.lingframe.api.storage.LingTransactionContext;
import com.lingframe.api.storage.LingTransactionContext.TransactionSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事务上下文跨线程搬运传播器测试：capture / replay / restore 三方法契约、
 * previous 状态管理与释放、restore 合并语义（信号上行 + worker 状态恢复）。
 */
@DisplayName("TransactionContextPropagator 事务跨线程传播器")
class TransactionContextPropagatorTest {

    private static final String DATA_SOURCE_ID = "default";

    @AfterEach
    void tearDown() {
        LingTransactionContext.clear();
    }

    private static Connection mockConnection() {
        return (Connection) Proxy.newProxyInstance(
                TransactionContextPropagatorTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("isClosed".equals(method.getName())) {
                        return false;
                    }
                    return null;
                });
    }

    @Nested
    @DisplayName("capture 捕获快照")
    class Capture {

        @Test
        @DisplayName("捕获各源栈顶连接与当前信号")
        void capturesStackAndSignal() {
            Connection conn = mockConnection();
            LingTransactionContext.pushConnection(DATA_SOURCE_ID, conn);
            LingTransactionContext.setRollbackOnly();

            TransactionContextPropagator propagator = new TransactionContextPropagator();
            TransactionSnapshot snapshot = propagator.capture();

            assertNotNull(snapshot);
            assertTrue(snapshot.isRollbackOnly());
            LingTransactionContext.popConnection();
        }

        @Test
        @DisplayName("无连接无信号时捕获空快照")
        void capturesEmptyState() {
            TransactionContextPropagator propagator = new TransactionContextPropagator();
            TransactionSnapshot snapshot = propagator.capture();

            assertNotNull(snapshot);
            assertFalse(snapshot.isRollbackOnly());
        }
    }

    @Nested
    @DisplayName("replay 重放快照")
    class Replay {

        @Test
        @DisplayName("worker 线程重放后可见下行连接，返回执行前状态")
        void replaysConnectionsIntoWorkerThread() throws Exception {
            Connection conn = mockConnection();
            LingTransactionContext.pushConnection(DATA_SOURCE_ID, conn);
            TransactionContextPropagator propagator = new TransactionContextPropagator();
            TransactionSnapshot snapshot = propagator.capture();

            AtomicReference<TransactionSnapshot> previousRef = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                TransactionSnapshot previous = propagator.replay(snapshot);
                previousRef.set(previous);
                // worker 线程重放后：下行连接进入 worker 穿透上下文
                assertSame(conn, LingTransactionContext.getCurrentConnection(DATA_SOURCE_ID));
            });
            worker.start();
            worker.join();

            assertNotNull(previousRef.get());
            LingTransactionContext.popConnection();
        }

        @Test
        @DisplayName("replay 记录 previous，restore 后释放（可重复 capture）")
        void replayRecordsPreviousThenRestoreReleases() throws Exception {
            Connection conn = mockConnection();
            LingTransactionContext.pushConnection(DATA_SOURCE_ID, conn);
            TransactionContextPropagator propagator = new TransactionContextPropagator();
            TransactionSnapshot snapshot = propagator.capture();

            Thread worker = new Thread(() -> {
                propagator.replay(snapshot);
                // 恢复：合并语义 + 状态清理
                propagator.restore(snapshot);
                // restore 后 worker 线程上下文已清空
                assertFalse(LingTransactionContext.hasAnyConnection());
            });
            worker.start();
            worker.join();

            LingTransactionContext.popConnection();
        }
    }

    @Nested
    @DisplayName("restore 合并语义")
    class RestoreMerge {

        @Test
        @DisplayName("worker 置位 rollbackOnly → 合并进 carrier 快照（上行），worker 状态恢复")
        void mergeWorkerSignalIntoCarrier() throws Exception {
            Connection conn = mockConnection();
            LingTransactionContext.pushConnection(DATA_SOURCE_ID, conn);
            TransactionContextPropagator propagator = new TransactionContextPropagator();
            TransactionSnapshot carrier = propagator.capture();

            Thread worker = new Thread(() -> {
                propagator.replay(carrier);
                // worker 执行期间下游声明回滚
                LingTransactionContext.setRollbackOnly();
                // restore 合并语义：worker 信号并入 carrier，再恢复 worker 为执行前状态
                propagator.restore(carrier);
                // worker 侧信号已擦除（恢复为执行前状态）
                assertFalse(LingTransactionContext.isRollbackOnly());
            });
            worker.start();
            worker.join();

            // 信号上行：主线程经 carrier 读取 worker 置位的 rollbackOnly
            assertTrue(carrier.isRollbackOnly());
            LingTransactionContext.popConnection();
        }

        @Test
        @DisplayName("worker 未置位 → carrier 保持无信号（不误报）")
        void noSignalWhenWorkerClean() throws Exception {
            Connection conn = mockConnection();
            LingTransactionContext.pushConnection(DATA_SOURCE_ID, conn);
            TransactionContextPropagator propagator = new TransactionContextPropagator();
            TransactionSnapshot carrier = propagator.capture();

            Thread worker = new Thread(() -> {
                propagator.replay(carrier);
                propagator.restore(carrier);
            });
            worker.start();
            worker.join();

            assertFalse(carrier.isRollbackOnly());
            LingTransactionContext.popConnection();
        }

        @Test
        @DisplayName("restore 后 previous 引用释放（null），防悬挂引用")
        void restoreNullsPreviousReference() throws Exception {
            Connection conn = mockConnection();
            LingTransactionContext.pushConnection(DATA_SOURCE_ID, conn);
            TransactionContextPropagator propagator = new TransactionContextPropagator();
            TransactionSnapshot carrier = propagator.capture();

            Thread worker = new Thread(() -> {
                propagator.replay(carrier);
                propagator.restore(carrier);
            });
            worker.start();
            worker.join();

            // previous 已在 restore 中置 null：再次 restore 不抛错（幂等、无悬挂引用）
            propagator.restore(carrier);
            LingTransactionContext.popConnection();
        }
    }
}
