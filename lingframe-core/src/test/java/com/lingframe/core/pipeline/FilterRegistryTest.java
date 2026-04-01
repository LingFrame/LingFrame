package com.lingframe.core.pipeline;

import com.lingframe.api.security.PermissionService;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.ling.DefaultLingRepository;
import com.lingframe.core.ling.InvokableMethodCache;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingRuntimeConfig;
import com.lingframe.core.spi.LingFilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

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

            FilterRegistry registry = new FilterRegistry(new InvokableMethodCache(), mock(PermissionService.class));
            registry.initialize(repository, new LatestVersionPolicy(), eventBus, runtimeCoordinator);

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
}
