package com.lingframe.core.pipeline;

import com.lingframe.api.security.PermissionService;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.ling.DefaultLingRepository;
import com.lingframe.core.ling.InvokableMethodCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

@DisplayName("Pipeline 架构契约测试")
class PipelineArchitectureContractTest {

    @Test
    @DisplayName("内置过滤器应按稳定阶段顺序装配")
    void shouldAssembleBuiltinFiltersInStablePhaseOrder() {
        EventBus eventBus = new EventBus();
        RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(eventBus);
        FilterRegistry registry = new FilterRegistry(new InvokableMethodCache(), mock(PermissionService.class));
        registry.initialize(new DefaultLingRepository(), null, eventBus, runtimeCoordinator);

        List<Class<?>> filterTypes = registry.getOrderedFilters().stream()
                .map(Object::getClass)
                .collect(Collectors.toList());

        assertEquals(Arrays.asList(
                TrafficMetricsFilter.class,
                MacroStateGuardFilter.class,
                CanaryRoutingFilter.class,
                ResilienceGovernanceFilter.class,
                ContextIsolationFilter.class,
                GovernanceDecisionFilter.class,
                PermissionGovernanceFilter.class,
                ThreadIsolationGovernanceFilter.class,
                TerminalInvokerFilter.class), filterTypes);
    }
}
