package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.ling.DefaultLingRepository;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingRuntimeConfig;
import com.lingframe.core.spi.LingFilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            LingRuntime runtime = new LingRuntime("ling1", config, eventBus, new RuntimeCoordinator(eventBus));
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
            LingRuntime runtime = new LingRuntime("ling1", config, eventBus, new RuntimeCoordinator(eventBus));
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
    }
}
