package com.lingframe.starter.configuration;

import com.lingframe.core.pipeline.FilterRegistry;
import com.lingframe.core.spi.LingInvocationFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 自动发现并注入 Spring 托管的 {@link LingInvocationFilter} 到 Pipeline 引擎。
 * <p>
 * 当宿主应用或第三方 Starter 中声明了 {@code @Bean LingInvocationFilter} 时，
 * 本配置类会自动将其收集并通过 {@link FilterRegistry#addDynamicFilter} 注入到全局调用管道中。
 * 这样 Pipeline 就能同时运行内置 Filter（由 {@code FilterRegistry.initialize} 注册）
 * 和 Spring 生态中动态声明的扩展 Filter。
 */
@Slf4j
@Configuration
@ConditionalOnBean(FilterRegistry.class)
public class LingFrameFilterConfiguration {

    @Bean
    public Object lingFilterInjector(
            FilterRegistry filterRegistry,
            ObjectProvider<List<LingInvocationFilter>> filtersProvider) {

        List<LingInvocationFilter> springFilters = filtersProvider.getIfAvailable();
        if (springFilters != null && !springFilters.isEmpty()) {
            for (LingInvocationFilter filter : springFilters) {
                filterRegistry.addDynamicFilter(filter);
                log.info("🔌 [LingFrame Pipeline] Injected Spring-managed filter: {} (order={})",
                        filter.getClass().getSimpleName(), filter.getOrder());
            }
            log.info("🔌 [LingFrame Pipeline] Total {} Spring-managed filter(s) injected", springFilters.size());
        }

        // 返回占位对象确保 Bean 存在
        return new Object();
    }
}
