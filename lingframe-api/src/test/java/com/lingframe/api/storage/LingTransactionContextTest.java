package com.lingframe.api.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调用链事务穿透上下文测试：分栈 / 身份查栈 / rollbackOnly 信号 / 双向快照合并语义 / 清理。
 * <p>
 * 覆盖 {@link LingTransactionContext} 的完整契约，以及双向快照的核心语义——
 * restore 采用合并（carrier.rollbackOnly |= worker 置位）而非覆盖。
 */
@DisplayName("LingTransactionContext 穿透上下文")
class LingTransactionContextTest {

    @AfterEach
    void tearDown() {
        // 每个用例后无条件清空，防止 ThreadLocal 跨用例残留污染
        // （cleanIfEmpty 只在栈空时清，用例结束时栈可能非空）
        LingTransactionContext.clear();
    }

    /**
     * 生成一个轻量 Connection 代理（api 模块无 Mockito，用动态代理即可满足引用语义）。
     */
    private static Connection mockConnection(String tag) {
        return (Connection) Proxy.newProxyInstance(
                LingTransactionContextTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isClosed":
                            return false;
                        case "toString":
                            return "conn[" + tag + "]@" + Integer.toHexString(System.identityHashCode(proxy));
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            return null;
                    }
                });
    }

    @Nested
    @DisplayName("分栈与身份查栈（身份门控的存储侧）")
    class Stacking {

        @Test
        @DisplayName("按 dataSourceId 分栈隔离，互不串用")
        void stacksAreIsolatedByDataSourceId() {
            Connection defaultConn = mockConnection("default");
            Connection orderConn = mockConnection("order");

            LingTransactionContext.pushConnection("default", defaultConn);
            LingTransactionContext.pushConnection("order", orderConn);

            assertSame(defaultConn, LingTransactionContext.getCurrentConnection("default"));
            assertSame(orderConn, LingTransactionContext.getCurrentConnection("order"));
            // 无参取默认 "default"
            assertSame(defaultConn, LingTransactionContext.getCurrentConnection());
        }

        @Test
        @DisplayName("同一源压入多个连接，取栈顶；弹出后回退到下一层")
        void peekReturnsTopAndPopPeeksNext() {
            Connection first = mockConnection("first");
            Connection second = mockConnection("second");

            LingTransactionContext.pushConnection("default", first);
            LingTransactionContext.pushConnection("default", second);

            assertSame(second, LingTransactionContext.getCurrentConnection());
            LingTransactionContext.popConnection();
            assertSame(first, LingTransactionContext.getCurrentConnection());
        }

        @Test
        @DisplayName("无连接源返回 null")
        void missingSourceReturnsNull() {
            assertNull(LingTransactionContext.getCurrentConnection("ghost"));
            assertNull(LingTransactionContext.getCurrentConnection());
        }

        @Test
        @DisplayName("hasAnyConnection 反映任意源是否有连接")
        void hasAnyConnectionReflectsNonEmptyStacks() {
            assertFalse(LingTransactionContext.hasAnyConnection());
            LingTransactionContext.pushConnection("default", mockConnection("a"));
            assertTrue(LingTransactionContext.hasAnyConnection());
            LingTransactionContext.popConnection();
            assertFalse(LingTransactionContext.hasAnyConnection());
        }

        @Test
        @DisplayName("无参 pop 与 push 严格配对（Filter finally 逐层弹栈语义）")
        void popPairsWithPushOrder() {
            Connection a = mockConnection("a");
            Connection b = mockConnection("b");
            Connection c = mockConnection("c");
            LingTransactionContext.pushConnection("default", a);
            LingTransactionContext.pushConnection("order", b);
            LingTransactionContext.pushConnection("default", c);

            // 弹出顺序 = 压入逆序：default(c) → order(b) → default(a)
            LingTransactionContext.popConnection();
            assertSame(a, LingTransactionContext.getCurrentConnection("default"));
            LingTransactionContext.popConnection();
            assertNull(LingTransactionContext.getCurrentConnection("order"));
            LingTransactionContext.popConnection();
            assertNull(LingTransactionContext.getCurrentConnection("default"));
            assertFalse(LingTransactionContext.hasAnyConnection());
        }
    }

    @Nested
    @DisplayName("rollbackOnly 回滚信号")
    class RollbackOnlySignal {

        @Test
        @DisplayName("默认未置位；setRollbackOnly 后置位")
        void signalLifecycle() {
            assertFalse(LingTransactionContext.isRollbackOnly());
            LingTransactionContext.setRollbackOnly();
            assertTrue(LingTransactionContext.isRollbackOnly());
        }
    }

    @Nested
    @DisplayName("双向快照搬运（资源下行 + 信号上行）")
    class SnapshotPropagation {

        @Test
        @DisplayName("capture 捕获各源栈顶连接；worker apply 后可见")
        void captureAndApplyCarryResourcesDownstream() throws Exception {
            Connection defaultConn = mockConnection("default");
            LingTransactionContext.pushConnection("default", defaultConn);

            LingTransactionContext.TransactionSnapshot snapshot = LingTransactionContext.captureSnapshot();

            // 在 worker 线程重放快照（模拟 ThreadIsolationGovernanceFilter 的跨线程搬运）
            AtomicReference<Connection> workerView = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                LingTransactionContext.TransactionSnapshot previous =
                        LingTransactionContext.applySnapshot(snapshot);
                workerView.set(LingTransactionContext.getCurrentConnection("default"));
                // 主线程的栈不受 worker 影响（ThreadLocal 隔离）
                assertSame(defaultConn, LingTransactionContext.getCurrentConnection());
                LingTransactionContext.restoreSnapshot(previous, snapshot);
            });
            worker.start();
            worker.join();

            assertSame(defaultConn, workerView.get());
            // 主线程栈不受 worker 影响
            assertSame(defaultConn, LingTransactionContext.getCurrentConnection("default"));
            LingTransactionContext.popConnection();
        }

        @Test
        @DisplayName("合并语义：worker 置位信号经 restore 写入 carrier，主线程 future.get 后读取")
        void restoreMergesWorkerSignalIntoCarrier() throws Exception {
            Connection defaultConn = mockConnection("default");
            LingTransactionContext.pushConnection("default", defaultConn);
            LingTransactionContext.TransactionSnapshot carrier = LingTransactionContext.captureSnapshot();

            Thread worker = new Thread(() -> {
                LingTransactionContext.TransactionSnapshot previous =
                        LingTransactionContext.applySnapshot(carrier);
                // worker 期间下游声明回滚（如 NonCloseableLingConnectionProxy.rollback）
                LingTransactionContext.setRollbackOnly();
                // 覆盖式 restore 会把信号丢弃——合并语义必须写回 carrier
                LingTransactionContext.restoreSnapshot(previous, carrier);
            });
            worker.start();
            worker.join();

            // 信号上行：主线程 future.get() 后读取 carrier 的 rollbackOnly
            assertTrue(carrier.isRollbackOnly());
            // 主线程自身的 ThreadLocal 不应被 worker 污染（信号经 carrier 显式上行）
            assertFalse(LingTransactionContext.isRollbackOnly());
            LingTransactionContext.popConnection();
        }

        @Test
        @DisplayName("worker 未置位时 restore 后 carrier 保持无信号（不误报）")
        void restoreWithoutSignalKeepsCarrierClean() throws Exception {
            Connection defaultConn = mockConnection("default");
            LingTransactionContext.pushConnection("default", defaultConn);
            LingTransactionContext.TransactionSnapshot carrier = LingTransactionContext.captureSnapshot();

            Thread worker = new Thread(() -> {
                LingTransactionContext.TransactionSnapshot previous =
                        LingTransactionContext.applySnapshot(carrier);
                LingTransactionContext.restoreSnapshot(previous, carrier);
            });
            worker.start();
            worker.join();

            assertFalse(carrier.isRollbackOnly());
            LingTransactionContext.popConnection();
        }

        @Test
        @DisplayName("capture 快照包含信号；apply 后 worker 侧信号可见")
        void captureCarriesSignalDownstream() throws Exception {
            LingTransactionContext.setRollbackOnly();
            LingTransactionContext.TransactionSnapshot snapshot = LingTransactionContext.captureSnapshot();

            AtomicReference<Boolean> workerSignal = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                LingTransactionContext.TransactionSnapshot previous =
                        LingTransactionContext.applySnapshot(snapshot);
                workerSignal.set(LingTransactionContext.isRollbackOnly());
                LingTransactionContext.restoreSnapshot(previous, snapshot);
            });
            worker.start();
            worker.join();

            assertTrue(workerSignal.get());
        }

        @Test
        @DisplayName("快照恢复还原压入顺序：worker 侧无参 pop 与 push 严格配对（不因搬运丢失弹栈语义）")
        void restoreReconstructsPushOrder() throws Exception {
            Connection defaultConn = mockConnection("default");
            Connection orderConn = mockConnection("order");
            LingTransactionContext.pushConnection("default", defaultConn);
            LingTransactionContext.pushConnection("order", orderConn);

            LingTransactionContext.TransactionSnapshot snapshot = LingTransactionContext.captureSnapshot();

            Thread worker = new Thread(() -> {
                LingTransactionContext.TransactionSnapshot previous =
                        LingTransactionContext.applySnapshot(snapshot);
                // worker 侧无参 pop：按压入逆序弹出（order → default），与主线程语义一致
                LingTransactionContext.popConnection();
                assertNull(LingTransactionContext.getCurrentConnection("order"));
                LingTransactionContext.popConnection();
                assertNull(LingTransactionContext.getCurrentConnection("default"));
                LingTransactionContext.restoreSnapshot(previous, snapshot);
            });
            worker.start();
            worker.join();

            // 主线程栈不受影响
            assertSame(orderConn, LingTransactionContext.getCurrentConnection("order"));
            LingTransactionContext.popConnection("order");
            LingTransactionContext.popConnection("default");
        }
    }

    @Nested
    @DisplayName("清理护栏")
    class Cleanup {

        @Test
        @DisplayName("cleanIfEmpty 空栈后清空信号（防线程池污染）")
        void cleanIfEmptyRemovesSignalWhenStacksEmpty() {
            LingTransactionContext.pushConnection("default", mockConnection("a"));
            LingTransactionContext.setRollbackOnly();

            LingTransactionContext.cleanIfEmpty();
            // 栈非空时不应清理
            assertTrue(LingTransactionContext.hasAnyConnection());

            LingTransactionContext.popConnection();
            LingTransactionContext.cleanIfEmpty();
            // 栈空后清理信号
            assertFalse(LingTransactionContext.hasAnyConnection());
            assertFalse(LingTransactionContext.isRollbackOnly());
        }

        @Test
        @DisplayName("cleanIfEmpty 保留非空栈的连接（不误清）")
        void cleanIfEmptyKeepsNonEmptyStacks() {
            LingTransactionContext.pushConnection("order", mockConnection("b"));
            LingTransactionContext.setRollbackOnly();

            LingTransactionContext.cleanIfEmpty();
            assertTrue(LingTransactionContext.hasAnyConnection());
            assertNotNull(LingTransactionContext.getCurrentConnection("order"));
        }

        @Test
        @DisplayName("closeAllConnections 清空连接栈与回滚信号（poisoned 路径不留脏状态）")
        void closeAllConnectionsClearsEverything() {
            LingTransactionContext.pushConnection("default", mockConnection("a"));
            LingTransactionContext.pushConnection("order", mockConnection("b"));
            LingTransactionContext.setRollbackOnly();

            int closed = LingTransactionContext.closeAllConnections();

            assertEquals(2, closed);
            assertFalse(LingTransactionContext.hasAnyConnection());
            // 信号一并清空：线程池复用线程不得带「已声明回滚」的脏状态跑后续调用
            assertFalse(LingTransactionContext.isRollbackOnly());
            // 栈已清空时再次调用幂等
            assertEquals(0, LingTransactionContext.closeAllConnections());
        }
    }

    /**
     * 生成一个可追踪 close 调用的 Connection 代理（api 模块无 Mockito，用动态代理验证 close 语义）。
     *
     * @param tag         连接标识（仅用于 toString）
     * @param closeCount  记录 close 调用次数（可为 null）
     * @param throwOnClose true 时 close 抛 SQLException（模拟已损坏连接）
     */
    private static Connection trackedConnection(String tag, AtomicInteger closeCount, boolean throwOnClose) {
        return (Connection) Proxy.newProxyInstance(
                LingTransactionContextTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isClosed":
                            return false;
                        case "close":
                            if (closeCount != null) {
                                closeCount.incrementAndGet();
                            }
                            if (throwOnClose) {
                                throw new SQLException("already broken");
                            }
                            return null;
                        case "toString":
                            return "conn[" + tag + "]@" + Integer.toHexString(System.identityHashCode(proxy));
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            return null;
                    }
                });
    }

    @Nested
    @DisplayName("poisoned 废弃（closeAllConnections）")
    class PoisonedClose {

        @Test
        @DisplayName("多源栈全部 close 并返回废弃计数，上下文清空")
        void closesAllSourcesAndReturnsCount() {
            AtomicInteger defaultCloses = new AtomicInteger();
            AtomicInteger orderCloses = new AtomicInteger();
            Connection defaultConn = trackedConnection("default", defaultCloses, false);
            Connection orderConn = trackedConnection("order", orderCloses, false);
            LingTransactionContext.pushConnection("default", defaultConn);
            LingTransactionContext.pushConnection("order", orderConn);

            int closed = LingTransactionContext.closeAllConnections();

            assertEquals(2, closed);
            assertEquals(1, defaultCloses.get());
            assertEquals(1, orderCloses.get());
            assertFalse(LingTransactionContext.hasAnyConnection());
            assertNull(LingTransactionContext.getCurrentConnection("default"));
            assertNull(LingTransactionContext.getCurrentConnection("order"));
        }

        @Test
        @DisplayName("单源多连接全部废弃")
        void closesSingleSourceStack() {
            AtomicInteger closes = new AtomicInteger();
            Connection first = trackedConnection("first", closes, false);
            Connection second = trackedConnection("second", closes, false);

            LingTransactionContext.pushConnection("default", first);
            LingTransactionContext.pushConnection("default", second);

            int closed = LingTransactionContext.closeAllConnections();

            assertEquals(2, closed);
            assertEquals(2, closes.get());
            assertFalse(LingTransactionContext.hasAnyConnection());
        }

        @Test
        @DisplayName("空栈返回 0，不抛异常")
        void emptyStackReturnsZero() {
            assertEquals(0, LingTransactionContext.closeAllConnections());
            assertFalse(LingTransactionContext.hasAnyConnection());
        }

        @Test
        @DisplayName("close 抛异常不阻断整体流程（单个失败由连接池重建兜底）")
        void closeExceptionDoesNotBlockOthers() {
            AtomicInteger healthyCloses = new AtomicInteger();
            Connection throwing = trackedConnection("throwing", null, true);
            Connection healthy = trackedConnection("healthy", healthyCloses, false);

            LingTransactionContext.pushConnection("default", throwing);
            LingTransactionContext.pushConnection("order", healthy);

            // 异常被吞掉：返回成功 close 的数量（1），healthy 仍被关闭
            int closed = LingTransactionContext.closeAllConnections();

            assertEquals(1, closed);
            assertEquals(1, healthyCloses.get());
            assertFalse(LingTransactionContext.hasAnyConnection());
        }

        @Test
        @DisplayName("废弃后清空压入顺序（后续无参 pop 安全无副作用）")
        void pushOrderClearedAfterPoison() {
            LingTransactionContext.pushConnection("default", mockConnection("a"));
            LingTransactionContext.closeAllConnections();

            // 压入顺序已清空：无参 pop 不再弹任何源（幂等安全）
            LingTransactionContext.popConnection();
            assertFalse(LingTransactionContext.hasAnyConnection());
        }
    }

    @Nested
    @DisplayName("按源精确废弃（closeConnectionsByDataSource）")
    class ScopedPoisonClose {

        @Test
        @DisplayName("只关闭入参指定的源，其余源连接保留（不误伤父事务/无关源）")
        void closesOnlySpecifiedSources() {
            AtomicInteger poisonedCloses = new AtomicInteger();
            AtomicInteger keptCloses = new AtomicInteger();
            Connection toPoison = trackedConnection("to-poison", poisonedCloses, false);
            Connection kept = trackedConnection("kept", keptCloses, false);
            LingTransactionContext.pushConnection("order", kept);
            LingTransactionContext.pushConnection("default", toPoison);

            List<String> scope = Collections.singletonList("default");
            int closed = LingTransactionContext.closeConnectionsByDataSource(scope);

            assertEquals(1, closed, "只废弃本次调用涉及的源");
            assertEquals(1, poisonedCloses.get());
            assertEquals(0, keptCloses.get(), "其他源不得被关闭");
            assertNull(LingTransactionContext.getCurrentConnection("default"));
            assertSame(kept, LingTransactionContext.getCurrentConnection("order"), "父事务/无关源连接保留");
            LingTransactionContext.popConnection("order");
        }

        @Test
        @DisplayName("null / 空集合返回 0，不抛异常")
        void nullOrEmptyScopeReturnsZero() {
            LingTransactionContext.pushConnection("default", mockConnection("a"));
            assertEquals(0, LingTransactionContext.closeConnectionsByDataSource(null));
            assertEquals(0, LingTransactionContext.closeConnectionsByDataSource(Collections.emptyList()));
            assertTrue(LingTransactionContext.hasAnyConnection());
            LingTransactionContext.popConnection();
        }

        @Test
        @DisplayName("指定的源全部废弃后清空上下文（cleanIfEmpty 语义）")
        void allPoisonedEmptiesContextWhenOnlySource() {
            AtomicInteger closes = new AtomicInteger();
            LingTransactionContext.pushConnection("default", trackedConnection("a", closes, false));
            LingTransactionContext.setRollbackOnly();

            int closed = LingTransactionContext.closeConnectionsByDataSource(Collections.singletonList("default"));

            assertEquals(1, closed);
            assertFalse(LingTransactionContext.hasAnyConnection());
            assertFalse(LingTransactionContext.isRollbackOnly(), "全部源废弃后连信号一并清空");
        }

        @Test
        @DisplayName("快照暴露本次调用继承的数据源 ID（供按源精确废弃）")
        void snapshotExposesCarriedSourceIds() {
            Connection defaultConn = mockConnection("default");
            Connection orderConn = mockConnection("order");
            LingTransactionContext.pushConnection("default", defaultConn);
            LingTransactionContext.pushConnection("order", orderConn);

            LingTransactionContext.TransactionSnapshot snapshot = LingTransactionContext.captureSnapshot();

            assertTrue(snapshot.getDataSourceIds().containsAll(Arrays.asList("default", "order")));
            LingTransactionContext.popConnection("order");
            LingTransactionContext.popConnection("default");
        }
    }
}
