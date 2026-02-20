package com.lingframe.runtime;

import com.lingframe.api.context.LingContext;
import com.lingframe.core.classloader.DefaultLingLoaderFactory;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.context.CoreLingContext;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.governance.DefaultTransactionVerifier;
import com.lingframe.core.governance.GovernanceArbitrator;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.invoker.DefaultLingServiceInvoker;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.loader.LingDiscoveryService;
import com.lingframe.core.ling.LingManager;
import com.lingframe.core.router.LabelMatchRouter;
import com.lingframe.core.security.DangerousApiVerifier;
import com.lingframe.core.security.DefaultPermissionService;
import com.lingframe.core.spi.GovernancePolicyProvider;
import com.lingframe.runtime.adapter.NativeContainerFactory;
import lombok.extern.slf4j.Slf4j;
import com.lingframe.core.exception.ServiceUnavailableException;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LingFrame Native 启动器
 * 灵核应用通过此类一键启动框架
 */
@Slf4j
public class NativeLingFrame {

    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static LingManager GLOBAL_Ling_MANAGER;
    private static LingContext HOST_CONTEXT;

    /**
     * 启动 LingFrame (使用默认配置)
     */
    public static LingManager start() {
        return start(LingFrameConfig.current());
    }

    /**
     * 启动 LingFrame (自定义配置)
     */
    public static LingManager start(LingFrameConfig config) {
        if (started.get()) {
            log.warn("LingFrame is already started.");
            return GLOBAL_Ling_MANAGER;
        }

        long start = System.currentTimeMillis();
        log.info("Starting LingFrame Native Runtime...");

        // 准备基础设施
        EventBus eventBus = new EventBus();

        List<GovernancePolicyProvider> providers = Collections.emptyList();
        // 准备核心组件
        DefaultPermissionService permissionService = new DefaultPermissionService(eventBus);
        GovernanceArbitrator governanceArbitrator = new GovernanceArbitrator(providers);
        GovernanceKernel governanceKernel = new GovernanceKernel(permissionService, governanceArbitrator, eventBus);
        DefaultLingLoaderFactory loaderFactory = new DefaultLingLoaderFactory();

        // 准备治理组件
        DefaultLingServiceInvoker serviceInvoker = new DefaultLingServiceInvoker();
        DefaultTransactionVerifier txVerifier = new DefaultTransactionVerifier();

        // 创建 Native 专用的容器工厂
        NativeContainerFactory containerFactory = new NativeContainerFactory();

        LocalGovernanceRegistry localGovernanceRegistry = new LocalGovernanceRegistry(eventBus);

        // 组装 LingManager
        // 注意：这里需要传入 Core 需要的所有组件
        LingManager lingManager = new LingManager(
                containerFactory, // <--- 注入 Native 实现
                permissionService,
                governanceKernel,
                loaderFactory,
                Collections.singletonList(new DangerousApiVerifier()), // 默认安全验证
                eventBus,
                new LabelMatchRouter(),
                serviceInvoker,
                txVerifier,
                Collections.emptyList(), // 无 ThreadLocal 传播器
                config,
                localGovernanceRegistry,
                null); // ResourceGuard - 使用默认实现

        // 注册一个特殊的 "lingcore-app" 上下文
        HOST_CONTEXT = new CoreLingContext("lingcore-app", lingManager, permissionService, eventBus);

        // 自动扫描单元
        // 模拟 Spring Boot Starter 中的 ApplicationRunner 逻辑
        if (config.getLingRoots() != null || config.getLingHome() != null) {
            LingDiscoveryService discoveryService = new LingDiscoveryService(config, lingManager);
            log.info("Executing initial ling scan...");
            discoveryService.scanAndLoad();
        }

        // 7. 注册关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("LingFrame shutting down...");
            lingManager.shutdown();
        }));

        GLOBAL_Ling_MANAGER = lingManager;
        started.set(true);

        log.info("LingFrame Native started in {} ms", System.currentTimeMillis() - start);

        return lingManager;
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