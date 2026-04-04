package com.lingframe.starter.configuration;

import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.invoker.FastLingServiceInvoker;
import com.lingframe.core.ling.DefaultLingResourceManager;
import com.lingframe.core.ling.InvokableMethodCache;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingResourceManager;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.resource.DefaultLeakDetector;
import com.lingframe.core.spi.LeakDetector;
import com.lingframe.core.spi.LingServiceInvoker;
import com.lingframe.core.spi.ResourceGuard;
import com.lingframe.starter.resource.SpringBasicResourceGuard;
import com.lingframe.starter.resource.StorageResourceGuard;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 运行时通用 Bean 装配切片。
 */
@Configuration
public class LingFrameRuntimeBeansConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MetricsCollector metricsCollector(LingRepository lingRepository) {
        return new MetricsCollector(lingRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public GovernanceMetricsCollector governanceMetricsCollector() {
        return new GovernanceMetricsCollector();
    }

    @Bean
    @ConditionalOnMissingBean
    public LingServiceInvoker lingServiceInvoker() {
        return new FastLingServiceInvoker();
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public LingResourceManager lingResourceManager(LingRepository lingRepository,
            EventBus eventBus,
            InvokableMethodCache methodCache) {
        return new DefaultLingResourceManager(lingRepository, eventBus, methodCache);
    }

    @Bean
    @ConditionalOnMissingBean
    public LeakDetector leakDetector(EventBus eventBus, LingFrameConfig lingFrameConfig) {
        return new DefaultLeakDetector(eventBus, lingFrameConfig);
    }

    @Bean
    @ConditionalOnMissingBean
    public ResourceGuard resourceGuard() {
        return new SpringBasicResourceGuard();
    }

    @Bean
    public ResourceGuard storageResourceGuard() {
        return new StorageResourceGuard();
    }
}
