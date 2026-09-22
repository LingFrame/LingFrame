package com.lingframe.starter.configuration;

import com.lingframe.core.pipeline.FilterRegistry;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LingFrameFilterConfiguration} 补充测试。
 * <p>
 * 该配置类负责将 Spring 托管的 {@link LingInvocationFilter} 注入到
 * {@link FilterRegistry} 中。覆盖空列表、非空列表、null 列表三条主路径。
 */
@DisplayName("LingFrameFilterConfiguration 补充测试")
class LingFrameFilterConfigurationSupplementTest {

    @Test
    @DisplayName("lingFilterInjector 在存在多个 Filter 时应全部注入并返回占位 Bean")
    void shouldInjectAllSpringFiltersWhenPresent() {
        LingFrameFilterConfiguration configuration = new LingFrameFilterConfiguration();
        FilterRegistry filterRegistry = mock(FilterRegistry.class);
        LingInvocationFilter filterA = new TestFilter("filter-a", 10);
        LingInvocationFilter filterB = new TestFilter("filter-b", 20);
        List<LingInvocationFilter> filters = new ArrayList<>(Arrays.asList(filterA, filterB));

        @SuppressWarnings("unchecked")
        ObjectProvider<List<LingInvocationFilter>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(filters);

        Object bean = configuration.lingFilterInjector(filterRegistry, provider);

        assertNotNull(bean, "应返回占位 Bean");
        verify(filterRegistry).addDynamicFilter(filterA);
        verify(filterRegistry).addDynamicFilter(filterB);
    }

    @Test
    @DisplayName("lingFilterInjector 在 Filter 列表为空时不应注入任何 Filter")
    void shouldNotInjectWhenFilterListEmpty() {
        LingFrameFilterConfiguration configuration = new LingFrameFilterConfiguration();
        FilterRegistry filterRegistry = mock(FilterRegistry.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<List<LingInvocationFilter>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(Collections.emptyList());

        Object bean = configuration.lingFilterInjector(filterRegistry, provider);

        assertNotNull(bean);
        verify(filterRegistry, never()).addDynamicFilter(any());
    }

    @Test
    @DisplayName("lingFilterInjector 在 getIfAvailable 返回 null 时不应注入")
    void shouldNotInjectWhenProviderReturnsNull() {
        LingFrameFilterConfiguration configuration = new LingFrameFilterConfiguration();
        FilterRegistry filterRegistry = mock(FilterRegistry.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<List<LingInvocationFilter>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        Object bean = configuration.lingFilterInjector(filterRegistry, provider);

        assertNotNull(bean);
        verify(filterRegistry, never()).addDynamicFilter(any());
    }

    /**
     * 测试用 Filter，仅记录名称与顺序。
     */
    private static class TestFilter implements LingInvocationFilter {
        private final String name;
        private final int order;

        TestFilter(String name, int order) {
            this.name = name;
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

        @Override
        public String toString() {
            return "TestFilter{" + name + ", order=" + order + "}";
        }
    }
}
