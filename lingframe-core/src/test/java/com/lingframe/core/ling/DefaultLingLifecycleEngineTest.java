package com.lingframe.core.ling;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.alert.AlertManager;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.dev.HotSwapWatcher;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.InstanceStatus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.fsm.RuntimeStatus;
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
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
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
        when(container.getClassLoader()).thenReturn(createSafeTestClassLoader());

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
        when(container.getClassLoader()).thenReturn(createSafeTestClassLoader());

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

            assertEquals(InstanceStatus.READY, instance.currentStatus());
            assertEquals(RuntimeStatus.ACTIVE, runtime.currentStatus());
            verify(pipelineEngine).recoverLingGovernance("ling1");
            verify(container).start(ArgumentMatchers.any());
            verify(container, never()).stop();
        } finally {
            runtimeCoordinator.stop();
        }
    }

    @Test
    @DisplayName("recover 治理重置失败时收口 DEGRADED 并重抛异常，runtime 不卡死 RECOVERING")
    void recoverGovernanceResetFailureConvergesToDegraded() {
        EventBus eventBus = new EventBus();
        RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(eventBus);
        runtimeCoordinator.start();

        ContainerFactory containerFactory = mock(ContainerFactory.class);
        PermissionService permissionService = mock(PermissionService.class);
        LingLoaderFactory loaderFactory = mock(LingLoaderFactory.class);
        LingServiceRegistry serviceRegistry = mock(LingServiceRegistry.class);
        InvocationPipelineEngine pipelineEngine = mock(InvocationPipelineEngine.class);
        doThrow(new RuntimeException("governance reset failed"))
                .when(pipelineEngine).recoverLingGovernance("ling1");

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
        when(container.getClassLoader()).thenReturn(createSafeTestClassLoader());

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
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> engine.recover("ling1", "1.0.0"));
            assertEquals("governance reset failed", ex.getMessage());
            // 失败路径显式收口：runtime 不再卡在 RECOVERING 意图态（否则 MacroStateGuardFilter 永久拒绝流量）
            assertEquals(RuntimeStatus.DEGRADED, runtimeCoordinator.getStatus("ling1"));
            verify(pipelineEngine).recoverLingGovernance("ling1");
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
    @DisplayName("替换默认实例时应立即回收空闲的旧默认实例，不使其滞留濒死队列")
    void replacingDefaultInstanceShouldReclaimOldOneImmediately() throws Exception {
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

        // 旧默认 v1 无在途请求（idle），应被立即回收：先 moveToDying（非孤儿），随即 tearDown 到 DEAD
        assertEquals(InstanceStatus.DEAD, v1.currentStatus(),
                "被替换的空闲旧默认实例应被立即 tearDown 到 DEAD，而非滞留濒死队列累积引用");
        // 新默认应为 v2
        assertEquals(v2, runtime.getInstancePool().getDefault());
        // 濒死队列不应滞留空闲实例
        assertEquals(0, runtime.getInstancePool().getDyingCount(),
                "空闲实例应立即回收，濒死队列不应滞留");

        runtimeCoordinator.stop();
    }

    @Test
    @DisplayName("替换默认实例时被替换实例应走完整卸载钩子（onVersionUnload + detectLeak）")
    void reclaimedOldDefaultShouldRunFullUnloadHooks() throws Exception {
        EventBus eventBus = new EventBus();
        RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(eventBus);
        runtimeCoordinator.start();

        LingUnloadCoordinator unloadCoordinator = mock(LingUnloadCoordinator.class);
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
                .unloadCoordinator(unloadCoordinator)
                .runtimeCoordinator(runtimeCoordinator)
                .build());

        InstanceCoordinator coordinator = new InstanceCoordinator(eventBus);
        runtimeCoordinator.register("ling1");
        LingRuntime runtime = new LingRuntime(
                "ling1",
                LingRuntimeConfig.defaults(),
                eventBus,
                coordinator,
                runtimeCoordinator);

        // 用独立 ClassLoader 模拟灵元 CL（生产由 finalizeInstanceUnload 关闭）；
        // 不能用 getClass().getClassLoader()——surefire 的 URLClassLoader 被 close 后 forked VM 崩溃
        ClassLoader tracked = createSafeTestClassLoader();
        LingContainer oldContainer = mock(LingContainer.class);
        when(oldContainer.isActive()).thenReturn(true);
        when(oldContainer.getClassLoader()).thenReturn(tracked);
        LingDefinition oldDef = new LingDefinition();
        oldDef.setId("ling1");
        oldDef.setVersion("1.0.0");
        oldDef.setMainClass("demo.Main");
        LingInstance v1 = new LingInstance(oldContainer, oldDef, eventBus);
        coordinator.prepare(v1);
        coordinator.start(v1);
        coordinator.markReady(v1);
        runtime.getInstancePool().addInstance(v1, true);

        LingInstance v2 = createReadyInstance("ling1", "2.0.0", coordinator);
        invokePublishReady(engine, runtime, v2, true);

        // 完整卸载钩子被调用：onVersionUnload（负责关闭 LingClassLoader + 资源回收）+ detectLeak
        verify(unloadCoordinator).onVersionUnload(
                ArgumentMatchers.eq("ling1"), ArgumentMatchers.eq("1.0.0"), ArgumentMatchers.same(tracked));
        verify(unloadCoordinator).detectLeak(
                ArgumentMatchers.eq("ling1"), ArgumentMatchers.eq("1.0.0"), ArgumentMatchers.same(tracked));

        runtimeCoordinator.stop();
    }

    @Test
    @DisplayName("替换默认实例退役旧版本时，应版本级精确清理 provider（其他版本仍服务时不做全量 evict）")
    void replacingDefaultShouldEvictProviderByVersionOnly() throws Exception {
        EventBus eventBus = new EventBus();
        RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(eventBus);
        runtimeCoordinator.start();

        LingServiceRegistry serviceRegistry = mock(LingServiceRegistry.class);
        LingUnloadCoordinator unloadCoordinator = mock(LingUnloadCoordinator.class);
        DefaultLingLifecycleEngine engine = new DefaultLingLifecycleEngine(LifecycleEngineConfig.builder()
                .containerFactory(mock(ContainerFactory.class))
                .permissionService(mock(PermissionService.class))
                .lingLoaderFactory(mock(LingLoaderFactory.class))
                .verifiers(Collections.emptyList())
                .eventBus(eventBus)
                .lingFrameConfig(LingFrameConfig.builder().build())
                .lingRepository(new DefaultLingRepository())
                .lingServiceRegistry(serviceRegistry)
                .pipelineEngine(mock(InvocationPipelineEngine.class))
                .lingResourceManager(null)
                .unloadCoordinator(unloadCoordinator)
                .runtimeCoordinator(runtimeCoordinator)
                .build());

        InstanceCoordinator coordinator = new InstanceCoordinator(eventBus);
        runtimeCoordinator.register("ling1");
        LingRuntime runtime = new LingRuntime(
                "ling1", LingRuntimeConfig.defaults(), eventBus, coordinator, runtimeCoordinator);

        LingInstance v1 = createReadyInstance("ling1", "1.0.0", coordinator);
        runtime.getInstancePool().addInstance(v1, true);
        LingInstance v2 = createReadyInstance("ling1", "2.0.0", coordinator);
        invokePublishReady(engine, runtime, v2, true);

        // 旧版本 v1 退役时 v2 仍在服务 —— 只按版本精确清理 provider，不做全量 evict
        verify(serviceRegistry).evictProvider("ling1", "1.0.0");
        verify(serviceRegistry, never()).evictProvider("ling1");
        verify(serviceRegistry, never()).evict("ling1");

        runtimeCoordinator.stop();
    }

    @Test
    @DisplayName("全量卸载的 drain 窗口内 Runtime 应呈现 STOPPING 而非 ACTIVE（C3）")
    void fullUndeployDrainWindowShouldPresentRuntimeStopping() throws Exception {
        EventBus eventBus = new EventBus();
        RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(eventBus);
        runtimeCoordinator.start();

        LingServiceRegistry serviceRegistry = mock(LingServiceRegistry.class);
        when(serviceRegistry.getServicesByLingId("c3-ling")).thenReturn(Collections.emptyList());

        LingUnloadCoordinator unloadCoordinator = mock(LingUnloadCoordinator.class);
        when(unloadCoordinator.checkBeforeLingUnload(ArgumentMatchers.anyString(), ArgumentMatchers.anyList()))
                .thenReturn(Collections.emptyList());

        DefaultLingRepository repository = new DefaultLingRepository();
        DefaultLingLifecycleEngine engine = new DefaultLingLifecycleEngine(LifecycleEngineConfig.builder()
                .containerFactory(mock(ContainerFactory.class))
                .permissionService(mock(PermissionService.class))
                .lingLoaderFactory(mock(LingLoaderFactory.class))
                .verifiers(Collections.emptyList())
                .eventBus(eventBus)
                .lingFrameConfig(LingFrameConfig.builder()
                        .runtimeConfig(LingRuntimeConfig.builder().forceCleanupDelaySeconds(30).build())
                        .build())
                .lingRepository(repository)
                .lingServiceRegistry(serviceRegistry)
                .pipelineEngine(mock(InvocationPipelineEngine.class))
                .lingResourceManager(null)
                .unloadCoordinator(unloadCoordinator)
                .runtimeCoordinator(runtimeCoordinator)
                .build());

        InstanceCoordinator coordinator = new InstanceCoordinator(eventBus);
        // 不预先 markReady：实例状态事件必须在 register 之后出现（AGENTS.md 硬约束）。
        LingContainer container = mock(LingContainer.class);
        when(container.isActive()).thenReturn(true);
        when(container.getClassLoader()).thenReturn(createSafeTestClassLoader());
        LingDefinition lingDefinition = new LingDefinition();
        lingDefinition.setId("c3-ling");
        lingDefinition.setVersion("1.0.0");
        lingDefinition.setMainClass("demo.Main");
        LingInstance instance = new LingInstance(container, lingDefinition, eventBus);

        LingRuntime runtime = new LingRuntime(
                "c3-ling",
                LingRuntimeConfig.builder().forceCleanupDelaySeconds(30).build(),
                eventBus,
                coordinator,
                runtimeCoordinator);
        runtimeCoordinator.register("c3-ling");
        runtime.getInstancePool().addInstance(instance, true);
        repository.register(runtime);

        // 引导实例进入 READY，READY 事件触发 RuntimeCoordinator 聚合至 ACTIVE。
        coordinator.prepare(instance);
        coordinator.start(instance);
        coordinator.markReady(instance);
        awaitStatus(runtime, RuntimeStatus.ACTIVE);

        // 模拟在途请求：instance 非 idle，迫使 drain 阻塞等待。
        assertTrue(instance.tryEnter());

        AtomicReference<Throwable> unloadFailure = new AtomicReference<>();
        Thread unloadThread = new Thread(() -> {
            try {
                engine.undeployWithReport("c3-ling");
            } catch (Throwable t) {
                unloadFailure.set(t);
            }
        });
        unloadThread.start();

        try {
            // 等待 drain 窗口：moveToDying 已发生、实例 STOPPING，Runtime 宏观应进入 STOPPING（C3 收敛点）。
            awaitStatus(runtime, RuntimeStatus.STOPPING);
            assertEquals(RuntimeStatus.STOPPING, runtime.currentStatus(),
                    "drain 窗口内宏观状态应为 STOPPING，而非 ACTIVE");
            assertFalse(instance.isIdle(),
                    "drain 窗口内 in-flight 实例应仍处于非 idle，证明卸载确实被阻塞在排空阶段");

            // 结束 in-flight，唤醒 drain，卸载继续走完。
            instance.exit();
            unloadThread.join(15000);
            assertFalse(unloadThread.isAlive(), "全量卸载子线程应正常结束");
            assertNull(unloadFailure.get(), "全量卸载不应失败: " + unloadFailure.get());
        } finally {
            unloadThread.interrupt();
            runtimeCoordinator.stop();
        }
    }

    private void awaitStatus(LingRuntime runtime, RuntimeStatus expected) {
        for (int i = 0; i < 200 && runtime.currentStatus() != expected; i++) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void invokePublishReady(DefaultLingLifecycleEngine engine, LingRuntime runtime,
                                    LingInstance instance, boolean isDefault) throws Exception {
        java.lang.reflect.Method m = DefaultLingLifecycleEngine.class.getDeclaredMethod(
                "publishReadyInstance", LingRuntime.class, LingInstance.class, boolean.class);
        m.setAccessible(true);
        m.invoke(engine, runtime, instance, isDefault);
    }

    @Test
    @DisplayName("withLifecycleLock 同一 lingId 的并发操作应被串行化")
    void withLifecycleLock_sameLingId_shouldSerialize() throws Exception {
        EventBus eventBus = new EventBus();
        RuntimeCoordinator coordinator = new RuntimeCoordinator(eventBus);
        DefaultLingLifecycleEngine engine = createMinimalEngine(eventBus, coordinator);
        String lingId = "test-ling-lock";

        java.util.concurrent.CountDownLatch thread1Running = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch allowThread1Finish = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean thread2Executed = new java.util.concurrent.atomic.AtomicBoolean(false);

        Thread t1 = new Thread(() -> {
            engine.withLifecycleLock(lingId, () -> {
                thread1Running.countDown();
                try {
                    allowThread1Finish.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                return null;
            });
        });

        Thread t2 = new Thread(() -> {
            try {
                thread1Running.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            // 此时 t1 持有锁，t2 尝试获取锁应被阻塞
            engine.withLifecycleLock(lingId, () -> {
                thread2Executed.set(true);
                return null;
            });
        });

        t1.start();
        t2.start();

        assertTrue(thread1Running.await(5, java.util.concurrent.TimeUnit.SECONDS));
        // 给 t2 稍微一点时间尝试加锁
        Thread.sleep(50);
        // t1 还没结束，t2 一定还没执行
        assertFalse(thread2Executed.get(), "Thread 2 should be blocked by Thread 1");

        allowThread1Finish.countDown();
        t1.join(5000);
        t2.join(5000);

        assertTrue(thread2Executed.get(), "Thread 2 should have completed after Thread 1 released lock");
    }

    @Test
    @DisplayName("withLifecycleLock 不同 lingId 的操作应并行执行不阻塞")
    void withLifecycleLock_differentLingId_shouldNotBlock() throws Exception {
        EventBus eventBus = new EventBus();
        RuntimeCoordinator coordinator = new RuntimeCoordinator(eventBus);
        DefaultLingLifecycleEngine engine = createMinimalEngine(eventBus, coordinator);

        java.util.concurrent.CountDownLatch t1InLock = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseT1 = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean t2Finished = new java.util.concurrent.atomic.AtomicBoolean(false);

        Thread t1 = new Thread(() -> {
            engine.withLifecycleLock("ling-a", () -> {
                t1InLock.countDown();
                try {
                    releaseT1.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                return null;
            });
        });

        Thread t2 = new Thread(() -> {
            try {
                t1InLock.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            engine.withLifecycleLock("ling-b", () -> {
                t2Finished.set(true);
                return null;
            });
        });

        t1.start();
        t2.start();

        t2.join(3000);
        assertTrue(t2Finished.get(), "Thread 2 for ling-b should finish even if ling-a is locked");

        releaseT1.countDown();
        t1.join(3000);
    }

    @Test
    @DisplayName("withLifecycleLock 内部重入调用不应发生死锁")
    void withLifecycleLock_reentrant_shouldNotDeadlock() {
        EventBus eventBus = new EventBus();
        RuntimeCoordinator coordinator = new RuntimeCoordinator(eventBus);
        DefaultLingLifecycleEngine engine = createMinimalEngine(eventBus, coordinator);
        String lingId = "reentrant-ling";

        String result = engine.withLifecycleLock(lingId, () -> {
            // 外层持锁，内层再次 withLifecycleLock
            return engine.withLifecycleLock(lingId, () -> "success");
        });

        assertEquals("success", result);
    }

    @Test
    @DisplayName("withLifecycleLock 抛出异常后应正确释放锁")
    void withLifecycleLock_onException_shouldReleaseLock() {
        EventBus eventBus = new EventBus();
        RuntimeCoordinator coordinator = new RuntimeCoordinator(eventBus);
        DefaultLingLifecycleEngine engine = createMinimalEngine(eventBus, coordinator);
        String lingId = "exception-ling";

        assertThrows(RuntimeException.class, () -> {
            engine.withLifecycleLock(lingId, () -> {
                throw new IllegalStateException("simulated failure");
            });
        });

        // 验证后续调用可以正常加锁，未产生锁泄漏
        assertDoesNotThrow(() -> {
            engine.withLifecycleLock(lingId, () -> "recovered");
        });
    }

    @Test
    @DisplayName("withLifecycleLock 获取锁超时应抛出 IllegalStateException")
    void withLifecycleLock_timeout_shouldThrow() throws Exception {
        EventBus eventBus = new EventBus();
        RuntimeCoordinator coordinator = new RuntimeCoordinator(eventBus);
        ContainerFactory containerFactory = mock(ContainerFactory.class);
        PermissionService permissionService = mock(PermissionService.class);
        LingLoaderFactory loaderFactory = mock(LingLoaderFactory.class);
        LingServiceRegistry serviceRegistry = mock(LingServiceRegistry.class);

        // 设置极短的锁超时 50ms
        DefaultLingLifecycleEngine shortTimeoutEngine = new DefaultLingLifecycleEngine(LifecycleEngineConfig.builder()
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
                .runtimeCoordinator(coordinator)
                .lifecycleLockTimeoutMs(50L)
                .build());

        String lingId = "timeout-ling";
        java.util.concurrent.CountDownLatch t1Holding = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseT1 = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> t2Error = new java.util.concurrent.atomic.AtomicReference<>();

        Thread t1 = new Thread(() -> {
            shortTimeoutEngine.withLifecycleLock(lingId, () -> {
                t1Holding.countDown();
                try {
                    releaseT1.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                return null;
            });
        });

        Thread t2 = new Thread(() -> {
            try {
                t1Holding.await(5, java.util.concurrent.TimeUnit.SECONDS);
                shortTimeoutEngine.withLifecycleLock(lingId, () -> null);
            } catch (Throwable t) {
                t2Error.set(t);
            }
        });

        t1.start();
        t2.start();

        t2.join(3000);
        releaseT1.countDown();
        t1.join(3000);

        assertNotNull(t2Error.get());
        assertInstanceOf(IllegalStateException.class, t2Error.get());
        assertTrue(t2Error.get().getMessage().contains("Acquire lifecycle lock timeout"));
    }

    @Test
    @DisplayName("安装失败回滚应透传 lingId/version 触发身份版失败清理（孤儿资源随回滚释放）")
    void installFailureShouldPassthroughIdentityToFailureCleanup() {
        EventBus eventBus = new EventBus();
        RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(eventBus);
        runtimeCoordinator.start();

        LingUnloadCoordinator unloadCoordinator = mock(LingUnloadCoordinator.class);
        ContainerFactory containerFactory = mock(ContainerFactory.class);
        LingLoaderFactory loaderFactory = mock(LingLoaderFactory.class);
        // 用独立 ClassLoader 模拟灵元 CL（生产由 cleanupOnFailure 关闭）
        ClassLoader tracked = createSafeTestClassLoader();
        when(loaderFactory.create(anyString(), any(), any())).thenReturn(tracked);

        LingContainer container = mock(LingContainer.class);
        when(container.getClassLoader()).thenReturn(tracked);
        when(containerFactory.create(any(), any(), any())).thenReturn(container);
        // 模拟 onStart 失败：容器启动抛异常，回滚路径被触发
        doThrow(new IllegalStateException("simulated onStart failure")).when(container).start(any());

        DefaultLingLifecycleEngine engine = new DefaultLingLifecycleEngine(LifecycleEngineConfig.builder()
                .containerFactory(containerFactory)
                .permissionService(mock(PermissionService.class))
                .lingLoaderFactory(loaderFactory)
                .verifiers(Collections.emptyList())
                .eventBus(eventBus)
                .lingFrameConfig(LingFrameConfig.builder().build())
                .lingRepository(new DefaultLingRepository())
                .lingServiceRegistry(mock(LingServiceRegistry.class))
                .pipelineEngine(mock(InvocationPipelineEngine.class))
                .lingResourceManager(null)
                .unloadCoordinator(unloadCoordinator)
                .runtimeCoordinator(runtimeCoordinator)
                .build());

        LingDefinition definition = new LingDefinition();
        definition.setId("ling-fail");
        definition.setVersion("1.0.0");
        definition.setMainClass("demo.Main");

        assertThrows(RuntimeException.class, () -> engine.deploy(definition, null, true, null));

        // 身份透传验证：onStart 内已注册的孤儿资源才能随回滚按 (lingId, version) 释放
        verify(unloadCoordinator).onFailureCleanup("ling-fail", "1.0.0", tracked);
        // 旧的无身份重载不再被编排层使用
        verify(unloadCoordinator, never()).onFailureCleanup(any(ClassLoader.class));

        runtimeCoordinator.stop();
    }

    private ClassLoader createSafeTestClassLoader() {
        return new ClassLoader(getClass().getClassLoader()) {};
    }

    private LingInstance createReadyInstance(String lingId, String version, InstanceCoordinator coordinator) {
        LingContainer container = mock(LingContainer.class);
        when(container.isActive()).thenReturn(true);
        when(container.getClassLoader()).thenReturn(createSafeTestClassLoader());

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
