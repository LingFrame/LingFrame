package com.lingframe.starter.configuration;

import com.lingframe.api.context.LingContext;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.classloader.SharedApiManager;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.deploy.LingDeployService;
import com.lingframe.core.dev.HotSwapWatcher;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.governance.GovernanceArbitrator;
import com.lingframe.core.ling.*;
import com.lingframe.core.loader.LingDiscoveryService;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.pipeline.FilterRegistry;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.spi.*;
import com.lingframe.starter.adapter.SpringContainerFactory;
import com.lingframe.starter.event.ServiceExporterListener;
import com.lingframe.starter.processor.LingReferenceInjector;
import com.lingframe.starter.spi.LingContextCustomizer;
import com.lingframe.starter.web.WebInterfaceManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("LingFrameLifecycleBeansConfiguration 单元测试")
class LingFrameLifecycleBeansConfigurationTest {

    @Test
    @DisplayName("测试所有 Bean 的初始化及其生命周期方法")
    void testAllBeans() throws Exception {
        LingFrameLifecycleBeansConfiguration config = new LingFrameLifecycleBeansConfiguration();

        // 1. containerFactory
        ApplicationContext parentContext = mock(ApplicationContext.class);
        com.lingframe.starter.config.LingFrameProperties properties = new com.lingframe.starter.config.LingFrameProperties();
        properties.setDevMode(true);
        when(parentContext.getBean(com.lingframe.starter.config.LingFrameProperties.class)).thenReturn(properties);

        WebInterfaceManager webInterfaceManager = mock(WebInterfaceManager.class);
        ObjectProvider<List<LingContextCustomizer>> customizersProvider = new ObjectProvider<List<LingContextCustomizer>>() {
            @Override
            public List<LingContextCustomizer> getObject(Object... args) { return new ArrayList<>(); }
            @Override
            public List<LingContextCustomizer> getObject() { return new ArrayList<>(); }
            @Override
            public List<LingContextCustomizer> getIfAvailable() { return new ArrayList<>(); }
            @Override
            public List<LingContextCustomizer> getIfAvailable(java.util.function.Supplier<List<LingContextCustomizer>> defaultSupplier) {
                return defaultSupplier.get();
            }
            @Override
            public List<LingContextCustomizer> getIfUnique() { return new ArrayList<>(); }
        };
        List<ResourceGuard> resourceGuards = new ArrayList<>();
        
        ContainerFactory containerFactory = config.containerFactory(parentContext, webInterfaceManager, customizersProvider, resourceGuards);
        assertNotNull(containerFactory);

        // 2. lingLifecycleEngine
        PermissionService permissionService = mock(PermissionService.class);
        LingLoaderFactory lingLoaderFactory = mock(LingLoaderFactory.class);
        ObjectProvider<List<LingSecurityVerifier>> verifiersProvider = new ObjectProvider<List<LingSecurityVerifier>>() {
            @Override
            public List<LingSecurityVerifier> getObject(Object... args) { return new ArrayList<>(); }
            @Override
            public List<LingSecurityVerifier> getObject() { return new ArrayList<>(); }
            @Override
            public List<LingSecurityVerifier> getIfAvailable() { return new ArrayList<>(); }
            @Override
            public List<LingSecurityVerifier> getIfAvailable(java.util.function.Supplier<List<LingSecurityVerifier>> defaultSupplier) {
                return defaultSupplier.get();
            }
            @Override
            public List<LingSecurityVerifier> getIfUnique() { return new ArrayList<>(); }
        };
        EventBus eventBus = mock(EventBus.class);
        LingFrameConfig lingFrameConfig = mock(LingFrameConfig.class);
        LingRepository lingRepository = mock(LingRepository.class);
        LingServiceRegistry lingServiceRegistry = mock(LingServiceRegistry.class);
        InvocationPipelineEngine pipelineEngine = mock(InvocationPipelineEngine.class);
        LingResourceManager lingResourceManager = mock(LingResourceManager.class);
        LeakDetector leakDetector = mock(LeakDetector.class);
        RuntimeCoordinator runtimeCoordinator = mock(RuntimeCoordinator.class);

        LingLifecycleEngine lifecycleEngine = config.lingLifecycleEngine(
                containerFactory, permissionService, lingLoaderFactory, verifiersProvider, eventBus,
                lingFrameConfig, lingRepository, lingServiceRegistry, pipelineEngine, resourceGuards,
                lingResourceManager, leakDetector, runtimeCoordinator
        );
        assertNotNull(lifecycleEngine);

        // 3. filterRegistry
        InvokableMethodCache methodCache = mock(InvokableMethodCache.class);
        ObjectProvider<LingServiceInvoker> invokerProvider = mock(ObjectProvider.class);
        ObjectProvider<GovernanceArbitrator> arbitratorProvider = mock(ObjectProvider.class);
        ObjectProvider<MetricsCollector> metricsCollectorProvider = mock(ObjectProvider.class);
        ObjectProvider<GovernanceMetricsCollector> governanceMetricsCollectorProvider = mock(ObjectProvider.class);
        TrafficRouter trafficRouter = mock(TrafficRouter.class);

        FilterRegistry filterRegistry = config.filterRegistry(
                lingRepository, methodCache, permissionService, invokerProvider, arbitratorProvider,
                metricsCollectorProvider, governanceMetricsCollectorProvider, trafficRouter, eventBus, runtimeCoordinator,
                lingServiceRegistry
        );
        assertNotNull(filterRegistry);

        // 4. invocationPipelineEngine
        InvocationPipelineEngine invocationPipelineEngine = config.invocationPipelineEngine(filterRegistry);
        assertNotNull(invocationPipelineEngine);

        // 5. lingDiscoveryService
        LingDiscoveryService discoveryService = config.lingDiscoveryService(lingFrameConfig, lifecycleEngine);
        assertNotNull(discoveryService);

        // 6. lingDeployService
        LingDeployService deployService = config.lingDeployService(lifecycleEngine);
        assertNotNull(deployService);

        // 7. serviceExporterListener
        ObjectProvider<List<ServiceExporter>> exportersProvider = new ObjectProvider<List<ServiceExporter>>() {
            @Override
            public List<ServiceExporter> getObject(Object... args) { return new ArrayList<>(); }
            @Override
            public List<ServiceExporter> getObject() { return new ArrayList<>(); }
            @Override
            public List<ServiceExporter> getIfAvailable() { return new ArrayList<>(); }
            @Override
            public List<ServiceExporter> getIfAvailable(java.util.function.Supplier<List<ServiceExporter>> defaultSupplier) {
                return defaultSupplier.get();
            }
            @Override
            public List<ServiceExporter> getIfUnique() { return new ArrayList<>(); }
        };
        ServiceExporterListener exporterListener = config.serviceExporterListener(
                eventBus, lingRepository, lingServiceRegistry, exportersProvider
        );
        assertNotNull(exporterListener);
        // 执行 destroyMethod shutdown
        exporterListener.shutdown();

        // 8. sharedApiManager
        SharedApiManager sharedApiManager = config.sharedApiManager(lingFrameConfig);
        assertNotNull(sharedApiManager);

        // 9. lingScannerRunner
        ApplicationRunner runner = config.lingScannerRunner(discoveryService, sharedApiManager);
        assertNotNull(runner);
        
        // 为了避免真实 scanAndLoad 引起报错，使用 mock 实例验证流程
        LingDiscoveryService mockDiscovery = mock(LingDiscoveryService.class);
        SharedApiManager mockSharedApi = mock(SharedApiManager.class);
        ApplicationRunner runnerMock = config.lingScannerRunner(mockDiscovery, mockSharedApi);
        runnerMock.run(null);
        verify(mockSharedApi).preloadFromConfig();
        verify(mockSharedApi).freezeSharedBoundary();
        verify(mockDiscovery).scanAndLoad();

        // 再次执行，应该直接 return 走 BOOTSTRAP_DONE 校验
        reset(mockDiscovery);
        runnerMock.run(null);
        verifyNoInteractions(mockDiscovery);

        // 10. lingFrameStaticStateResetter
        DisposableBean resetter = config.lingFrameStaticStateResetter();
        assertNotNull(resetter);
        resetter.destroy();

        // 11. hotSwapWatcher
        HotSwapWatcher watcher = config.hotSwapWatcher(lifecycleEngine, lingRepository, eventBus, leakDetector);
        assertNotNull(watcher);
        // 测试 DefaultLingLifecycleEngine 分支
        DefaultLingLifecycleEngine mockEngine = mock(DefaultLingLifecycleEngine.class);
        HotSwapWatcher watcher2 = config.hotSwapWatcher(mockEngine, lingRepository, eventBus, leakDetector);
        verify(mockEngine).setHotSwapWatcher(watcher2);

        // 12. lingCoreContext
        LingContext coreContext = config.lingCoreContext(lingRepository, lingServiceRegistry, pipelineEngine, permissionService, eventBus);
        assertNotNull(coreContext);

        // 13. lingReferenceInjector
        LingReferenceInjector referenceInjector = config.lingReferenceInjector();
        assertNotNull(referenceInjector);

        // 14. webInterfaceManager
        WebInterfaceManager webInterfaceManagerBean = config.webInterfaceManager(lingRepository, trafficRouter, metricsCollectorProvider);
        assertNotNull(webInterfaceManagerBean);
    }
}
