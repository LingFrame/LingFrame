package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.ling.DefaultLingRepository;
import com.lingframe.core.ling.InstancePool;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingRuntimeConfig;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.model.EngineTrace;
import com.lingframe.core.spi.LingFilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@DisplayName("ThreadIsolationGovernanceFilter 测试")
class ThreadIsolationGovernanceFilterTest {

    @Nested
    @DisplayName("线程池隔离")
    class ThreadIsolationTests {

        @Test
        @DisplayName("隔离线程池满载时应拒绝新请求")
        void shouldRejectWhenIsolationPoolIsFull() throws Exception {
            LingRepository repository = new DefaultLingRepository();
            LingRuntimeConfig config = LingRuntimeConfig.builder()
                    .bulkheadMaxConcurrent(1)
                    .defaultTimeoutMs(2000)
                    .build();
            EventBus eventBus = new EventBus();
            LingRuntime runtime = mockRuntime("ling1", config);
            repository.register(runtime);

            ThreadIsolationGovernanceFilter filter = new ThreadIsolationGovernanceFilter(repository);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            LingFilterChain blockingChain = context -> {
                entered.countDown();
                release.await(2, TimeUnit.SECONDS);
                return "ok";
            };

            InvocationContext firstContext = InvocationContext.obtain();
            firstContext.setServiceFQSID("ling1:TestService");
            InvocationContext secondContext = InvocationContext.obtain();
            secondContext.setServiceFQSID("ling1:TestService");

            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                caller.submit(() -> {
                    try {
                        filter.doFilter(firstContext, blockingChain);
                    } catch (Throwable ignored) {
                    }
                });

                assertTrue(entered.await(1, TimeUnit.SECONDS));

                LingInvocationException exception = assertThrows(LingInvocationException.class,
                        () -> filter.doFilter(secondContext, blockingChain));
                assertEquals(LingInvocationException.ErrorKind.RATE_LIMITED, exception.getKind());
            } finally {
                release.countDown();
                caller.shutdownNow();
                filter.evict("ling1");
                firstContext.recycle();
                secondContext.recycle();
            }
        }

        @Test
        @DisplayName("应使用子上下文执行并合并新增追踪")
        void shouldRunWithChildContextAndMergeNewTraces() throws Throwable {
            LingRepository repository = new DefaultLingRepository();
            LingRuntimeConfig config = LingRuntimeConfig.builder()
                    .bulkheadMaxConcurrent(2)
                    .defaultTimeoutMs(1000)
                    .build();
            EventBus eventBus = new EventBus();
            LingRuntime runtime = mockRuntime("ling1", config);
            repository.register(runtime);

            ThreadIsolationGovernanceFilter filter = new ThreadIsolationGovernanceFilter(repository);
            InvocationContext context = InvocationContext.obtain();
            context.setServiceFQSID("ling1:TestService");
            context.execution().addTrace(EngineTrace.builder()
                    .source("parent")
                    .action("existing")
                    .type("INFO")
                    .depth(1)
                    .build());

            AtomicReference<InvocationContext> seen = new AtomicReference<>();
            LingFilterChain chain = current -> {
                seen.set(current);
                current.execution().addTrace(EngineTrace.builder()
                        .source("child")
                        .action("isolated")
                        .type("INFO")
                        .depth(2)
                        .build());
                return "ok";
            };

            try {
                assertEquals("ok", filter.doFilter(context, chain));
                assertNotSame(context, seen.get());
                assertEquals(2, context.execution().getTraces().size());
                assertEquals("child", context.execution().getTraces().get(1).getSource());
            } finally {
                filter.evict("ling1");
                context.recycle();
            }
        }

        @Test
        @DisplayName("治理最大并发变更后应切换到新的隔离线程池规格")
        void shouldRefreshExecutorWhenGovernedMaxThreadsChanges() throws Exception {
            LingRepository repository = new DefaultLingRepository();
            LingRuntimeConfig config = LingRuntimeConfig.builder()
                    .bulkheadMaxConcurrent(2)
                    .defaultTimeoutMs(2000)
                    .build();
            EventBus eventBus = new EventBus();
            LingRuntime runtime = mockRuntime("ling1", config);
            repository.register(runtime);

            ThreadIsolationGovernanceFilter filter = new ThreadIsolationGovernanceFilter(repository);
            ExecutorService caller = Executors.newFixedThreadPool(2);

            CountDownLatch firstEnteredA = new CountDownLatch(1);
            CountDownLatch firstEnteredB = new CountDownLatch(1);
            CountDownLatch firstRelease = new CountDownLatch(1);

            LingFilterChain firstChainA = context -> {
                firstEnteredA.countDown();
                firstRelease.await(2, TimeUnit.SECONDS);
                return "a";
            };
            LingFilterChain firstChainB = context -> {
                firstEnteredB.countDown();
                firstRelease.await(2, TimeUnit.SECONDS);
                return "b";
            };

            InvocationContext firstContext = InvocationContext.obtain();
            firstContext.setServiceFQSID("ling1:TestService");
            firstContext.governance().setMaxConcurrentThreads(2);
            InvocationContext secondContext = InvocationContext.obtain();
            secondContext.setServiceFQSID("ling1:TestService");
            secondContext.governance().setMaxConcurrentThreads(2);

            CountDownLatch secondEntered = new CountDownLatch(1);
            CountDownLatch secondRelease = new CountDownLatch(1);
            LingFilterChain secondChain = context -> {
                secondEntered.countDown();
                secondRelease.await(2, TimeUnit.SECONDS);
                return "c";
            };

            InvocationContext thirdContext = InvocationContext.obtain();
            thirdContext.setServiceFQSID("ling1:TestService");
            thirdContext.governance().setMaxConcurrentThreads(1);
            InvocationContext fourthContext = InvocationContext.obtain();
            fourthContext.setServiceFQSID("ling1:TestService");
            fourthContext.governance().setMaxConcurrentThreads(1);

            try {
                Future<?> firstFuture = caller.submit(() -> {
                    try {
                        filter.doFilter(firstContext, firstChainA);
                    } catch (Throwable ignored) {
                    }
                });
                Future<?> secondFuture = caller.submit(() -> {
                    try {
                        filter.doFilter(secondContext, firstChainB);
                    } catch (Throwable ignored) {
                    }
                });

                assertTrue(firstEnteredA.await(1, TimeUnit.SECONDS));
                assertTrue(firstEnteredB.await(1, TimeUnit.SECONDS));

                firstRelease.countDown();
                firstFuture.get(2, TimeUnit.SECONDS);
                secondFuture.get(2, TimeUnit.SECONDS);

                caller.submit(() -> {
                    try {
                        filter.doFilter(thirdContext, secondChain);
                    } catch (Throwable ignored) {
                    }
                });
                assertTrue(secondEntered.await(1, TimeUnit.SECONDS));

                LingInvocationException exception = assertThrows(LingInvocationException.class,
                        () -> filter.doFilter(fourthContext, secondChain));
                assertEquals(LingInvocationException.ErrorKind.RATE_LIMITED, exception.getKind());
            } finally {
                secondRelease.countDown();
                caller.shutdownNow();
                filter.evict("ling1");
                firstContext.recycle();
                secondContext.recycle();
                thirdContext.recycle();
                fourthContext.recycle();
            }
        }

        @Test
        @DisplayName("隔离调用结束后工作线程 TCCL 应恢复为 Core ClassLoader")
        void shouldRestoreCoreClassLoaderAfterIsolatedInvocation() throws Throwable {
            LingRepository repository = new DefaultLingRepository();
            LingRuntimeConfig config = LingRuntimeConfig.builder()
                    .bulkheadMaxConcurrent(1)
                    .defaultTimeoutMs(1000)
                    .build();
            EventBus eventBus = new EventBus();
            LingRuntime runtime = mockRuntime("ling1", config);
            repository.register(runtime);

            ThreadIsolationGovernanceFilter filter = new ThreadIsolationGovernanceFilter(repository);
            InvocationContext context = InvocationContext.obtain();
            ClassLoader targetClassLoader = new ClassLoader(getClass().getClassLoader()) {
            };
            context.setServiceFQSID("ling1:TestService");
            context.resolution().setTargetClassLoader(targetClassLoader);

            AtomicReference<Thread> workerThread = new AtomicReference<>();
            LingFilterChain chain = current -> {
                workerThread.set(Thread.currentThread());
                assertSame(targetClassLoader, Thread.currentThread().getContextClassLoader());
                return "ok";
            };

            try {
                assertEquals("ok", filter.doFilter(context, chain));
                assertNotNull(workerThread.get());
                assertSame(ThreadIsolationGovernanceFilter.class.getClassLoader(),
                        workerThread.get().getContextClassLoader());
            } finally {
                filter.evict("ling1");
                context.recycle();
            }
        }

        @Test
        @DisplayName("卸载驱逐后不应继续保留隔离线程池缓存")
        void shouldReleaseExecutorStateAfterEvict() throws Throwable {
            LingRepository repository = new DefaultLingRepository();
            LingRuntimeConfig config = LingRuntimeConfig.builder()
                    .bulkheadMaxConcurrent(1)
                    .defaultTimeoutMs(1000)
                    .build();
            EventBus eventBus = new EventBus();
            LingRuntime runtime = mockRuntime("ling1", config);
            repository.register(runtime);

            ThreadIsolationGovernanceFilter filter = new ThreadIsolationGovernanceFilter(repository);
            InvocationContext context = InvocationContext.obtain();
            context.setServiceFQSID("ling1:TestService");

            try {
                assertEquals("ok", filter.doFilter(context, current -> "ok"));
                assertTrue(filter.hasExecutor("ling1"));

                filter.evict("ling1");

                assertFalse(filter.hasExecutor("ling1"));
            } finally {
                filter.evict("ling1");
                context.recycle();
            }
        }
    }

    @Nested
    @DisplayName("超时治理")
    class TimeoutGovernanceTests {

        @Test
        @DisplayName("治理超时应优先于运行时默认超时生效")
        void shouldUseGovernedTimeoutBeforeRuntimeDefaultTimeout() {
            LingRepository repository = new DefaultLingRepository();
            LingRuntimeConfig config = LingRuntimeConfig.builder()
                    .bulkheadMaxConcurrent(2)
                    .defaultTimeoutMs(1000)
                    .build();
            EventBus eventBus = new EventBus();
            LingRuntime runtime = mockRuntime("ling1", config);
            repository.register(runtime);

            ThreadIsolationGovernanceFilter filter = new ThreadIsolationGovernanceFilter(repository);
            InvocationContext context = InvocationContext.obtain();
            context.setServiceFQSID("ling1:TestService");
            context.governance().setTimeoutMs(50);

            LingFilterChain slowChain = current -> {
                Thread.sleep(200);
                return "ok";
            };

            try {
                LingInvocationException exception = assertThrows(LingInvocationException.class,
                        () -> filter.doFilter(context, slowChain));
                assertEquals(LingInvocationException.ErrorKind.TIMEOUT, exception.getKind());
            } finally {
                filter.evict("ling1");
                context.recycle();
            }
        }

        @Test
        @DisplayName("应记录线程预算、CPU 预算与内存估算观测")
        void shouldRecordBudgetObservations() throws Throwable {
            LingRepository repository = new DefaultLingRepository();
            LingRuntimeConfig config = LingRuntimeConfig.builder()
                    .bulkheadMaxConcurrent(2)
                    .defaultTimeoutMs(1000)
                    .build();
            EventBus eventBus = new EventBus();
            LingRuntime runtime = mockRuntime("ling1", config);
            repository.register(runtime);
            GovernanceMetricsCollector collector = new GovernanceMetricsCollector();

            ThreadIsolationGovernanceFilter filter = new ThreadIsolationGovernanceFilter(repository, collector);
            InvocationContext context = InvocationContext.obtain();
            context.setServiceFQSID("ling1:TestService");
            context.setTargetVersion("1.0.0");
            context.governance().setMaxConcurrentThreads(2);
            context.governance().setCpuBudgetMsPerMinute(500);
            context.governance().setMemoryBudgetMb(8);

            try {
                assertEquals("ok", filter.doFilter(context, current -> {
                    byte[] buffer = new byte[128 * 1024];
                    return buffer.length > 0 ? "ok" : "fail";
                }));

                com.lingframe.core.metrics.GovernanceMetricsSnapshot snapshot = collector.getSummary("ling1");
                assertEquals(2, snapshot.getMaxConcurrentThreadsBudget());
                assertEquals(500, snapshot.getCpuBudgetMsPerMinute());
                assertEquals(8, snapshot.getMemoryBudgetMb());
                assertTrue(snapshot.getCpuTimeMsLastMinute() >= 0);
                assertTrue(snapshot.getEstimatedHeapDeltaBytes() >= 0);
                assertNotEquals(0L, snapshot.getTimestamp());
            } finally {
                filter.evict("ling1");
                context.recycle();
            }
        }
    }

    private static LingRuntime mockRuntime(String lingId, LingRuntimeConfig config) {
        LingRuntime runtime = mock(LingRuntime.class);
        InstancePool pool = mock(InstancePool.class);
        when(runtime.getInstancePool()).thenReturn(pool);
        when(runtime.getLingId()).thenReturn(lingId);
        when(runtime.getConfig()).thenReturn(config);
        return runtime;
    }

    @Nested
    @DisplayName("新格式 __provider__: FQSID 读路径")
    class ProviderFqsidTests {

        @Test
        @DisplayName("新格式 FQSID 应优先读 targetLingId 查 runtime，线程隔离正常生效")
        void shouldReadTargetLingIdWhenProviderFqsid() throws Throwable {
            LingRepository repository = new DefaultLingRepository();
            LingRuntimeConfig config = LingRuntimeConfig.builder()
                    .bulkheadMaxConcurrent(1)
                    .defaultTimeoutMs(2000)
                    .build();
            LingRuntime runtime = mockRuntime("ling1", config);
            repository.register(runtime);

            ThreadIsolationGovernanceFilter filter = new ThreadIsolationGovernanceFilter(repository);
            InvocationContext context = InvocationContext.obtain();
            // 模拟 ContractProviderRoutingFilter 已设置 targetLingId
            context.setServiceFQSID("__provider__:TestService");
            context.setTargetLingId("ling1");

            try {
                assertEquals("ok", filter.doFilter(context, current -> "ok"));
                // 关键断言：线程隔离池创建在 "ling1" 名下，说明用了 targetLingId 而非占位符
                assertTrue(filter.hasExecutor("ling1"));
            } finally {
                filter.evict("ling1");
                context.recycle();
            }
        }
    }
}
