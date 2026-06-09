package com.lingframe.core.ling;

import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.spi.LeakDetector;
import com.lingframe.core.spi.LeakRiskReport;
import com.lingframe.core.spi.LeakRiskLevel;
import com.lingframe.core.spi.ResourceGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * LingUnloadCoordinator 测试。
 * 覆盖：版本卸载、整Ling卸载、泄漏预检、失败回滚、异常容错。
 */
@DisplayName("LingUnloadCoordinator 测试")
class LingUnloadCoordinatorTest {

    private InvocationPipelineEngine pipelineEngine;
    private ResourceGuard resourceGuard;
    private LingResourceManager resourceManager;
    private LeakDetector leakDetector;
    private LingUnloadCoordinator coordinator;

    @BeforeEach
    void setUp() {
        pipelineEngine = mock(InvocationPipelineEngine.class);
        resourceGuard = mock(ResourceGuard.class);
        resourceManager = mock(LingResourceManager.class);
        leakDetector = mock(LeakDetector.class);
        coordinator = new LingUnloadCoordinator(
                pipelineEngine,
                Collections.singletonList(resourceGuard),
                resourceManager,
                leakDetector);
    }

    // ==================== 版本级卸载 ====================

    @Nested
    @DisplayName("版本级卸载 onVersionUnload")
    class VersionUnload {

        @Test
        @DisplayName("调用 ResourceGuard.cleanup 清理指定 ClassLoader")
        void callsResourceGuardCleanup() {
            ClassLoader cl = mock(ClassLoader.class);
            coordinator.onVersionUnload("ling-1", "v1", cl);

            verify(resourceGuard).cleanup("ling-1", cl);
        }

        @Test
        @DisplayName("调用 LingResourceManager.cleanupCaches")
        void callsResourceManagerCleanupCaches() {
            ClassLoader cl = mock(ClassLoader.class);
            coordinator.onVersionUnload("ling-1", "v1", cl);

            verify(resourceManager).cleanupCaches("ling-1", cl);
        }

        @Test
        @DisplayName("ClassLoader 为 null 时不执行清理")
        void nullClassLoaderSkipsCleanup() {
            coordinator.onVersionUnload("ling-1", "v1", null);

            verifyNoInteractions(resourceGuard);
            verifyNoInteractions(resourceManager);
        }

        @Test
        @DisplayName("ResourceGuard 抛异常不影响后续 Guard")
        void guardExceptionDoesNotBlockOthers() {
            ResourceGuard failingGuard = mock(ResourceGuard.class);
            ResourceGuard normalGuard = mock(ResourceGuard.class);
            doThrow(new RuntimeException("test error")).when(failingGuard).cleanup(any(), any());

            LingUnloadCoordinator coord = new LingUnloadCoordinator(
                    pipelineEngine,
                    Arrays.asList(failingGuard, normalGuard),
                    resourceManager,
                    leakDetector);

            ClassLoader cl = mock(ClassLoader.class);
            coord.onVersionUnload("ling-1", "v1", cl);

            verify(failingGuard).cleanup("ling-1", cl);
            verify(normalGuard).cleanup("ling-1", cl);
        }
    }

    // ==================== 整 Ling 卸载 ====================

    @Nested
    @DisplayName("整 Ling 卸载 onLingUnload")
    class LingUnload {

        @Test
        @DisplayName("驱逐 Pipeline 资源和方法缓存")
        void evictsPipelineResources() {
            when(pipelineEngine.evictMethodCache("ling-1")).thenReturn(5);

            coordinator.onLingUnload("ling-1");

            verify(pipelineEngine).evictLingResources("ling-1");
            verify(pipelineEngine).evictMethodCache("ling-1");
            verify(resourceManager).closeResources("ling-1");
        }

        @Test
        @DisplayName("pipelineEngine 为 null 时不抛异常")
        void nullPipelineEngineSafe() {
            LingUnloadCoordinator coord = new LingUnloadCoordinator(
                    null, Collections.emptyList(), resourceManager, leakDetector);
            assertDoesNotThrow(() -> coord.onLingUnload("ling-1"));
        }

        @Test
        @DisplayName("resourceManager 为 null 时不抛异常")
        void nullResourceManagerSafe() {
            LingUnloadCoordinator coord = new LingUnloadCoordinator(
                    pipelineEngine, Collections.emptyList(), null, leakDetector);
            assertDoesNotThrow(() -> coord.onLingUnload("ling-1"));
        }
    }

    // ==================== 泄漏预检 ====================

    @Nested
    @DisplayName("泄漏预检 checkBeforeVersionUnload")
    class LeakPrecheck {

        @Test
        @DisplayName("正常预检返回检测结果")
        void normalPrecheck() {
            ClassLoader cl = mock(ClassLoader.class);
            LeakRiskReport report = LeakRiskReport.noRisk("ling-1", "v1", "OK", null, "TestDetector");
            when(leakDetector.checkBefore("ling-1", "v1", cl)).thenReturn(report);

            LeakRiskReport result = coordinator.checkBeforeVersionUnload("ling-1", "v1", cl);

            assertEquals(LeakRiskLevel.NO_RISK, result.getLevel());
        }

        @Test
        @DisplayName("ClassLoader 为 null 返回 CHECK_FAILED")
        void nullClassLoaderReturnsCheckFailed() {
            LeakRiskReport result = coordinator.checkBeforeVersionUnload("ling-1", "v1", null);

            assertEquals(LeakRiskLevel.CHECK_FAILED, result.getLevel());
        }

        @Test
        @DisplayName("LeakDetector 为 null 返回 CHECK_FAILED")
        void nullLeakDetectorReturnsCheckFailed() {
            LingUnloadCoordinator coord = new LingUnloadCoordinator(
                    pipelineEngine, Collections.emptyList(), resourceManager, null);

            ClassLoader cl = mock(ClassLoader.class);
            LeakRiskReport result = coord.checkBeforeVersionUnload("ling-1", "v1", cl);

            assertEquals(LeakRiskLevel.CHECK_FAILED, result.getLevel());
        }

        @Test
        @DisplayName("LeakDetector 抛异常返回 CHECK_FAILED")
        void leakDetectorExceptionReturnsCheckFailed() {
            ClassLoader cl = mock(ClassLoader.class);
            when(leakDetector.checkBefore("ling-1", "v1", cl))
                    .thenThrow(new RuntimeException("detector error"));

            LeakRiskReport result = coordinator.checkBeforeVersionUnload("ling-1", "v1", cl);

            assertEquals(LeakRiskLevel.CHECK_FAILED, result.getLevel());
            assertTrue(result.getSummary().contains("detector error"));
        }
    }

    // ==================== 整 Ling 泄漏预检 ====================

    @Nested
    @DisplayName("整 Ling 泄漏预检 checkBeforeLingUnload")
    class LingLeakPrecheck {

        @Test
        @DisplayName("空实例列表返回空报告")
        void emptyInstancesReturnsEmpty() {
            List<LeakRiskReport> reports = coordinator.checkBeforeLingUnload("ling-1", Collections.emptyList());
            assertTrue(reports.isEmpty());
        }

        @Test
        @DisplayName("null 实例列表返回空报告")
        void nullInstancesReturnsEmpty() {
            List<LeakRiskReport> reports = coordinator.checkBeforeLingUnload("ling-1", null);
            assertTrue(reports.isEmpty());
        }
    }

    // ==================== 失败回滚 ====================

    @Nested
    @DisplayName("失败回滚 onFailureCleanup")
    class FailureCleanup {

        @Test
        @DisplayName("安装失败时调用 ResourceGuard 清理")
        void failureCleanupCallsGuards() {
            ClassLoader cl = mock(ClassLoader.class);
            coordinator.onFailureCleanup(cl);

            verify(resourceGuard).cleanup("fault-cleanup", cl);
        }

        @Test
        @DisplayName("null ClassLoader 不执行清理")
        void nullClassLoaderSkipsCleanup() {
            coordinator.onFailureCleanup(null);
            verifyNoInteractions(resourceGuard);
        }
    }

    // ==================== 卸载后泄漏检测 ====================

    @Nested
    @DisplayName("卸载后泄漏检测 detectLeak")
    class DetectLeak {

        @Test
        @DisplayName("正常调用 LeakDetector.detectLeak")
        void callsDetectLeak() {
            ClassLoader cl = mock(ClassLoader.class);
            coordinator.detectLeak("ling-1", "v1", cl);

            verify(leakDetector).detectLeak("ling-1", "v1", cl);
        }

        @Test
        @DisplayName("null ClassLoader 不调用检测")
        void nullClassLoaderSkipsDetection() {
            coordinator.detectLeak("ling-1", "v1", null);
            verifyNoInteractions(leakDetector);
        }

        @Test
        @DisplayName("null LeakDetector 不调用检测")
        void nullLeakDetectorSkipsDetection() {
            LingUnloadCoordinator coord = new LingUnloadCoordinator(
                    pipelineEngine, Collections.emptyList(), resourceManager, null);

            ClassLoader cl = mock(ClassLoader.class);
            coord.detectLeak("ling-1", "v1", cl);
            // 不抛异常即可
        }

        @Test
        @DisplayName("LeakDetector 抛异常不传播")
        void leakDetectorExceptionSwallowed() {
            ClassLoader cl = mock(ClassLoader.class);
            doThrow(new RuntimeException("detection error")).when(leakDetector).detectLeak(any(), any(), any());

            assertDoesNotThrow(() -> coordinator.detectLeak("ling-1", "v1", cl));
        }
    }

    // ==================== getLeakDetector ====================

    @Test
    @DisplayName("getLeakDetector 返回注入的检测器")
    void getLeakDetectorReturnsInjected() {
        assertSame(leakDetector, coordinator.getLeakDetector());
    }
}
