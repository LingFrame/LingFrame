package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.ling.DefaultLingRepository;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingRuntimeConfig;
import com.lingframe.core.spi.LingFilterChain;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadIsolationGovernanceFilterTest {

    @Test
    void rejectsWhenIsolationPoolFull() throws Exception {
        LingRepository repository = new DefaultLingRepository();
        LingRuntimeConfig config = LingRuntimeConfig.builder()
                .bulkheadMaxConcurrent(1)
                .defaultTimeoutMs(2000)
                .build();
        LingRuntime runtime = new LingRuntime("ling1", config, new EventBus());
        repository.register(runtime);

        ThreadIsolationGovernanceFilter filter = new ThreadIsolationGovernanceFilter(repository);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        LingFilterChain blockingChain = ctx -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return "ok";
        };

        InvocationContext ctx1 = InvocationContext.obtain();
        ctx1.setServiceFQSID("ling1:TestService");
        InvocationContext ctx2 = InvocationContext.obtain();
        ctx2.setServiceFQSID("ling1:TestService");

        ExecutorService caller = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            caller.submit(() -> {
                try {
                    filter.doFilter(ctx1, blockingChain);
                } catch (Throwable ignored) {
                    // ignore
                }
            });

            assertTrue(entered.await(1, TimeUnit.SECONDS));

            LingInvocationException ex = assertThrows(LingInvocationException.class,
                    () -> filter.doFilter(ctx2, blockingChain));
            assertEquals(LingInvocationException.ErrorKind.RATE_LIMITED, ex.getKind());
        } finally {
            release.countDown();
            caller.shutdownNow();
            filter.evict("ling1");
            ctx1.recycle();
            ctx2.recycle();
        }
    }
}

