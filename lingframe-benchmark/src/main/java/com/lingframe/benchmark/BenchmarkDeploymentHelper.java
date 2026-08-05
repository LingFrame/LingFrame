package com.lingframe.benchmark;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.context.LingContext;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.ling.*;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.pipeline.FilterRegistry;
import com.lingframe.core.pipeline.FilterRegistryConfig;
import com.lingframe.core.pipeline.LatestVersionPolicy;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.security.DefaultPermissionService;
import com.lingframe.core.spi.ContainerFactory;
import com.lingframe.core.spi.LingContainer;
import com.lingframe.core.resource.DebuggerCaptureUnloadHook;
import com.lingframe.core.resource.JdbcDriverUnloadHook;
import com.lingframe.core.resource.JvmShutdownHookUnloadHook;
import com.lingframe.core.resource.LoggingFrameworkUnloadHook;
import com.lingframe.core.resource.RmiTargetUnloadHook;
import com.lingframe.core.resource.ThreadReferenceUnloadHook;
import com.lingframe.core.spi.LingLoaderFactory;
import com.lingframe.core.spi.LingSecurityVerifier;
import com.lingframe.core.spi.LingUnloadHook;
import com.lingframe.core.resource.DefaultLeakDetector;
import com.bench.TestService;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Benchmark 部署辅助工具
 * <p>
 * 通过 {@link DefaultLingLifecycleEngine#deploy} 完成灵元部署，
 * 让双层状态机（RuntimeCoordinator + InstanceCoordinator）自动协调状态推进，
 * 而非通过反射 hack 绕过框架的封装边界。
 * <p>
 * 部署流程：
 * 
 * <pre>
 * deploy()
 *   → RuntimeCoordinator.register(lingId)        // 宏观状态 CREATED
 *   → InstanceCoordinator.prepare(instance)       // 微观状态 CREATED → LOADING
 *   → InstanceCoordinator.start(instance)         // 微观状态 LOADING → STARTING
 *   → InstanceCoordinator.markReady(instance)     // 微观状态 STARTING → READY
 *   → RuntimeCoordinator 聚合 → ACTIVE            // 宏观状态 ACTIVE
 * </pre>
 */
public class BenchmarkDeploymentHelper {

    private final DefaultLingLifecycleEngine lifecycleEngine;
    private final DefaultLingRepository lingRepository;
    private final EventBus eventBus;
    private final InvocationPipelineEngine pipelineEngine;
    private final FilterRegistry filterRegistry;
    private final DefaultLeakDetector leakDetector;
    private final RuntimeCoordinator runtimeCoordinator;

    /**
     * 默认构造：卸载时不装配任何资源清理钩子（剥离资源清理噪声，专注状态机纯 CPU 开销）。
     */
    public BenchmarkDeploymentHelper() {
        this(false);
    }

    /**
     * 可选装配真实 JVM 资源清理钩子的构造。
     * <p>
     * 当 {@code enableRealHooks=true} 时，按生产装配顺序
     * （{@code LingFrameLifecycleBeansConfiguration.jvmHooks}）注入 6 个 JVM 桶钩子，
     * 使卸载路径包含反射扫描与资源清理的真实开销。
     * <p>
     * 注意：Spring 生态桶钩子（SpringEcosystemUnloadHook/BindConverterCacheCleaner 等）
     * 为包级可见，benchmark 模块无法直接构造，故生态桶始终为空。
     *
     * @param enableRealHooks 是否装配生产级 JVM 资源清理钩子
     */
    public BenchmarkDeploymentHelper(boolean enableRealHooks) {
        // 定制全局 LingFrameConfig 模板，提供极大的限流额度与并发限制，避免压测拦截与瓶颈
        LingRuntimeConfig customRuntimeConfig = LingRuntimeConfig.builder()
                .rateLimitPerSecond(1000000000) // 10亿，相当于不限流
                .bulkheadMaxConcurrent(1000000) // 100万，舱壁极大值
                .build();
        LingFrameConfig customFrameConfig = LingFrameConfig.builder()
                .runtimeConfig(customRuntimeConfig)
                .build();
        LingFrameConfig.clear();
        LingFrameConfig.init(customFrameConfig);

        this.eventBus = new EventBus();
        this.lingRepository = new DefaultLingRepository();
        this.leakDetector = new DefaultLeakDetector(eventBus, LingFrameConfig.current());

        PermissionService permissionService = new DefaultPermissionService(eventBus, LingFrameConfig.current());
        InvokableMethodCache methodCache = new InvokableMethodCache();
        DefaultLingServiceRegistry serviceRegistry = new DefaultLingServiceRegistry();

        this.filterRegistry = new FilterRegistry(FilterRegistryConfig.builder()
                .methodCache(methodCache)
                .permissionService(permissionService)
                .lingRepository(lingRepository)
                .trafficRouter(new LatestVersionPolicy())
                .eventBus(eventBus)
                .build());

        this.pipelineEngine = new InvocationPipelineEngine(filterRegistry);

        this.runtimeCoordinator = new RuntimeCoordinator(eventBus);
        this.runtimeCoordinator.start();

        DefaultLingResourceManager resourceManager = new DefaultLingResourceManager(
                lingRepository, eventBus, methodCache);

        LingUnloadCoordinator unloadCoordinator;
        if (enableRealHooks) {
            // 与生产 LingFrameLifecycleBeansConfiguration.jvmHooks 完全一致的装配顺序
            List<LingUnloadHook> jvmHooks = Arrays.asList(
                    new JdbcDriverUnloadHook(),
                    new ThreadReferenceUnloadHook(),
                    new JvmShutdownHookUnloadHook(),
                    new RmiTargetUnloadHook(),
                    new LoggingFrameworkUnloadHook(),
                    new DebuggerCaptureUnloadHook());
            // 双桶构造：生态桶留空（Spring 生态钩子包级不可见），JVM 桶装配真实钩子
            unloadCoordinator = new LingUnloadCoordinator(
                    pipelineEngine, Collections.<LingUnloadHook>emptyList(), jvmHooks,
                    resourceManager, leakDetector);
        } else {
            // 原有行为：全部桶为空，剥离资源清理噪声
            // 构造为两桶签名：ecosystemHooks + jvmHooks 均为空
            unloadCoordinator = new LingUnloadCoordinator(
                    pipelineEngine, Collections.<LingUnloadHook>emptyList(), Collections.<LingUnloadHook>emptyList(),
                    resourceManager, leakDetector);
        }

        this.lifecycleEngine = new DefaultLingLifecycleEngine(LifecycleEngineConfig.builder()
                .containerFactory(new BenchmarkContainerFactory())
                .permissionService(permissionService)
                .lingLoaderFactory(new BenchmarkLoaderFactory())
                .verifiers(Collections.<LingSecurityVerifier>emptyList())
                .eventBus(eventBus)
                .lingFrameConfig(LingFrameConfig.current())
                .lingRepository(lingRepository)
                .lingServiceRegistry(serviceRegistry)
                .pipelineEngine(pipelineEngine)
                .lingResourceManager(resourceManager)
                .unloadCoordinator(unloadCoordinator)
                .runtimeCoordinator(runtimeCoordinator)
                .build());
    }

    /**
     * 通过双层状态机正确部署一个 ACTIVE 灵元
     */
    public void deployLing(String lingId, String version) {
        LingDefinition definition = new LingDefinition();
        definition.setId(lingId);
        definition.setVersion(version);
        lifecycleEngine.deploy(definition, null, true, Collections.<String, String>emptyMap());
    }

    /**
     * 通过双层状态机正确卸载一个灵元并回收全部关联资源
     */
    public void undeployLing(String lingId) {
        lifecycleEngine.undeploy(lingId);
    }

    public DefaultLingRepository getLingRepository() {
        return lingRepository;
    }

    public EventBus getEventBus() {
        return eventBus;
    }

    public InvocationPipelineEngine getPipelineEngine() {
        return pipelineEngine;
    }

    public FilterRegistry getFilterRegistry() {
        return filterRegistry;
    }

    public RuntimeCoordinator getRuntimeCoordinator() {
        return runtimeCoordinator;
    }

    /**
     * Benchmark 专用的 ContainerFactory
     * <p>
     * 返回一个包含真实可调用 Bean 的 LingContainer，
     * 使 NORMAL 模式的 Pipeline 能走通终端调用路径。
     */
    private static class BenchmarkContainerFactory implements ContainerFactory {
        @Override
        public LingContainer create(LingDefinition definition, File jarFile,
                ClassLoader classLoader) {
            return new BenchmarkContainer(classLoader);
        }
    }

    /**
     * Benchmark 专用的 LingContainer
     * <p>
     * 提供一个真实的 {@link TestService} Bean，
     * 使 Pipeline NORMAL 模式能走通终端调用（loadClass → getBean(Class) → MethodHandle →
     * ping()），
     * 而非走 ClassNotFoundException → 兜底 getBean(String) 的噪声路径。
     */
    private static class BenchmarkContainer implements LingContainer {
        private final ClassLoader classLoader;
        private volatile boolean active = false;

        /** 预创建的服务 Bean，TerminalInvokerFilter 通过 getBean() 获取 */
        private final TestService testService = new TestService();

        BenchmarkContainer(ClassLoader classLoader) {
            this.classLoader = classLoader != null ? classLoader : BenchmarkContainer.class.getClassLoader();
        }

        @Override
        public void start(LingContext context) {
            active = true;
        }

        @Override
        public void stop() {
            active = false;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T getBean(Class<T> type) {
            if (type == TestService.class) {
                return (T) testService;
            }
            return null;
        }

        @Override
        public Object getBean(String beanName) {
            if ("com.bench.TestService".equals(beanName)) {
                return testService;
            }
            return null;
        }

        @Override
        public String[] getBeanNames() {
            return new String[] { "com.bench.TestService" };
        }

        @Override
        public ClassLoader getClassLoader() {
            return classLoader;
        }
    }

    /**
     * Benchmark 专用的 LingLoaderFactory
     * <p>
     * 直接返回父 ClassLoader，不做任何类加载隔离。
     * TestService 在 benchmark 模块的 classpath 中，系统 ClassLoader 可直接加载。
     */
    private static class BenchmarkLoaderFactory implements LingLoaderFactory {
        @Override
        public ClassLoader create(String lingId, File sourceFile, ClassLoader parent) {
            return new URLClassLoader(new URL[0], parent);
        }
    }
}
