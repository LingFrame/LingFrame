package com.lingframe.core.pipeline;

import com.lingframe.api.security.PermissionService;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.ling.DefaultLingRepository;
import com.lingframe.core.ling.InvokableMethodCache;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingRuntimeConfig;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("FilterRegistry 测试")
class FilterRegistryTest {

    @Nested
    @DisplayName("灵元资源清退")
    class LingResourceEvictionTests {

        @Test
        @DisplayName("卸载时应同时清退调用治理相关资源")
        void shouldEvictInvocationGovernanceResourcesOnLingUnload() throws Throwable {
            DefaultLingRepository repository = new DefaultLingRepository();
            EventBus eventBus = new EventBus();
            RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(eventBus);
            LingRuntimeConfig config = LingRuntimeConfig.builder()
                    .bulkheadMaxConcurrent(1)
                    .defaultTimeoutMs(1000)
                    .rateLimitPerSecond(5)
                    .build();
            LingRuntime runtime = new LingRuntime("ling1", config, eventBus, runtimeCoordinator);
            repository.register(runtime);

            FilterRegistry registry = new FilterRegistry(FilterRegistryConfig.builder()
                    .methodCache(new InvokableMethodCache())
                    .permissionService(mock(PermissionService.class))
                    .lingRepository(repository)
                    .trafficRouter(new LatestVersionPolicy())
                    .eventBus(eventBus)
                    .runtimeCoordinator(runtimeCoordinator)
                    .build());

            ResilienceGovernanceFilter resilienceFilter = registry.getResilienceFilter();
            ThreadIsolationGovernanceFilter isolationFilter = registry.getIsolationFilter();
            assertNotNull(resilienceFilter);
            assertNotNull(isolationFilter);

            InvocationContext resilienceContext = InvocationContext.obtain();
            resilienceContext.setServiceFQSID("ling1:demo.Service");
            InvocationContext isolationContext = InvocationContext.obtain();
            isolationContext.setServiceFQSID("ling1:demo.Service");

            LingFilterChain passThrough = current -> "ok";

            try {
                assertEquals("ok", resilienceFilter.doFilter(resilienceContext, passThrough));
                assertEquals("ok", isolationFilter.doFilter(isolationContext, passThrough));

                assertTrue(resilienceFilter.hasLimiter("ling1"));
                assertTrue(resilienceFilter.hasBreaker("ling1"));
                assertTrue(isolationFilter.hasExecutor("ling1"));

                registry.evictLingResources("ling1");

                assertFalse(resilienceFilter.hasLimiter("ling1"));
                assertFalse(resilienceFilter.hasBreaker("ling1"));
                assertFalse(isolationFilter.hasExecutor("ling1"));
            } finally {
                registry.evictLingResources("ling1");
                resilienceContext.recycle();
                isolationContext.recycle();
            }
        }
    }

    @Nested
    @DisplayName("DCL 缓存并发正确性测试")
    class DclCacheConcurrencyTests {

        /**
         * 构造一个可用的 FilterRegistry，复用 LingResourceEvictionTests 的依赖装配方式。
         */
        private FilterRegistry createRegistry() {
            DefaultLingRepository repository = new DefaultLingRepository();
            EventBus eventBus = new EventBus();
            RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(eventBus);
            LingRuntimeConfig config = LingRuntimeConfig.builder()
                    .bulkheadMaxConcurrent(1)
                    .defaultTimeoutMs(1000)
                    .rateLimitPerSecond(5)
                    .build();
            LingRuntime runtime = new LingRuntime("ling1", config, eventBus, runtimeCoordinator);
            repository.register(runtime);
            return new FilterRegistry(FilterRegistryConfig.builder()
                    .methodCache(new InvokableMethodCache())
                    .permissionService(mock(PermissionService.class))
                    .lingRepository(repository)
                    .trafficRouter(new LatestVersionPolicy())
                    .eventBus(eventBus)
                    .runtimeCoordinator(runtimeCoordinator)
                    .build());
        }

        @Test
        @DisplayName("getOrderedFilters 应返回不可变列表")
        void shouldReturnImmutableList() {
            FilterRegistry registry = createRegistry();
            List<LingInvocationFilter> filters = registry.getOrderedFilters();
            assertNotNull(filters);
            assertThrows(UnsupportedOperationException.class, () -> filters.add(null),
                    "缓存列表应为不可变，防止外部篡改破坏 DCL 缓存一致性");
        }

        @Test
        @DisplayName("多次调用 getOrderedFilters 应返回同一缓存实例（DCL 命中）")
        void shouldReturnSameCachedInstanceOnRepeatedCalls() {
            FilterRegistry registry = createRegistry();
            List<LingInvocationFilter> first = registry.getOrderedFilters();
            List<LingInvocationFilter> second = registry.getOrderedFilters();
            assertSame(first, second, "多次调用应返回同一缓存实例，DCL volatile 读命中");
        }

        @Test
        @DisplayName("多线程并发 getOrderedFilters 应返回同一实例，不抛异常")
        void shouldReturnSameInstanceUnderConcurrentAccess() throws Exception {
            FilterRegistry registry = createRegistry();

            int threadCount = 20;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            List<List<LingInvocationFilter>> results = Collections.synchronizedList(new ArrayList<>());
            ExecutorService exec = Executors.newFixedThreadPool(threadCount);

            for (int i = 0; i < threadCount; i++) {
                exec.submit(() -> {
                    try {
                        startLatch.await();
                        results.add(registry.getOrderedFilters());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown(); // 所有线程同时竞争
            boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
            exec.shutdownNow();
            assertTrue(finished, "并发调用应在超时前完成，DCL 不应死锁");

            assertEquals(threadCount, results.size());
            List<LingInvocationFilter> reference = results.get(0);
            assertNotNull(reference);
            for (List<LingInvocationFilter> result : results) {
                assertSame(reference, result, "所有线程应看到同一缓存实例（DCL + volatile 并发安全）");
            }
        }

        @Test
        @DisplayName("addDynamicFilter 后缓存应失效并重建为新实例")
        void shouldInvalidateCacheAfterAddDynamicFilter() {
            FilterRegistry registry = createRegistry();
            List<LingInvocationFilter> first = registry.getOrderedFilters();
            int originalSize = first.size();

            LingInvocationFilter dynamicFilter = mock(LingInvocationFilter.class);
            when(dynamicFilter.getOrder()).thenReturn(Integer.MAX_VALUE);
            registry.addDynamicFilter(dynamicFilter);

            List<LingInvocationFilter> second = registry.getOrderedFilters();
            assertNotSame(first, second, "addDynamicFilter 后 invalidateCache 应使旧缓存失效，返回新实例");
            assertEquals(originalSize + 1, second.size(), "新缓存应包含新增的动态过滤器");
        }

        @Test
        @DisplayName("removeDynamicFilter 后缓存应失效并重建")
        void shouldInvalidateCacheAfterRemoveDynamicFilter() {
            FilterRegistry registry = createRegistry();
            LingInvocationFilter dynamicFilter = mock(LingInvocationFilter.class);
            when(dynamicFilter.getOrder()).thenReturn(Integer.MAX_VALUE);
            registry.addDynamicFilter(dynamicFilter);

            List<LingInvocationFilter> withDynamic = registry.getOrderedFilters();

            assertTrue(registry.removeDynamicFilter(dynamicFilter));
            List<LingInvocationFilter> afterRemove = registry.getOrderedFilters();
            assertNotSame(withDynamic, afterRemove, "removeDynamicFilter 后应返回新缓存实例");
            assertEquals(withDynamic.size() - 1, afterRemove.size(), "移除的过滤器不应在新缓存中");
        }
    }
}
