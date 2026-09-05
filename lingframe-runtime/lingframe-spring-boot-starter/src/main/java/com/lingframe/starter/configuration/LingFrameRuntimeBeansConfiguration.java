package com.lingframe.starter.configuration;

import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.invoker.FastLingServiceInvoker;
import com.lingframe.core.ling.DefaultLingResourceManager;
import com.lingframe.core.ling.InvokableMethodCache;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingResourceManager;
import com.lingframe.core.ling.LingRuntimeConfig;
import com.lingframe.core.ling.VirtualLingManager;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.resource.DefaultLeakDetector;
import com.lingframe.core.resource.DebuggerCaptureUnloadHook;
import com.lingframe.core.spi.LeakDetector;
import com.lingframe.core.spi.LingServiceInvoker;
import com.lingframe.core.spi.LingUnloadHook;
import com.lingframe.starter.config.LingFrameProperties;
import com.lingframe.starter.ling.VirtualLingRegistration;
import com.lingframe.starter.resource.SpringEcosystemUnloadHook;
import com.lingframe.starter.resource.StorageCacheUnloadHook;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.List;

/**
 * 运行时通用 Bean 腐配切片。
 */
@Configuration(proxyBeanMethods = false)
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
        // IDE 调试模式下，debugger-agent 会在卸载清理后至 GC 窗口期间持续捕获异常，
        // 新条目的 backtrace 强引用灵元 CL 加载的 Class，阻止 ClassLoader 被 GC 回收。
        // 在每轮 GC 前重新清理 CaptureStorage，确保 GC 时引用链已断开。
        List<LingUnloadHook> preGcCleaners = Collections.singletonList(new DebuggerCaptureUnloadHook());
        return new DefaultLeakDetector(eventBus, lingFrameConfig, preGcCleaners);
    }

    // =========================================================================
    // 生态卸载钩子（Spring 生态清理，注册到生态桶）
    // =========================================================================

    @Bean
    public LingUnloadHook springEcosystemUnloadHook() {
        return new SpringEcosystemUnloadHook();
    }

    @Bean
    public LingUnloadHook storageCacheUnloadHook() {
        return new StorageCacheUnloadHook();
    }

    // =========================================================================
    // 虚拟灵元（Virtual Governance Ling）管理与自动装配
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean
    public VirtualLingManager virtualLingManager(LingRepository lingRepository,
                                                 RuntimeCoordinator runtimeCoordinator,
                                                 EventBus eventBus) {
        return new VirtualLingManager(lingRepository, runtimeCoordinator, eventBus);
    }

    @Bean
    public SmartInitializingSingleton virtualLingBootstrapRunner(
            VirtualLingManager virtualLingManager,
            LingFrameProperties properties,
            ObjectProvider<VirtualLingRegistration> registrationsProvider) {
        return () -> {
            // 1. 装配 YAML 配置驱动的虚拟灵元（lingframe.virtual-lings.*）
            if (properties.getVirtualLings() != null) {
                properties.getVirtualLings().forEach((lingId, cfg) -> {
                    LingRuntimeConfig runtimeConfig = LingRuntimeConfig.builder()
                            .rateLimitPerSecond(cfg.getRateLimitPerSecond())
                            .circuitBreakerFailureRateThreshold(cfg.getCircuitBreakerFailureRateThreshold())
                            .circuitBreakerSlidingWindowSize(cfg.getCircuitBreakerSlidingWindowSize())
                            .defaultTimeoutMs(cfg.getDefaultTimeoutMs())
                            .bulkheadMaxConcurrent(cfg.getBulkheadMaxConcurrent())
                            .build();
                    virtualLingManager.register(lingId, runtimeConfig);
                });
            }

            // 2. 收集容器中声明的 @Bean VirtualLingRegistration 声明式模型并统一注册
            if (registrationsProvider != null) {
                registrationsProvider.orderedStream().forEach(registration ->
                        virtualLingManager.register(registration.getLingId(), registration.getConfig())
                );
            }
        };
    }

    @Bean
    public DisposableBean virtualLingShutdownHook(
            VirtualLingManager virtualLingManager,
            LingFrameProperties properties,
            ObjectProvider<VirtualLingRegistration> registrationsProvider) {
        return () -> {
            if (properties.getVirtualLings() != null) {
                for (String lingId : properties.getVirtualLings().keySet()) {
                    virtualLingManager.unregister(lingId);
                }
            }
            if (registrationsProvider != null) {
                registrationsProvider.orderedStream().forEach(reg ->
                        virtualLingManager.unregister(reg.getLingId())
                );
            }
        };
    }
}
