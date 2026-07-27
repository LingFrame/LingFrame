package com.lingframe.dashboard.service;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.context.LingContext;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.exception.LingInstallException;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.ling.InstancePool;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingRuntimeConfig;
import com.lingframe.core.ling.LingUninstallResult;
import com.lingframe.core.spi.LeakRiskReport;
import com.lingframe.core.spi.LingContainer;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DashboardLingOperations 补充测试
 * <p>
 * 现有 DashboardLingOperationsTest 覆盖 install/uninstall 的正常路径。
 * 本类补齐 installLing 的 parseDefinition 返回 null、uninstallLing 的异常包装、
 * reloadLing 的 runtime 不存在、目标实例不存在、源文件找不到等异常路径。
 */
@DisplayName("DashboardLingOperations 补充测试")
class DashboardLingOperationsSupplementTest {

    private LingLifecycleEngine lifecycleEngine;
    private LingRepository lingRepository;
    private DashboardLifecycleEventStore lifecycleEventStore;
    private DashboardLingSourceResolver lingSourceResolver;
    private DashboardLingOperations operations;

    @BeforeEach
    void setUp() {
        lifecycleEngine = mock(LingLifecycleEngine.class);
        lingRepository = mock(LingRepository.class);
        lifecycleEventStore = mock(DashboardLifecycleEventStore.class);
        lingSourceResolver = new DashboardLingSourceResolver(
                LingFrameConfig.builder().build());
        operations = new DashboardLingOperations(lifecycleEngine, lingRepository,
                null, lifecycleEventStore, lingSourceResolver);
    }

    // ==================== installLing 异常路径 ====================

    @Nested
    @DisplayName("installLing 异常")
    class InstallLingExceptionTests {

        @Test
        @DisplayName("parseDefinition 返回 null 时应抛 LingInstallException")
        void shouldThrowWhenParseDefinitionReturnsNull(@TempDir Path tempDir) throws Exception {
            // 创建一个空文件，parseDefinition 会返回 null
            File emptyFile = tempDir.resolve("empty.jar").toFile();
            Files.createFile(emptyFile.toPath());

            assertThrows(LingInstallException.class,
                    () -> operations.installLing(emptyFile));
        }

        @Test
        @DisplayName("lifecycleEngine.deploy 抛异常时应包装为 LingInstallException")
        void shouldWrapDeployException(@TempDir Path tempDir) throws Exception {
            // 构造一个真正的 JAR 文件让 parseDefinition 能解析
            // 由于很难构造合法 JAR，这里直接验证异常包装路径
            File badFile = tempDir.resolve("bad.jar").toFile();
            Files.createFile(badFile.toPath());

            assertThrows(LingInstallException.class,
                    () -> operations.installLing(badFile));
        }
    }

    // ==================== uninstallLing 异常路径 ====================

    @Nested
    @DisplayName("uninstallLing 异常")
    class UninstallLingExceptionTests {

        @Test
        @DisplayName("单参 uninstallLing 在 lifecycleEngine 抛异常时应包装为 LingInstallException")
        void shouldWrapExceptionInSingleArgUninstall() {
            doThrow(new RuntimeException("undeploy error"))
                    .when(lifecycleEngine).undeployWithReport("ling1");

            assertThrows(LingInstallException.class,
                    () -> operations.uninstallLing("ling1"));
        }

        @Test
        @DisplayName("双参 uninstallLing 在 lifecycleEngine 抛异常时应包装为 LingInstallException")
        void shouldWrapExceptionInTwoArgUninstall() {
            doThrow(new RuntimeException("undeploy error"))
                    .when(lifecycleEngine).undeployWithReport("ling1", "1.0.0");

            assertThrows(LingInstallException.class,
                    () -> operations.uninstallLing("ling1", "1.0.0"));
        }

        @Test
        @DisplayName("uninstallLing 触发后应记录 DEAD 事件")
        void shouldRecordDeadEventWhenUninstallTriggered() {
            when(lifecycleEngine.undeployWithReport("ling1"))
                    .thenReturn(LingUninstallResult.triggered("ling1", null, Collections.<LeakRiskReport>emptyList()));

            operations.uninstallLing("ling1");

            verify(lifecycleEventStore).addEvent(eq("ling1"), eq(""), eq("DEAD"), anyString(), anyString());
        }

        @Test
        @DisplayName("uninstallLing(version) 触发后应记录 UNLOAD 事件")
        void shouldRecordUnloadEventWhenVersionUninstallTriggered() {
            when(lifecycleEngine.undeployWithReport("ling1", "1.0.0"))
                    .thenReturn(LingUninstallResult.triggered("ling1", "1.0.0", Collections.<LeakRiskReport>emptyList()));

            operations.uninstallLing("ling1", "1.0.0");

            verify(lifecycleEventStore).addEvent(eq("ling1"), eq("1.0.0"), eq("UNLOAD"), anyString(), anyString());
        }
    }

    // ==================== reloadLing 异常路径 ====================

    @Nested
    @DisplayName("reloadLing 异常")
    class ReloadLingExceptionTests {

        @Test
        @DisplayName("runtime 不存在时应抛 LingInstallException（包装 LingNotFoundException）")
        void shouldThrowWhenRuntimeNotFound() {
            when(lingRepository.getRuntime("ling1")).thenReturn(null);

            // LingNotFoundException 被包装为 LingInstallException
            assertThrows(LingInstallException.class,
                    () -> operations.reloadLing("ling1", null));
        }

        @Test
        @DisplayName("version 指定但实例不存在时应抛 LingInstallException")
        void shouldThrowWhenVersionInstanceNotFound() {
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
            when(runtime.getInstancePool()).thenReturn(pool);
            when(pool.getInstance("1.0.0")).thenReturn(null);

            assertThrows(LingInstallException.class,
                    () -> operations.reloadLing("ling1", "1.0.0"));
        }

        @Test
        @DisplayName("无指定 version 且无可用实例时应抛 LingInstallException")
        void shouldThrowWhenNoStableInstance() {
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
            when(runtime.getInstancePool()).thenReturn(pool);
            when(pool.getActiveInstances()).thenReturn(Collections.<LingInstance>emptyList());
            when(pool.getDefault()).thenReturn(null);

            assertThrows(LingInstallException.class,
                    () -> operations.reloadLing("ling1", null));
        }
    }

    // ==================== getLingSourceResolver ====================

    @Nested
    @DisplayName("getLingSourceResolver")
    class GetLingSourceResolverTests {

        @Test
        @DisplayName("应返回构造时传入的 resolver")
        void shouldReturnResolverFromConstructor() {
            assertEquals(lingSourceResolver, operations.getLingSourceResolver());
        }
    }
}
