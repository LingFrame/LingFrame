package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.api.storage.LingTransactionContext;
import com.lingframe.core.ling.DefaultLingRepository;
import com.lingframe.core.ling.InstancePool;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingRuntimeConfig;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.TransactionBindingHook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 跨线程事务穿透测试：worker 线程复用穿透连接、rollbackOnly 信号经快照合并上行回主线程、
 * 线程池拒绝提交时穿透连接经 finally 归还。
 */
@DisplayName("ThreadIsolationGovernanceFilter 跨线程事务穿透")
class ThreadIsolationTxPropagationTest {

    private static final String LING_ID = "ling1";

    @AfterEach
    void tearDown() {
        LingTransactionContext.clear();
    }

    private LingRuntime mockRuntime(String lingId, LingRuntimeConfig config) {
        LingRuntime runtime = mock(LingRuntime.class);
        InstancePool pool = mock(InstancePool.class);
        when(runtime.getInstancePool()).thenReturn(pool);
        when(runtime.getLingId()).thenReturn(lingId);
        when(runtime.getConfig()).thenReturn(config);
        return runtime;
    }

    private ThreadIsolationGovernanceFilter filterWith(LingRuntimeConfig config) {
        LingRepository repository = new DefaultLingRepository();
        LingRuntime runtime = mockRuntime(LING_ID, config);
        repository.register(runtime);
        return new ThreadIsolationGovernanceFilter(repository);
    }

    private InvocationContext normalContext() {
        InvocationContext ctx = InvocationContext.obtain();
        ctx.attach();
        ctx.setServiceFQSID(LING_ID + ":TestService");
        ctx.execution().setMode(InvocationExecutionMode.NORMAL);
        return ctx;
    }

    @Nested
    @DisplayName("连接跨线程复用与双端擦除")
    class ConnectionReuse {

        @Test
        @DisplayName("worker 线程经快照重放复用主线程压栈的穿透连接")
        void workerReusesPushedConnection() throws Throwable {
            LingRuntimeConfig config = LingRuntimeConfig.builder()
                    .bulkheadMaxConcurrent(1)
                    .defaultTimeoutMs(2000)
                    .build();
            ThreadIsolationGovernanceFilter filter = filterWith(config);
            Connection conn = mock(Connection.class);
            when(conn.isClosed()).thenReturn(false);

            // 模拟 TransactionPropagationFilter 已把穿透连接压入主线程上下文
            LingTransactionContext.pushConnection("default", conn);
            InvocationContext ctx = normalContext();

            AtomicReference<Connection> workerView = new AtomicReference<>();
            try {
                Object result = filter.doFilter(ctx, current -> {
                    // worker 线程内：穿透连接经快照重放可见（跨线程复用成功）
                    workerView.set(LingTransactionContext.getCurrentConnection("default"));
                    return "ok";
                });

                assertEquals("ok", result);
                assertSame(conn, workerView.get());
                // 主线程上下文不受 worker 影响（线程隔离）：连接仍在主线程栈中
                assertSame(conn, LingTransactionContext.getCurrentConnection("default"));
            } finally {
                LingTransactionContext.popConnection();
                filter.evict(LING_ID);
                InvocationContext.detach(null);
                ctx.recycle();
            }
        }

        @Test
        @DisplayName("worker 线程池复用后无连接/信号残留（双端擦除的 worker 端验证）")
        void workerThreadReuseHasNoResidual() throws Throwable {
            LingRuntimeConfig config = LingRuntimeConfig.builder()
                    .bulkheadMaxConcurrent(1)
                    .defaultTimeoutMs(2000)
                    .build();
            ThreadIsolationGovernanceFilter filter = filterWith(config);
            Connection conn = mock(Connection.class);
            when(conn.isClosed()).thenReturn(false);

            // 第一次调用：主线程压栈穿透连接，worker 线程内可见
            LingTransactionContext.pushConnection("default", conn);
            InvocationContext firstCtx = normalContext();
            AtomicReference<String> workerThreadName = new AtomicReference<>();
            try {
                filter.doFilter(firstCtx, current -> {
                    assertSame(conn, LingTransactionContext.getCurrentConnection("default"));
                    workerThreadName.set(Thread.currentThread().getName());
                    return "first";
                });
            } finally {
                LingTransactionContext.popConnection();
                InvocationContext.detach(null);
                firstCtx.recycle();
            }

            // 第二次调用：不压栈任何连接，复用同一隔离池线程（单线程池）
            InvocationContext secondCtx = normalContext();
            try {
                filter.doFilter(secondCtx, current -> {
                    // 断言确实复用了同一 worker 线程（单线程池语义）
                    assertEquals(workerThreadName.get(), Thread.currentThread().getName());
                    // 关键断言：worker 端无连接残留、无信号残留——
                    // 第一次调用 worker finally 的 restoreSnapshot 已把 worker 上下文擦除干净
                    assertFalse(LingTransactionContext.hasAnyConnection());
                    assertFalse(LingTransactionContext.isRollbackOnly());
                    return "second";
                });
            } finally {
                InvocationContext.detach(null);
                secondCtx.recycle();
                filter.evict(LING_ID);
            }
        }
    }

    @Nested
    @DisplayName("rollbackOnly 信号上行合并")
    class SignalUplink {

        @Test
        @DisplayName("worker 置位 rollbackOnly → 经快照合并上行，主线程调用后可见")
        void workerSignalMergesBackToMainThread() throws Throwable {
            LingRuntimeConfig config = LingRuntimeConfig.builder()
                    .bulkheadMaxConcurrent(1)
                    .defaultTimeoutMs(2000)
                    .build();
            ThreadIsolationGovernanceFilter filter = filterWith(config);
            Connection conn = mock(Connection.class);
            when(conn.isClosed()).thenReturn(false);
            LingTransactionContext.pushConnection("default", conn);
            InvocationContext ctx = normalContext();

            try {
                filter.doFilter(ctx, current -> {
                    // worker 内下游声明回滚（模拟 NonCloseableLingConnectionProxy.rollback 仅置信号）
                    LingTransactionContext.setRollbackOnly();
                    return "ok";
                });

                // 信号上行合并：主线程穿透上下文感知到下游回滚意图
                assertTrue(LingTransactionContext.isRollbackOnly());
            } finally {
                LingTransactionContext.popConnection();
                filter.evict(LING_ID);
                InvocationContext.detach(null);
                ctx.recycle();
            }
        }

        @Test
        @DisplayName("worker 未置位 → 主线程不感知回滚信号（不误报）")
        void noSignalWhenWorkerClean() throws Throwable {
            LingRuntimeConfig config = LingRuntimeConfig.builder()
                    .bulkheadMaxConcurrent(1)
                    .defaultTimeoutMs(2000)
                    .build();
            ThreadIsolationGovernanceFilter filter = filterWith(config);
            Connection conn = mock(Connection.class);
            when(conn.isClosed()).thenReturn(false);
            LingTransactionContext.pushConnection("default", conn);
            InvocationContext ctx = normalContext();

            try {
                filter.doFilter(ctx, current -> "ok");
                assertFalse(LingTransactionContext.isRollbackOnly());
            } finally {
                LingTransactionContext.popConnection();
                filter.evict(LING_ID);
                InvocationContext.detach(null);
                ctx.recycle();
            }
        }
    }

    @Nested
    @DisplayName("异常路径")
    class AbnormalPath {

        @Test
        @DisplayName("worker 抛异常（ExecutionException）时信号仍上行，主线程感知下游回滚意图")
        void signalStillUplinksWhenWorkerThrows() throws Throwable {
            LingRuntimeConfig config = LingRuntimeConfig.builder()
                    .bulkheadMaxConcurrent(1)
                    .defaultTimeoutMs(2000)
                    .build();
            ThreadIsolationGovernanceFilter filter = filterWith(config);
            Connection conn = mock(Connection.class);
            when(conn.isClosed()).thenReturn(false);
            LingTransactionContext.pushConnection("default", conn);
            InvocationContext ctx = normalContext();

            try {
                assertThrows(LingInvocationException.class, () -> filter.doFilter(ctx, current -> {
                    // worker 内下游声明回滚后业务抛异常（吞异常前置场景的异常变体）
                    LingTransactionContext.setRollbackOnly();
                    throw new IllegalStateException("boom");
                }));
                // 异常路径：worker finally restore 合并信号 → 主线程 finally OR 回信号，不丢回滚意图
                assertTrue(LingTransactionContext.isRollbackOnly());
            } finally {
                LingTransactionContext.popConnection();
                filter.evict(LING_ID);
                InvocationContext.detach(null);
                ctx.recycle();
            }
        }

        @Test
        @DisplayName("超时路径：worker 阻塞超时被取消，主线程抛 TIMEOUT 且连接经外层 finally 归还")
        void timeoutPathReturnsConnectionAndThrowsTimeout() throws Throwable {
            LingRuntimeConfig config = LingRuntimeConfig.builder()
                    .bulkheadMaxConcurrent(1)
                    .defaultTimeoutMs(100)
                    .build();
            LingRepository repository = new DefaultLingRepository();
            LingRuntime runtime = mockRuntime(LING_ID, config);
            repository.register(runtime);
            ThreadIsolationGovernanceFilter isolationFilter = new ThreadIsolationGovernanceFilter(repository);

            // 组合链：外层 TransactionPropagationFilter 包内层 ThreadIsolationGovernanceFilter
            Connection conn = mock(Connection.class);
            when(conn.isClosed()).thenReturn(false);
            TransactionBindingHook hook = mock(TransactionBindingHook.class);
            when(hook.isTransactionActive()).thenReturn(true);
            when(hook.getActiveBoundDataSourceIds()).thenReturn(Collections.singleton("default"));
            when(hook.getBoundConnection("default")).thenReturn(conn);
            TransactionPropagationFilter propagationFilter = new TransactionPropagationFilter(hook);
            LingFilterChain isolationChain = current -> isolationFilter.doFilter(current, inner -> {
                // worker 阻塞超过 timeoutMs（100ms），等待中断
                Thread.sleep(2000);
                return "ok";
            });

            InvocationContext ctx = normalContext();
            try {
                LingInvocationException ex = assertThrows(LingInvocationException.class,
                        () -> propagationFilter.doFilter(ctx, isolationChain));
                assertEquals(LingInvocationException.ErrorKind.TIMEOUT, ex.getKind());
            } finally {
                // 超时路径：外层 finally pop 连接归还，无残留
                assertFalse(LingTransactionContext.hasAnyConnection());
                isolationFilter.evict(LING_ID);
                InvocationContext.detach(null);
                ctx.recycle();
            }
        }

        @Test
        @DisplayName("组合链：worker 抛异常（ExecutionException）时连接经外层 finally 归还（双端擦除无遗漏）")
        void connectionReturnedOnWorkerExceptionThroughChain() throws Throwable {
            LingRuntimeConfig config = LingRuntimeConfig.builder()
                    .bulkheadMaxConcurrent(1)
                    .defaultTimeoutMs(2000)
                    .build();
            LingRepository repository = new DefaultLingRepository();
            LingRuntime runtime = mockRuntime(LING_ID, config);
            repository.register(runtime);
            ThreadIsolationGovernanceFilter isolationFilter = new ThreadIsolationGovernanceFilter(repository);

            // 组合链：外层 TransactionPropagationFilter（负责 push/归还）包内层线程隔离过滤器
            Connection conn = mock(Connection.class);
            when(conn.isClosed()).thenReturn(false);
            TransactionBindingHook hook = mock(TransactionBindingHook.class);
            when(hook.isTransactionActive()).thenReturn(true);
            when(hook.getActiveBoundDataSourceIds()).thenReturn(Collections.singleton("default"));
            when(hook.getBoundConnection("default")).thenReturn(conn);
            TransactionPropagationFilter propagationFilter = new TransactionPropagationFilter(hook);
            LingFilterChain isolationChain = current -> isolationFilter.doFilter(current, inner -> {
                // worker 抛业务异常（非回滚信号路径）
                throw new IllegalStateException("boom");
            });

            InvocationContext ctx = normalContext();
            try {
                assertThrows(LingInvocationException.class,
                        () -> propagationFilter.doFilter(ctx, isolationChain));
            } finally {
                // finally 双端擦除无遗漏：异常路径下外层 finally 仍 pop 归还穿透连接
                assertFalse(LingTransactionContext.hasAnyConnection());
                isolationFilter.evict(LING_ID);
                InvocationContext.detach(null);
                ctx.recycle();
            }
        }
    }

    @Nested
    @DisplayName("线程池拒绝提交")
    class Rejection {

        @Test
        @DisplayName("BULKHEAD_FULL 穿过外层 TransactionPropagationFilter 时连接经 finally 归还")
        void pushedConnectionReturnedOnRejection() throws Throwable {
            // 单线程隔离池：先占满，再触发拒绝
            LingRuntimeConfig config = LingRuntimeConfig.builder()
                    .bulkheadMaxConcurrent(1)
                    .defaultTimeoutMs(2000)
                    .build();
            LingRepository repository = new DefaultLingRepository();
            LingRuntime runtime = mockRuntime(LING_ID, config);
            repository.register(runtime);
            ThreadIsolationGovernanceFilter isolationFilter = new ThreadIsolationGovernanceFilter(repository);

            // 组合链：TransactionPropagationFilter（外层，负责 push/归还）包 ThreadIsolationGovernanceFilter
            Connection conn = mock(Connection.class);
            when(conn.isClosed()).thenReturn(false);
            TransactionBindingHook hook = mock(TransactionBindingHook.class);
            when(hook.isTransactionActive()).thenReturn(true);
            when(hook.getActiveBoundDataSourceIds()).thenReturn(Collections.singleton("default"));
            when(hook.getBoundConnection("default")).thenReturn(conn);
            TransactionPropagationFilter propagationFilter = new TransactionPropagationFilter(hook);
            LingFilterChain isolationChain = current -> isolationFilter.doFilter(current, inner -> "ok");

            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService caller = Executors.newSingleThreadExecutor();

            try {
                // 第一个调用占住隔离池线程
                InvocationContext first = normalContext();
                caller.submit(() -> {
                    try {
                        isolationFilter.doFilter(first, current -> {
                            entered.countDown();
                            release.await(2, TimeUnit.SECONDS);
                            return "ok";
                        });
                    } catch (Throwable ignored) {
                    }
                });
                assertTrue(entered.await(1, TimeUnit.SECONDS));

                // 第二个调用：外层 propagation 压栈穿透连接，内层隔离池满触发 BULKHEAD_FULL
                InvocationContext second = normalContext();
                try {
                    assertThrows(LingInvocationException.class,
                            () -> propagationFilter.doFilter(second, isolationChain));
                } finally {
                    // 拒绝路径：异常穿过外层 TransactionPropagationFilter 的 finally，
                    // 主线程已 push 的连接被 popConnection + cleanIfEmpty 归还——无泄漏、无残留
                    assertFalse(LingTransactionContext.hasAnyConnection());
                    second.recycle();
                    InvocationContext.detach(null);
                }
            } finally {
                release.countDown();
                caller.shutdownNow();
                isolationFilter.evict(LING_ID);
                LingTransactionContext.clear();
            }
        }
    }

    @Nested
    @DisplayName("嵌套多跳：信号逐层合并上行")
    class NestedMultiHop {

        @Test
        @DisplayName("两跳链路：最内层置位 rollbackOnly，信号经两层快照合并上行回主线程")
        void signalMergesThroughTwoHops() throws Throwable {
            LingRuntimeConfig config = LingRuntimeConfig.builder()
                    .bulkheadMaxConcurrent(2)
                    .defaultTimeoutMs(2000)
                    .build();
            // 外层灵元 ling1 的隔离过滤器
            LingRepository outerRepository = new DefaultLingRepository();
            LingRuntime outerRuntime = mockRuntime(LING_ID, config);
            outerRepository.register(outerRuntime);
            ThreadIsolationGovernanceFilter outerFilter = new ThreadIsolationGovernanceFilter(outerRepository);
            // 内层灵元 ling2 的隔离过滤器（模拟嵌套调用 StockLing）
            LingRepository innerRepository = new DefaultLingRepository();
            LingRuntime innerRuntime = mockRuntime("ling2", config);
            innerRepository.register(innerRuntime);
            ThreadIsolationGovernanceFilter innerFilter = new ThreadIsolationGovernanceFilter(innerRepository);

            Connection conn = mock(Connection.class);
            when(conn.isClosed()).thenReturn(false);
            LingTransactionContext.pushConnection("default", conn);

            InvocationContext ctx = InvocationContext.obtain();
            ctx.attach();
            ctx.setServiceFQSID(LING_ID + ":TestService");
            ctx.execution().setMode(InvocationExecutionMode.NORMAL);

            try {
                // 外层链内触发内层调用（嵌套两跳）
                LingFilterChain innerChain = current -> innerFilter.doFilter(current, inner -> {
                    // 最内层 worker：下游声明回滚（仅置信号，模拟吞异常场景）
                    LingTransactionContext.setRollbackOnly();
                    return "inner-ok";
                });
                Object result = outerFilter.doFilter(ctx, current -> innerChain.doFilter(current));

                assertEquals("inner-ok", result);
                // 信号经两层快照合并逐层上行，最终 OR 回主线程穿透上下文
                assertTrue(LingTransactionContext.isRollbackOnly());
            } finally {
                LingTransactionContext.popConnection();
                outerFilter.evict(LING_ID);
                innerFilter.evict("ling2");
                InvocationContext.detach(null);
                ctx.recycle();
            }
        }

        @Test
        @DisplayName("两跳链路最内层正常返回 → 主线程不误报回滚信号")
        void noSignalThroughTwoHopsWhenClean() throws Throwable {
            LingRuntimeConfig config = LingRuntimeConfig.builder()
                    .bulkheadMaxConcurrent(2)
                    .defaultTimeoutMs(2000)
                    .build();
            LingRepository outerRepository = new DefaultLingRepository();
            LingRuntime outerRuntime = mockRuntime(LING_ID, config);
            outerRepository.register(outerRuntime);
            ThreadIsolationGovernanceFilter outerFilter = new ThreadIsolationGovernanceFilter(outerRepository);
            LingRepository innerRepository = new DefaultLingRepository();
            LingRuntime innerRuntime = mockRuntime("ling2", config);
            innerRepository.register(innerRuntime);
            ThreadIsolationGovernanceFilter innerFilter = new ThreadIsolationGovernanceFilter(innerRepository);

            Connection conn = mock(Connection.class);
            when(conn.isClosed()).thenReturn(false);
            LingTransactionContext.pushConnection("default", conn);

            InvocationContext ctx = InvocationContext.obtain();
            ctx.attach();
            ctx.setServiceFQSID(LING_ID + ":TestService");
            ctx.execution().setMode(InvocationExecutionMode.NORMAL);

            try {
                LingFilterChain innerChain = current -> innerFilter.doFilter(current, inner -> "inner-ok");
                Object result = outerFilter.doFilter(ctx, current -> innerChain.doFilter(current));

                assertEquals("inner-ok", result);
                assertFalse(LingTransactionContext.isRollbackOnly());
            } finally {
                LingTransactionContext.popConnection();
                outerFilter.evict(LING_ID);
                innerFilter.evict("ling2");
                InvocationContext.detach(null);
                ctx.recycle();
            }
        }
    }

    @Nested
    @DisplayName("多数据源跨线程复用")
    class MultiDataSourceReuse {

        @Test
        @DisplayName("worker 线程复用多个 dataSourceId 的穿透连接（模式 3 多存储灵元场景）")
        void workerReusesMultipleSources() throws Throwable {
            LingRuntimeConfig config = LingRuntimeConfig.builder()
                    .bulkheadMaxConcurrent(1)
                    .defaultTimeoutMs(2000)
                    .build();
            ThreadIsolationGovernanceFilter filter = filterWith(config);
            Connection orderConn = mock(Connection.class);
            Connection userConn = mock(Connection.class);
            when(orderConn.isClosed()).thenReturn(false);
            when(userConn.isClosed()).thenReturn(false);
            LingTransactionContext.pushConnection("order-ds", orderConn);
            LingTransactionContext.pushConnection("user-ds", userConn);

            InvocationContext ctx = normalContext();
            AtomicReference<Connection> orderView = new AtomicReference<>();
            AtomicReference<Connection> userView = new AtomicReference<>();
            try {
                Object result = filter.doFilter(ctx, current -> {
                    // worker 内：两个源各自的穿透连接均跨线程复用成功
                    orderView.set(LingTransactionContext.getCurrentConnection("order-ds"));
                    userView.set(LingTransactionContext.getCurrentConnection("user-ds"));
                    return "ok";
                });

                assertEquals("ok", result);
                assertSame(orderConn, orderView.get());
                assertSame(userConn, userView.get());
            } finally {
                LingTransactionContext.popConnection("order-ds");
                LingTransactionContext.popConnection("user-ds");
                filter.evict(LING_ID);
                InvocationContext.detach(null);
                ctx.recycle();
            }
        }
    }

    @Nested
    @DisplayName("poisoned 废弃（worker 忽略中断）")
    class PoisonedAbandonment {

        @Test
        @DisplayName("worker 忽略中断不退出 → 宽限期超时 → poisoned close + 指标计数")
        void workerIgnoringInterruptTriggersPoisonedClose() throws Throwable {
            // 主超时放宽到 1s：确保 worker 已进入临界区（若太短，cancel 会取消队列中未启动
            // 的任务并抛 CancellationException，awaitWorkerExit 误判为已退出，poisoned 不触发）；
            // 宽限期保持 200ms，让宽限期超时（worker 忽略中断）快速触发 poisoned 路径
            LingRuntimeConfig config = LingRuntimeConfig.builder()
                    .bulkheadMaxConcurrent(1)
                    .defaultTimeoutMs(1000)
                    .abandonedJoinTimeoutMs(200)
                    .build();
            GovernanceMetricsCollector collector = new GovernanceMetricsCollector();
            LingRepository repository = new DefaultLingRepository();
            LingRuntime runtime = mockRuntime(LING_ID, config);
            repository.register(runtime);
            ThreadIsolationGovernanceFilter isolationFilter = new ThreadIsolationGovernanceFilter(repository, collector);

            // 组合链：外层 TransactionPropagationFilter（负责 push 连接）包内层线程隔离过滤器
            Connection conn = mock(Connection.class);
            when(conn.isClosed()).thenReturn(false);
            TransactionBindingHook hook = mock(TransactionBindingHook.class);
            when(hook.isTransactionActive()).thenReturn(true);
            when(hook.getActiveBoundDataSourceIds()).thenReturn(Collections.singleton("default"));
            when(hook.getBoundConnection("default")).thenReturn(conn);
            TransactionPropagationFilter propagationFilter = new TransactionPropagationFilter(hook);

            // worker 忽略中断：纯忙等（不响应中断、不清中断标志），cancel(true) 无法让其退出。
            // 用 CountDownLatch.getCount() 忙等而非 await()——await 抛 InterruptedException 后
            // 中断标志被清除，catch 循环语义不可靠，忙等是「不可中断 I/O」的最忠实模拟
            CountDownLatch releaseWorker = new CountDownLatch(1);
            LingFilterChain isolationChain = current -> isolationFilter.doFilter(current, inner -> {
                while (releaseWorker.getCount() > 0) {
                    Thread.yield();
                }
                return "ok";
            });

            InvocationContext ctx = normalContext();
            try {
                LingInvocationException ex = assertThrows(LingInvocationException.class,
                        () -> propagationFilter.doFilter(ctx, isolationChain));
                assertEquals(LingInvocationException.ErrorKind.TIMEOUT, ex.getKind());
                // poisoned close：穿透连接被 close 废弃（跳过 rollback 直接 close）
                verify(conn).close();
                // lingframe.tx.connection.poisoned 指标计数 = 1
                assertEquals(1, collector.getSummary(LING_ID).getConnectionPoisonedCount());
                // 废弃后主线程穿透上下文清空（无残留）
                assertFalse(LingTransactionContext.hasAnyConnection());
            } finally {
                releaseWorker.countDown();
                isolationFilter.evict(LING_ID);
                InvocationContext.detach(null);
                ctx.recycle();
            }
        }
    }
}
