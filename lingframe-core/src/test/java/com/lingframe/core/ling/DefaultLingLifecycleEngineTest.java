package com.lingframe.core.ling;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.alert.AlertManager;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.dev.HotSwapWatcher;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.routing.MigrationStateHolder;
import com.lingframe.core.spi.ContainerFactory;
import com.lingframe.core.spi.LeakRiskLevel;
import com.lingframe.core.spi.LeakRiskReport;
import com.lingframe.core.spi.LingContainer;
import com.lingframe.core.spi.LingLoaderFactory;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DefaultLingLifecycleEngine 测试")
class DefaultLingLifecycleEngineTest {

    @Test
    @DisplayName("describeActiveInvocations 应输出阻塞排空的活跃调用摘要")
    void describeActiveInvocationsShouldRenderDrainBlockerSummaries() {
        LingContainer container = mock(LingContainer.class);
        when(container.isActive()).thenReturn(true);
        when(container.getClassLoader()).thenReturn(getClass().getClassLoader());

        LingDefinition definition = new LingDefinition();
        definition.setId("test-ling");
        definition.setVersion("1.0.0");

        LingInstance instance = new LingInstance(container, definition, new EventBus());
        InstanceCoordinator coordinator = new InstanceCoordinator(null);
        coordinator.prepare(instance);
        coordinator.start(instance);
        coordinator.markReady(instance);

        long invocationId = instance.beginInvocation(new ActiveInvocationSnapshot(
                "trace-123",
                "test-ling:demo.Service",
                "execute",
                "caller-a",
                "POST /demo",
                "1.0.0",
                1000L,
                7L,
                "worker-7"));

        List<String> summaries = DefaultLingLifecycleEngine.describeActiveInvocations(instance, 1250L);

        assertTrue(invocationId > 0);
        assertEquals(1, summaries.size());
        assertTrue(summaries.get(0).contains("traceId=trace-123"));
        assertTrue(summaries.get(0).contains("service=test-ling:demo.Service"));
        assertTrue(summaries.get(0).contains("ageMs=250"));
        assertTrue(summaries.get(0).contains("thread=worker-7(7)"));

        instance.completeInvocation(invocationId);
    }

    @Test
    @DisplayName("undeployWithReport 应先做预检并继续现有卸载链路")
    void undeployWithReportShouldPrecheckAndContinueUnloadFlow() {
        EventBus eventBus = new EventBus();
        RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(eventBus);
        runtimeCoordinator.start();

        ContainerFactory containerFactory = mock(ContainerFactory.class);
        PermissionService permissionService = mock(PermissionService.class);
        LingLoaderFactory loaderFactory = mock(LingLoaderFactory.class);
        LingServiceRegistry serviceRegistry = mock(LingServiceRegistry.class);
        when(serviceRegistry.getServicesByLingId("ling1")).thenReturn(Collections.emptyList());

        LingUnloadCoordinator unloadCoordinator = mock(LingUnloadCoordinator.class);
        ClassLoader targetClassLoader = new ClassLoader() {
        };
        LeakRiskReport report = LeakRiskReport.riskDetected(
                "ling1",
                "1.0.0",
                "risk detected",
                Collections.singletonList("thread=test"),
                "test");
        when(unloadCoordinator.checkBeforeVersionUnload("ling1", "1.0.0", targetClassLoader)).thenReturn(report);

        DefaultLingRepository repository = new DefaultLingRepository();
        DefaultLingLifecycleEngine engineWithRepository = new DefaultLingLifecycleEngine(LifecycleEngineConfig.builder()
                .containerFactory(containerFactory)
                .permissionService(permissionService)
                .lingLoaderFactory(loaderFactory)
                .verifiers(Collections.emptyList())
                .eventBus(eventBus)
                .lingFrameConfig(LingFrameConfig.builder()
                        .runtimeConfig(LingRuntimeConfig.builder().forceCleanupDelaySeconds(0).build())
                        .build())
                .lingRepository(repository)
                .lingServiceRegistry(serviceRegistry)
                .pipelineEngine(mock(InvocationPipelineEngine.class))
                .lingResourceManager(null)
                .unloadCoordinator(unloadCoordinator)
                .runtimeCoordinator(runtimeCoordinator)
                .build());

        LingContainer container = mock(LingContainer.class);
        when(container.isActive()).thenReturn(true);
        when(container.getClassLoader()).thenReturn(targetClassLoader);

        LingDefinition definition = new LingDefinition();
        definition.setId("ling1");
        definition.setVersion("1.0.0");
        definition.setMainClass("demo.Main");

        LingInstance instance = new LingInstance(container, definition, eventBus);
        InstanceCoordinator coordinator = new InstanceCoordinator(eventBus);
        coordinator.prepare(instance);
        coordinator.start(instance);
        coordinator.markReady(instance);

        LingRuntime runtime = new LingRuntime(
                "ling1",
                LingRuntimeConfig.builder().forceCleanupDelaySeconds(0).build(),
                eventBus,
                coordinator,
                runtimeCoordinator);
        runtime.getInstancePool().addInstance(instance, true);
        repository.register(runtime);

        try {
            LingUninstallResult result = engineWithRepository.undeployWithReport("ling1", "1.0.0");

            assertTrue(result.isUninstallTriggered());
            assertEquals(LeakRiskLevel.RISK_DETECTED, result.getOverallRiskLevel());
            assertEquals(1, result.getReports().size());

            verify(unloadCoordinator).checkBeforeVersionUnload("ling1", "1.0.0", targetClassLoader);
            verify(unloadCoordinator).onVersionUnload("ling1", "1.0.0", targetClassLoader);
            verify(unloadCoordinator).detectLeak("ling1", "1.0.0", targetClassLoader);
        } finally {
            runtimeCoordinator.stop();
        }
    }

    @Test
    @DisplayName("recover 应重置治理状态并把错误实例拉回 READY")
    void recoverShouldDriveErroredInstanceBackToReady() {
        EventBus eventBus = new EventBus();
        RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(eventBus);
        runtimeCoordinator.start();

        ContainerFactory containerFactory = mock(ContainerFactory.class);
        PermissionService permissionService = mock(PermissionService.class);
        LingLoaderFactory loaderFactory = mock(LingLoaderFactory.class);
        LingServiceRegistry serviceRegistry = mock(LingServiceRegistry.class);
        InvocationPipelineEngine pipelineEngine = mock(InvocationPipelineEngine.class);

        DefaultLingRepository repository = new DefaultLingRepository();
        DefaultLingLifecycleEngine engine = new DefaultLingLifecycleEngine(LifecycleEngineConfig.builder()
                .containerFactory(containerFactory)
                .permissionService(permissionService)
                .lingLoaderFactory(loaderFactory)
                .verifiers(Collections.emptyList())
                .eventBus(eventBus)
                .lingFrameConfig(LingFrameConfig.builder().build())
                .lingRepository(repository)
                .lingServiceRegistry(serviceRegistry)
                .pipelineEngine(pipelineEngine)
                .lingResourceManager(null)
                .unloadCoordinator(mock(LingUnloadCoordinator.class))
                .runtimeCoordinator(runtimeCoordinator)
                .build());

        LingContainer container = mock(LingContainer.class);
        when(container.isActive()).thenReturn(true);
        when(container.getClassLoader()).thenReturn(getClass().getClassLoader());

        LingDefinition definition = new LingDefinition();
        definition.setId("ling1");
        definition.setVersion("1.0.0");
        definition.setMainClass("demo.Main");

        // 先 register，再推进实例状态：与生产 ensureRuntimeForDeployment 顺序一致
        runtimeCoordinator.register("ling1");

        LingInstance instance = new LingInstance(container, definition, eventBus);
        InstanceCoordinator coordinator = new InstanceCoordinator(eventBus);
        coordinator.prepare(instance);
        coordinator.start(instance);
        coordinator.error(instance);

        LingRuntime runtime = new LingRuntime("ling1", LingRuntimeConfig.defaults(), eventBus, coordinator, runtimeCoordinator);
        runtime.getInstancePool().addInstance(instance, true);
        repository.register(runtime);

        try {
            engine.recover("ling1", "1.0.0");

            assertEquals(com.lingframe.core.fsm.InstanceStatus.READY, instance.currentStatus());
            assertEquals(com.lingframe.core.fsm.RuntimeStatus.ACTIVE, runtime.currentStatus());
            verify(pipelineEngine).recoverLingGovernance("ling1");
            verify(container).start(ArgumentMatchers.any());
            verify(container, never()).stop();
        } finally {
            runtimeCoordinator.stop();
        }
    }

    @Test
    @DisplayName("getter 方法返回正确值")
    void shouldReturnGetters() {
        EventBus eventBus = new EventBus();
        RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(eventBus);
        runtimeCoordinator.start();

        ContainerFactory containerFactory = mock(ContainerFactory.class);
        PermissionService permissionService = mock(PermissionService.class);
        LingLoaderFactory loaderFactory = mock(LingLoaderFactory.class);
        LingServiceRegistry serviceRegistry = mock(LingServiceRegistry.class);
        InvocationPipelineEngine pipelineEngine = mock(InvocationPipelineEngine.class);

        DefaultLingRepository repository = new DefaultLingRepository();
        DefaultLingLifecycleEngine engine = new DefaultLingLifecycleEngine(LifecycleEngineConfig.builder()
                .containerFactory(containerFactory)
                .permissionService(permissionService)
                .lingLoaderFactory(loaderFactory)
                .verifiers(Collections.emptyList())
                .eventBus(eventBus)
                .lingFrameConfig(LingFrameConfig.builder().build())
                .lingRepository(repository)
                .lingServiceRegistry(serviceRegistry)
                .pipelineEngine(pipelineEngine)
                .lingResourceManager(null)
                .unloadCoordinator(mock(LingUnloadCoordinator.class))
                .runtimeCoordinator(runtimeCoordinator)
                .build());

        assertSame(repository, engine.getRepository());
        assertSame(serviceRegistry, engine.getServiceRegistry());
        assertSame(pipelineEngine, engine.getPipelineEngine());
        assertSame(eventBus, engine.getEventBus());

        runtimeCoordinator.stop();
    }

    @Test
    @DisplayName("Builder 注入 MetricsCollector 后 getter 正常返回")
    void shouldSetAndGetMetricsCollector() {
        EventBus eventBus = new EventBus();
        RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(eventBus);
        runtimeCoordinator.start();

        MetricsCollector metricsCollector = mock(MetricsCollector.class);
        DefaultLingLifecycleEngine engine = createMinimalEngine(eventBus, runtimeCoordinator, metricsCollector, null, null);

        assertTrue(engine.getMetricsCollector().isPresent());
        assertSame(metricsCollector, engine.getMetricsCollector().get());

        runtimeCoordinator.stop();
    }

    @Test
    @DisplayName("Builder 注入 GovernanceMetricsCollector 后 getter 正常返回")
    void shouldSetAndGetGovernanceMetricsCollector() {
        EventBus eventBus = new EventBus();
        RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(eventBus);
        runtimeCoordinator.start();

        GovernanceMetricsCollector gmc = mock(GovernanceMetricsCollector.class);
        DefaultLingLifecycleEngine engine = createMinimalEngine(eventBus, runtimeCoordinator, null, gmc, null);

        assertTrue(engine.getGovernanceMetricsCollector().isPresent());
        assertSame(gmc, engine.getGovernanceMetricsCollector().get());

        runtimeCoordinator.stop();
    }

    @Test
    @DisplayName("Builder 注入 AlertManager 后 getter 正常返回")
    void shouldSetAndGetAlertManager() {
        EventBus eventBus = new EventBus();
        RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(eventBus);
        runtimeCoordinator.start();

        AlertManager alertManager = mock(AlertManager.class);
        DefaultLingLifecycleEngine engine = createMinimalEngine(eventBus, runtimeCoordinator, null, null, alertManager);

        assertTrue(engine.getAlertManager().isPresent());
        assertSame(alertManager, engine.getAlertManager().get());

        runtimeCoordinator.stop();
    }

    @Test
    @DisplayName("getLeakDetector 默认为空")
    void shouldReturnEmptyLeakDetector() {
        EventBus eventBus = new EventBus();
        RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(eventBus);
        runtimeCoordinator.start();

        DefaultLingLifecycleEngine engine = createMinimalEngine(eventBus, runtimeCoordinator);
        assertFalse(engine.getLeakDetector().isPresent());

        runtimeCoordinator.stop();
    }

    @Test
    @DisplayName("Builder 注入可选项后 getter 正常返回")
    void shouldExposeBuilderInjectedOptionals() {
        EventBus eventBus = new EventBus();
        RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(eventBus);
        runtimeCoordinator.start();

        HotSwapWatcher watcher = mock(HotSwapWatcher.class);
        MigrationStateHolder migrationStateHolder =
                new MigrationStateHolder();

        DefaultLingLifecycleEngine engine = new DefaultLingLifecycleEngine(LifecycleEngineConfig.builder()
                .containerFactory(mock(ContainerFactory.class))
                .permissionService(mock(PermissionService.class))
                .lingLoaderFactory(mock(LingLoaderFactory.class))
                .verifiers(Collections.emptyList())
                .eventBus(eventBus)
                .lingFrameConfig(LingFrameConfig.builder().build())
                .lingRepository(new DefaultLingRepository())
                .lingServiceRegistry(mock(LingServiceRegistry.class))
                .pipelineEngine(mock(InvocationPipelineEngine.class))
                .lingResourceManager(null)
                .unloadCoordinator(mock(LingUnloadCoordinator.class))
                .runtimeCoordinator(runtimeCoordinator)
                .hotSwapWatcher(watcher)
                .migrationStateHolder(migrationStateHolder)
                .build());

        assertNotNull(engine.getMigrationStateHolder());
        runtimeCoordinator.stop();
    }

    @Test
    @DisplayName("替换默认实例时应把旧默认实例移送濒死队列，不应成为孤儿引用")
    void replacingDefaultInstanceShouldMoveOldOneToDyingQueue() throws Exception {
        EventBus eventBus = new EventBus();
        RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(eventBus);
        runtimeCoordinator.start();

        DefaultLingLifecycleEngine engine = createMinimalEngine(eventBus, runtimeCoordinator);
        InstanceCoordinator coordinator = new InstanceCoordinator(eventBus);

        runtimeCoordinator.register("ling1");
        LingRuntime runtime = new LingRuntime(
                "ling1",
                LingRuntimeConfig.defaults(),
                eventBus,
                coordinator,
                runtimeCoordinator);

        // 先部署一个默认实例 v1.0.0
        LingInstance v1 = createReadyInstance("ling1", "1.0.0", coordinator);
        runtime.getInstancePool().addInstance(v1, true);

        // 再部署一个新默认实例 v2.0.0：通过反射调用 publishReadyInstance 触发旧默认移送逻辑
        // publishReadyInstance 是 private，直接调 pool.addInstance 不会触发修复后的移送
        LingInstance v2 = createReadyInstance("ling1", "2.0.0", coordinator);
        invokePublishReady(engine, runtime, v2, true);

        // 旧默认 v1 应该已进入濒死队列（isDying 为真）
        assertTrue(v1.isDying(),
                "被替换的旧默认实例应被 moveToDying 推进到 STOPPING，而非成为孤儿引用");
        // 新默认应为 v2
        assertEquals(v2, runtime.getInstancePool().getDefault());
        // 濒死队列计数应至少 1
        assertTrue(runtime.getInstancePool().getDyingCount() >= 1,
                "旧默认实例应计入 dyingCount，使排空统计可观测");

        runtimeCoordinator.stop();
    }

    private void invokePublishReady(DefaultLingLifecycleEngine engine, LingRuntime runtime,
                                    LingInstance instance, boolean isDefault) throws Exception {
        java.lang.reflect.Method m = DefaultLingLifecycleEngine.class.getDeclaredMethod(
                "publishReadyInstance", LingRuntime.class, LingInstance.class, boolean.class);
        m.setAccessible(true);
        m.invoke(engine, runtime, instance, isDefault);
    }

    private LingInstance createReadyInstance(String lingId, String version, InstanceCoordinator coordinator) {
        LingContainer container = mock(LingContainer.class);
        when(container.isActive()).thenReturn(true);
        when(container.getClassLoader()).thenReturn(getClass().getClassLoader());

        LingDefinition definition = new LingDefinition();
        definition.setId(lingId);
        definition.setVersion(version);
        definition.setMainClass("demo.Main");

        LingInstance instance = new LingInstance(container, definition, new EventBus());
        coordinator.prepare(instance);
        coordinator.start(instance);
        coordinator.markReady(instance);
        return instance;
    }

    private DefaultLingLifecycleEngine createMinimalEngine(EventBus eventBus, RuntimeCoordinator runtimeCoordinator) {
        return createMinimalEngine(eventBus, runtimeCoordinator, null, null, null);
    }

    private DefaultLingLifecycleEngine createMinimalEngine(EventBus eventBus, RuntimeCoordinator runtimeCoordinator,
            MetricsCollector metricsCollector, GovernanceMetricsCollector governanceMetricsCollector,
            AlertManager alertManager) {
        ContainerFactory containerFactory = mock(ContainerFactory.class);
        PermissionService permissionService = mock(PermissionService.class);
        LingLoaderFactory loaderFactory = mock(LingLoaderFactory.class);
        LingServiceRegistry serviceRegistry = mock(LingServiceRegistry.class);

        return new DefaultLingLifecycleEngine(LifecycleEngineConfig.builder()
                .containerFactory(containerFactory)
                .permissionService(permissionService)
                .lingLoaderFactory(loaderFactory)
                .verifiers(Collections.emptyList())
                .eventBus(eventBus)
                .lingFrameConfig(LingFrameConfig.builder().build())
                .lingRepository(new DefaultLingRepository())
                .lingServiceRegistry(serviceRegistry)
                .pipelineEngine(mock(InvocationPipelineEngine.class))
                .lingResourceManager(null)
                .unloadCoordinator(mock(LingUnloadCoordinator.class))
                .runtimeCoordinator(runtimeCoordinator)
                .metricsCollector(metricsCollector)
                .governanceMetricsCollector(governanceMetricsCollector)
                .alertManager(alertManager)
                .build());
    }
}
