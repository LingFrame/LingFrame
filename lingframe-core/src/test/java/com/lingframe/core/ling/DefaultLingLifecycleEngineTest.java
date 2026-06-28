package com.lingframe.core.ling;

import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.dev.HotSwapWatcher;
import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.spi.CanaryConfigurable;
import com.lingframe.core.spi.ContainerFactory;
import com.lingframe.core.spi.LeakRiskLevel;
import com.lingframe.core.spi.LeakRiskReport;
import com.lingframe.core.spi.LingContainer;
import com.lingframe.core.spi.LingLoaderFactory;
import com.lingframe.core.alert.AlertManager;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.metrics.MetricsCollector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
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
        DefaultLingLifecycleEngine engineWithRepository = new DefaultLingLifecycleEngine(
                containerFactory,
                permissionService,
                loaderFactory,
                Collections.emptyList(),
                eventBus,
                LingFrameConfig.builder()
                        .runtimeConfig(LingRuntimeConfig.builder().forceCleanupDelaySeconds(0).build())
                        .build(),
                repository,
                serviceRegistry,
                null,
                null,
                unloadCoordinator,
                runtimeCoordinator);

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
        DefaultLingLifecycleEngine engine = new DefaultLingLifecycleEngine(
                containerFactory,
                permissionService,
                loaderFactory,
                Collections.emptyList(),
                eventBus,
                LingFrameConfig.builder().build(),
                repository,
                serviceRegistry,
                pipelineEngine,
                null,
                mock(LingUnloadCoordinator.class),
                runtimeCoordinator);

        LingContainer container = mock(LingContainer.class);
        when(container.isActive()).thenReturn(true);
        when(container.getClassLoader()).thenReturn(getClass().getClassLoader());

        LingDefinition definition = new LingDefinition();
        definition.setId("ling1");
        definition.setVersion("1.0.0");
        definition.setMainClass("demo.Main");

        LingInstance instance = new LingInstance(container, definition, eventBus);
        InstanceCoordinator coordinator = new InstanceCoordinator(eventBus);
        coordinator.prepare(instance);
        coordinator.start(instance);
        coordinator.error(instance);

        LingRuntime runtime = new LingRuntime("ling1", LingRuntimeConfig.defaults(), eventBus, runtimeCoordinator);
        runtime.getInstancePool().addInstance(instance, true);
        repository.register(runtime);

        try {
            engine.recover("ling1", "1.0.0");

            assertEquals(com.lingframe.core.fsm.InstanceStatus.READY, instance.currentStatus());
            assertEquals(com.lingframe.core.fsm.RuntimeStatus.ACTIVE, runtime.currentStatus());
            verify(pipelineEngine).recoverLingGovernance("ling1");
            verify(container).start(org.mockito.ArgumentMatchers.any());
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
        DefaultLingLifecycleEngine engine = new DefaultLingLifecycleEngine(
                containerFactory,
                permissionService,
                loaderFactory,
                Collections.emptyList(),
                eventBus,
                LingFrameConfig.builder().build(),
                repository,
                serviceRegistry,
                pipelineEngine,
                null,
                mock(LingUnloadCoordinator.class),
                runtimeCoordinator);

        assertSame(repository, engine.getRepository());
        assertSame(serviceRegistry, engine.getServiceRegistry());
        assertSame(pipelineEngine, engine.getPipelineEngine());
        assertSame(eventBus, engine.getEventBus());

        runtimeCoordinator.stop();
    }

    @Test
    @DisplayName("setMetricsCollector 和 getter 正常工作")
    void shouldSetAndGetMetricsCollector() {
        EventBus eventBus = new EventBus();
        RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(eventBus);
        runtimeCoordinator.start();

        DefaultLingLifecycleEngine engine = createMinimalEngine(eventBus, runtimeCoordinator);

        // 微内核解耦：构造器不再自动创建 MetricsCollector，需外部注入
        assertFalse(engine.getMetricsCollector().isPresent());
        MetricsCollector metricsCollector = mock(MetricsCollector.class);
        engine.setMetricsCollector(metricsCollector);
        assertTrue(engine.getMetricsCollector().isPresent());
        assertSame(metricsCollector, engine.getMetricsCollector().get());

        runtimeCoordinator.stop();
    }

    @Test
    @DisplayName("setGovernanceMetricsCollector 和 getter 正常工作")
    void shouldSetAndGetGovernanceMetricsCollector() {
        EventBus eventBus = new EventBus();
        RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(eventBus);
        runtimeCoordinator.start();

        DefaultLingLifecycleEngine engine = createMinimalEngine(eventBus, runtimeCoordinator);

        // 微内核解耦：构造器不再自动创建，需外部注入
        assertFalse(engine.getGovernanceMetricsCollector().isPresent());
        GovernanceMetricsCollector gmc = mock(GovernanceMetricsCollector.class);
        engine.setGovernanceMetricsCollector(gmc);
        assertTrue(engine.getGovernanceMetricsCollector().isPresent());
        assertSame(gmc, engine.getGovernanceMetricsCollector().get());

        runtimeCoordinator.stop();
    }

    @Test
    @DisplayName("setAlertManager 和 getter 正常工作")
    void shouldSetAndGetAlertManager() {
        EventBus eventBus = new EventBus();
        RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(eventBus);
        runtimeCoordinator.start();

        DefaultLingLifecycleEngine engine = createMinimalEngine(eventBus, runtimeCoordinator);

        // 微内核解耦：构造器不再自动创建，需外部注入
        assertFalse(engine.getAlertManager().isPresent());
        AlertManager alertManager = mock(AlertManager.class);
        engine.setAlertManager(alertManager);
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
    @DisplayName("setHotSwapWatcher 和 getCanaryConfigurable 正常工作")
    void shouldSetHotSwapWatcherAndCanaryConfigurable() {
        EventBus eventBus = new EventBus();
        RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(eventBus);
        runtimeCoordinator.start();

        DefaultLingLifecycleEngine engine = createMinimalEngine(eventBus, runtimeCoordinator);

        HotSwapWatcher watcher = mock(HotSwapWatcher.class);
        engine.setHotSwapWatcher(watcher);

        CanaryConfigurable canary = mock(CanaryConfigurable.class);
        engine.setCanaryConfigurable(canary);
        assertTrue(engine.getCanaryConfigurable().isPresent());

        runtimeCoordinator.stop();
    }

    private DefaultLingLifecycleEngine createMinimalEngine(EventBus eventBus, RuntimeCoordinator runtimeCoordinator) {
        ContainerFactory containerFactory = mock(ContainerFactory.class);
        PermissionService permissionService = mock(PermissionService.class);
        LingLoaderFactory loaderFactory = mock(LingLoaderFactory.class);
        LingServiceRegistry serviceRegistry = mock(LingServiceRegistry.class);

        return new DefaultLingLifecycleEngine(
                containerFactory,
                permissionService,
                loaderFactory,
                Collections.emptyList(),
                eventBus,
                LingFrameConfig.builder().build(),
                new DefaultLingRepository(),
                serviceRegistry,
                null,
                null,
                mock(LingUnloadCoordinator.class),
                runtimeCoordinator);
    }
}
