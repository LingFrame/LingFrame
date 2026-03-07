package com.lingframe.runtime;

import com.lingframe.api.context.LingContext;
import com.lingframe.core.classloader.DefaultLingLoaderFactory;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.context.CoreLingContext;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.ling.DefaultLingLifecycleEngine;
import com.lingframe.core.ling.DefaultLingRepository;
import com.lingframe.core.ling.DefaultLingServiceRegistry;
import com.lingframe.core.ling.InvokableMethodCache;
import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.resource.BasicResourceGuard;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.loader.LingDiscoveryService;
import com.lingframe.core.pipeline.FilterRegistry;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.pipeline.LatestVersionPolicy;
import com.lingframe.core.security.DangerousApiVerifier;
import com.lingframe.core.security.DefaultPermissionService;
import com.lingframe.runtime.adapter.NativeContainerFactory;
import lombok.extern.slf4j.Slf4j;
import com.lingframe.api.exception.ServiceUnavailableException;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LingFrame Native 启动器
 * 灵核应用通过此类一键启动框架
 */
@Slf4j
public class NativeLingFrame {

    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static LingLifecycleEngine GLOBAL_LIFECYCLE_ENGINE;
    private static LingContext HOST_CONTEXT;

    /**
     * 启动 LingFrame (使用默认配置)
     */
    public static LingLifecycleEngine start() {
        return start(LingFrameConfig.current());
    }

    /**
     * 启动 LingFrame (自定义配置)
     */
    public static LingLifecycleEngine start(LingFrameConfig config) {
        if (started.get()) {
            log.warn("LingFrame is already started.");
            return GLOBAL_LIFECYCLE_ENGINE;
        }

        long start = System.currentTimeMillis();
        log.info("Starting LingFrame Native Runtime...");

        // 准备基础设施
        EventBus eventBus = new EventBus();

        // 准备核心组件
        DefaultPermissionService permissionService = new DefaultPermissionService(eventBus);
        DefaultLingLoaderFactory loaderFactory = new DefaultLingLoaderFactory();

        // 创建 Native 专用的容器工厂
        NativeContainerFactory containerFactory = new NativeContainerFactory();

        LingRepository lingRepository = new DefaultLingRepository();
        LingServiceRegistry lingServiceRegistry = new DefaultLingServiceRegistry();

        InvokableMethodCache invokableMethodCache = new InvokableMethodCache();
        FilterRegistry filterRegistry = new FilterRegistry(invokableMethodCache);
        // 初始化内置 Filter 并注入依赖
        filterRegistry.initialize(lingRepository, new LatestVersionPolicy(), eventBus);
        InvocationPipelineEngine pipelineEngine = new InvocationPipelineEngine(
                filterRegistry);

        LingLifecycleEngine lifecycleEngine = new DefaultLingLifecycleEngine(
                containerFactory,
                permissionService,
                loaderFactory,
                Collections.singletonList(new DangerousApiVerifier()), // 默认安全验证
                eventBus,
                config,
                lingRepository,
                lingServiceRegistry,
                pipelineEngine,
                Collections.singletonList(new BasicResourceGuard()));

        // 注册一个特殊的 "lingcore-app" 上下文
        HOST_CONTEXT = new CoreLingContext("lingcore-app", lingRepository, lingServiceRegistry, pipelineEngine,
                permissionService, eventBus);

        // 自动扫描灵元
        if (config.getLingRoots() != null || config.getLingHome() != null) {
            LingDiscoveryService discoveryService = new LingDiscoveryService(config, lifecycleEngine);
            log.info("Executing initial ling scan...");
            discoveryService.scanAndLoad();
        }

        // 注册关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("LingFrame shutting down...");
            // Native runtime cleanup goes here if necessary
        }));

        GLOBAL_LIFECYCLE_ENGINE = lifecycleEngine;
        started.set(true);

        log.info("LingFrame Native started in {} ms", System.currentTimeMillis() - start);

        return lifecycleEngine;
    }

    /**
     * 获取灵核上下文，用于 invoke 调用
     */
    public static LingContext getHostContext() {
        if (!started.get()) {
            throw new ServiceUnavailableException("lingcore-app", "LingFrame not started");
        }
        return HOST_CONTEXT;
    }
}