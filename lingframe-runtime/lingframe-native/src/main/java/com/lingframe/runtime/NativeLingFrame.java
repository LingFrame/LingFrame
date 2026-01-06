package com.lingframe.runtime;

import com.lingframe.api.context.PluginContext;
import com.lingframe.core.classloader.DefaultPluginLoaderFactory;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.context.CorePluginContext;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.governance.DefaultTransactionVerifier;
import com.lingframe.core.governance.GovernanceArbitrator;
import com.lingframe.core.invoker.DefaultPluginServiceInvoker;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.loader.PluginDiscoveryService;
import com.lingframe.core.plugin.PluginManager;
import com.lingframe.core.router.LabelMatchRouter;
import com.lingframe.core.security.DangerousApiVerifier;
import com.lingframe.core.security.DefaultPermissionService;
import com.lingframe.core.spi.GovernancePolicyProvider;
import com.lingframe.runtime.adapter.NativeContainerFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LingFrame Native 启动器
 * 宿主应用通过此类一键启动框架
 */
@Slf4j
public class NativeLingFrame {

    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static PluginManager GLOBAL_PLUGIN_MANAGER;
    private static PluginContext HOST_CONTEXT;

    /**
     * 启动 LingFrame (使用默认配置)
     */
    public static PluginManager start() {
        return start(LingFrameConfig.current());
    }

    /**
     * 启动 LingFrame (自定义配置)
     */
    public static PluginManager start(LingFrameConfig config) {
        if (started.get()) {
            log.warn("LingFrame is already started.");
            return GLOBAL_PLUGIN_MANAGER;
        }

        long start = System.currentTimeMillis();
        log.info("Starting LingFrame Native Runtime...");

        // 准备基础设施
        EventBus eventBus = new EventBus();

        List<GovernancePolicyProvider> providers = Collections.emptyList();
        // 准备核心组件
        DefaultPermissionService permissionService = new DefaultPermissionService();
        GovernanceArbitrator governanceArbitrator = new GovernanceArbitrator(providers);
        GovernanceKernel governanceKernel = new GovernanceKernel(permissionService, governanceArbitrator, eventBus);
        DefaultPluginLoaderFactory loaderFactory = new DefaultPluginLoaderFactory();

        // 准备治理组件
        LabelMatchRouter trafficRouter = new LabelMatchRouter();
        DefaultPluginServiceInvoker serviceInvoker = new DefaultPluginServiceInvoker();
        DefaultTransactionVerifier txVerifier = new DefaultTransactionVerifier();

        // 创建 Native 专用的容器工厂
        NativeContainerFactory containerFactory = new NativeContainerFactory();

        // 组装 PluginManager
        // 注意：这里需要传入 Core 需要的所有组件
        PluginManager pluginManager = new PluginManager(
                containerFactory,       // <--- 注入 Native 实现
                permissionService,
                governanceKernel,
                loaderFactory,
                Collections.singletonList(new DangerousApiVerifier()), // 默认安全验证
                eventBus,
                trafficRouter,
                serviceInvoker,
                txVerifier,
                Collections.emptyList(), // 无 ThreadLocal 传播器
                config
        );

        // 注册一个特殊的 "host-app" 上下文
        HOST_CONTEXT = new CorePluginContext("host-app", pluginManager, permissionService, eventBus);

        // 自动扫描插件
        // 模拟 Spring Boot Starter 中的 ApplicationRunner 逻辑
        if (config.getPluginRoots() != null || config.getPluginHome() != null) {
            PluginDiscoveryService discoveryService = new PluginDiscoveryService(config, pluginManager);
            log.info("Executing initial plugin scan...");
            discoveryService.scanAndLoad();
        }

        // 7. 注册关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("LingFrame shutting down...");
            pluginManager.shutdown();
        }));

        GLOBAL_PLUGIN_MANAGER = pluginManager;
        started.set(true);

        log.info("LingFrame Native started in {} ms", System.currentTimeMillis() - start);

        return pluginManager;
    }

    /**
     * 获取宿主上下文，用于 invoke 调用
     */
    public static PluginContext getHostContext() {
        if (!started.get()) {
            throw new IllegalStateException("LingFrame not started");
        }
        return HOST_CONTEXT;
    }
}