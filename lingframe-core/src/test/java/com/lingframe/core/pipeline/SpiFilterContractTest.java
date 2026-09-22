package com.lingframe.core.pipeline;

import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameInfo;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.ling.InvokableMethodCache;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.routing.ProviderWeightRouter;
import com.lingframe.core.spi.LingInvocationFilter;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingServiceInvoker;
import com.lingframe.core.spi.TrafficRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DisplayName("SPI 过滤器沙箱契约测试")
class SpiFilterContractTest {

    private FilterRegistry filterRegistry;

    @BeforeEach
    void setUp() {
        FilterRegistryConfig config = FilterRegistryConfig.builder()
                .methodCache(mock(InvokableMethodCache.class))
                .permissionService(mock(PermissionService.class))
                .serviceInvoker(mock(LingServiceInvoker.class))
                .lingRepository(mock(LingRepository.class))
                .trafficRouter(mock(TrafficRouter.class))
                .eventBus(mock(EventBus.class))
                .serviceRegistry(mock(LingServiceRegistry.class))
                .metricsCollector(mock(MetricsCollector.class))
                .runtimeCoordinator(mock(RuntimeCoordinator.class))
                .governanceMetricsCollector(mock(GovernanceMetricsCollector.class))
                .lingFrameInfo(mock(LingFrameInfo.class))
                .governanceRegistry(mock(LocalGovernanceRegistry.class))
                .providerWeightRouter(mock(ProviderWeightRouter.class))
                .build();

        filterRegistry = new FilterRegistry(config);
    }

    @Test
    @DisplayName("合法 SPI 过滤器：允许分配在非保留区间的 Order")
    void testValidSpiFilterOrder() {
        LingInvocationFilter validFilter = new AbstractSpiFilter(99); // < 100
        filterRegistry.addDynamicFilter(validFilter);

        // 触发懒加载排序验证
        filterRegistry.getOrderedFilters();
        // 如果没有抛出异常，说明测试通过
        assertTrue(filterRegistry.getOrderedFilters().contains(validFilter));
    }

    @Test
    @DisplayName("非法 SPI 过滤器：如果占用内置保留位，必须 Fail-Fast")
    void testInvalidSpiFilterOrderBlocked() {
        // FilterPhase.ROUTING 的 order 就是 300，是一个内置保留位
        LingInvocationFilter maliciousFilter = new AbstractSpiFilter(FilterPhase.ROUTING);
        filterRegistry.addDynamicFilter(maliciousFilter);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            filterRegistry.getOrderedFilters();
        });

        assertTrue(ex.getMessage().contains("uses reserved pipeline order"), "必须指明使用了内置保留位");
    }

    private static class AbstractSpiFilter implements LingInvocationFilter {
        private final int order;

        public AbstractSpiFilter(int order) {
            this.order = order;
        }

        @Override
        public int getOrder() {
            return order;
        }

        @Override
        public Object doFilter(InvocationContext context, LingFilterChain chain) throws Throwable {
            return chain.doFilter(context);
        }
    }
}
