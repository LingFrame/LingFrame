package com.lingframe.benchmark;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.ling.*;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.pipeline.FilterRegistry;
import com.lingframe.core.pipeline.LatestVersionPolicy;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.security.DefaultPermissionService;
import com.lingframe.core.spi.ContainerFactory;
import com.lingframe.core.spi.LingContainer;
import com.lingframe.core.spi.LingLoaderFactory;
import com.lingframe.core.spi.LingSecurityVerifier;
import com.lingframe.core.spi.ResourceGuard;
import com.lingframe.core.resource.DefaultLeakDetector;
import com.bench.TestService;

import java.io.File;
import java.util.Collections;

/**
 * Benchmark 部署辅助工具
 * <p>
 * 通过 {@link DefaultLingLifecycleEngine#deploy} 完成灵元部署，
 * 让双层状态机（RuntimeCoordinator + InstanceCoordinator）自动协调状态推进，
 * 而非通过反射 hack 绕过框架的封装边界。
 * <p>
 * 部署流程：
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

    public BenchmarkDeploymentHelper() {
        this.eventBus = new EventBus();
        this.lingRepository = new DefaultLingRepository();
        this.leakDetector = new DefaultLeakDetector();

        PermissionService permissionService = new DefaultPermissionService(eventBus);
        InvokableMethodCache methodCache = new InvokableMethodCache();
        DefaultLingServiceRegistry serviceRegistry = new DefaultLingServiceRegistry();

        this.filterRegistry = new FilterRegistry(methodCache, permissionService);
        filterRegistry.initialize(lingRepository, new LatestVersionPolicy(), eventBus);

        this.pipelineEngine = new InvocationPipelineEngine(filterRegistry);

        RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(eventBus);
        runtimeCoordinator.start();

        DefaultLingResourceManager resourceManager = new DefaultLingResourceManager(
                lingRepository, eventBus, methodCache);

        LingUnloadCoordinator unloadCoordinator = new LingUnloadCoordinator(
                pipelineEngine, Collections.<ResourceGuard>emptyList(), resourceManager, leakDetector);

        this.lifecycleEngine = new DefaultLingLifecycleEngine(
                new BenchmarkContainerFactory(),
                permissionService,
                new BenchmarkLoaderFactory(),
                Collections.<LingSecurityVerifier>emptyList(),     // verifiers
                eventBus,
                LingFrameConfig.current(),
                lingRepository,
                serviceRegistry,
                pipelineEngine,
                resourceManager,
                unloadCoordinator,
                runtimeCoordinator);
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

    /**
     * Benchmark 专用的 ContainerFactory
     * <p>
     * 返回一个包含真实可调用 Bean 的 LingContainer，
     * 使 NORMAL 模式的 Pipeline 能走通终端调用路径。
     */
    private static class BenchmarkContainerFactory implements ContainerFactory {
        @Override
        public LingContainer create(com.lingframe.api.config.LingDefinition definition, File jarFile,
                ClassLoader classLoader) {
            return new BenchmarkContainer(classLoader);
        }
    }

    /**
     * Benchmark 专用的 LingContainer
     * <p>
     * 提供一个真实的 {@link TestService} Bean，
     * 使 Pipeline NORMAL 模式能走通终端调用（loadClass → getBean(Class) → MethodHandle → ping()），
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
        public void start(com.lingframe.api.context.LingContext context) {
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
        public java.lang.Object getBean(String beanName) {
            if ("com.bench.TestService".equals(beanName)) {
                return testService;
            }
            return null;
        }

        @Override
        public String[] getBeanNames() {
            return new String[]{"com.bench.TestService"};
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
            return parent;
        }
    }
}
