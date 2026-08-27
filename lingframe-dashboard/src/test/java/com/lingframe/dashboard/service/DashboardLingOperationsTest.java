package com.lingframe.dashboard.service;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.exception.LingInstallException;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.InstancePool;
import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingUninstallResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DashboardLingOperations 测试")
class DashboardLingOperationsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("卸载灵元时应移除灰度配置并写入时间线")
    void shouldRemoveCanaryConfigAndRecordTimelineWhenUninstallingLing() {
        LingLifecycleEngine lifecycleEngine = mock(LingLifecycleEngine.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        LingRepository lingRepository = mock(LingRepository.class);
        DashboardLifecycleEventStore eventStore = new DashboardLifecycleEventStore();
        DashboardLingSourceResolver sourceResolver = mock(DashboardLingSourceResolver.class);
        DashboardLingOperations operations = new DashboardLingOperations(
                lifecycleEngine, lingRepository, null, eventStore, sourceResolver);

        LingUninstallResult result = LingUninstallResult.triggered("ling1", null, Collections.emptyList());
        when(lifecycleEngine.undeployWithReport("ling1")).thenReturn(result);

        LingUninstallResult actual = operations.uninstallLing("ling1");

        assertEquals(result, actual);
        assertEquals(1, eventStore.getEvents("ling1").size());
        assertEquals("DEAD", eventStore.getEvents("ling1").get(0).getType());
    }

    @Test
    @DisplayName("热重载时应部署新版本、卸载旧版本并写入时间线")
    void shouldReloadLingThroughSourceResolverAndLifecycleEngine() {
        LingLifecycleEngine lifecycleEngine = mock(LingLifecycleEngine.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        LingRepository lingRepository = mock(LingRepository.class);
        DashboardLifecycleEventStore eventStore = new DashboardLifecycleEventStore();
        DashboardLingSourceResolver sourceResolver = mock(DashboardLingSourceResolver.class);
        DashboardLingOperations operations = new DashboardLingOperations(
                lifecycleEngine, lingRepository, null, eventStore, sourceResolver);

        LingRuntime runtime = mock(LingRuntime.class);
        InstancePool instancePool = mock(InstancePool.class);
        LingInstance target = mock(LingInstance.class);
        LingInstance reloaded = mock(LingInstance.class);

        when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
        when(runtime.getInstancePool()).thenReturn(instancePool);
        when(sourceResolver.selectStableInstance(runtime)).thenReturn(target);
        when(target.getVersion()).thenReturn("1.0.0");
        when(target.getLabels()).thenReturn(new HashMap<String, String>());

        File source = createLingDirectory("ling1", "1.0.0");
        when(sourceResolver.resolveSourceFile("ling1", "1.0.0")).thenReturn(source);
        when(sourceResolver.buildReloadVersion(runtime, "1.0.0")).thenReturn("1.0.0-reload-1");
        doNothing().when(sourceResolver).markReload(any(LingDefinition.class), any(HashMap.class), eq("1.0.0-reload-1"));

        when(instancePool.getDefault()).thenReturn(target);
        when(instancePool.getInstance("1.0.0-reload-1")).thenReturn(reloaded);
        when(instancePool.getAllInstances()).thenReturn(Collections.singletonList(target));

        String lingId = operations.reloadLing("ling1", null);

        assertEquals("ling1", lingId);
        verify(lifecycleEngine).deployForReload(any(LingDefinition.class), same(source), eq(true), any(HashMap.class));
        verify(lifecycleEngine).undeploy("ling1", target);
        assertEquals(1, eventStore.getEvents("ling1").size());
        assertEquals("RELOAD", eventStore.getEvents("ling1").get(0).getType());
    }

    @Test
    @DisplayName("默认实例重载后已被替换流程回收时不再重复卸载旧实例")
    void shouldSkipUninstallWhenOldDefaultAlreadyReclaimed() {
        LingLifecycleEngine lifecycleEngine = mock(LingLifecycleEngine.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        LingRepository lingRepository = mock(LingRepository.class);
        DashboardLifecycleEventStore eventStore = new DashboardLifecycleEventStore();
        DashboardLingSourceResolver sourceResolver = mock(DashboardLingSourceResolver.class);
        DashboardLingOperations operations = new DashboardLingOperations(
                lifecycleEngine, lingRepository, null, eventStore, sourceResolver);

        LingRuntime runtime = mock(LingRuntime.class);
        InstancePool instancePool = mock(InstancePool.class);
        LingInstance target = mock(LingInstance.class);
        LingInstance reloaded = mock(LingInstance.class);

        when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
        when(runtime.getInstancePool()).thenReturn(instancePool);
        when(sourceResolver.selectStableInstance(runtime)).thenReturn(target);
        when(target.getVersion()).thenReturn("1.0.0");
        when(target.getLabels()).thenReturn(new HashMap<String, String>());

        File source = createLingDirectory("ling1", "1.0.0");
        when(sourceResolver.resolveSourceFile("ling1", "1.0.0")).thenReturn(source);
        when(sourceResolver.buildReloadVersion(runtime, "1.0.0")).thenReturn("1.0.0-reload-1");
        doNothing().when(sourceResolver).markReload(any(LingDefinition.class), any(HashMap.class), eq("1.0.0-reload-1"));

        when(instancePool.getDefault()).thenReturn(target);
        when(instancePool.getInstance("1.0.0-reload-1")).thenReturn(reloaded);
        // 模拟 deployForReload 即时回收：旧默认实例已从实例池移除（被替换流程清理）
        when(instancePool.getAllInstances()).thenReturn(Collections.emptyList());

        String lingId = operations.reloadLing("ling1", null);

        assertEquals("ling1", lingId);
        verify(lifecycleEngine).deployForReload(any(LingDefinition.class), same(source), eq(true), any(HashMap.class));
        verify(lifecycleEngine, never()).undeploy("ling1", target);
        verify(lifecycleEngine, never()).undeploy("ling1", "1.0.0");
    }

    @Test
    @DisplayName("当源码无法定位时热重载应失败")
    void shouldFailReloadWhenSourceCannotBeResolved() {
        LingLifecycleEngine lifecycleEngine = mock(LingLifecycleEngine.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        LingRepository lingRepository = mock(LingRepository.class);
        DashboardLifecycleEventStore eventStore = new DashboardLifecycleEventStore();
        DashboardLingSourceResolver sourceResolver = mock(DashboardLingSourceResolver.class);
        DashboardLingOperations operations = new DashboardLingOperations(
                lifecycleEngine, lingRepository, null, eventStore, sourceResolver);

        LingRuntime runtime = mock(LingRuntime.class);
        InstancePool instancePool = mock(InstancePool.class);
        LingInstance target = mock(LingInstance.class);
        when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
        when(runtime.getInstancePool()).thenReturn(instancePool);
        when(sourceResolver.selectStableInstance(runtime)).thenReturn(target);
        when(target.getVersion()).thenReturn("1.0.0");
        when(sourceResolver.resolveSourceFile("ling1", "1.0.0")).thenReturn(null);

        assertThrows(LingInstallException.class, () -> operations.reloadLing("ling1", null));
    }

    private File createLingDirectory(String lingId, String version) {
        try {
            Path dir = Files.createDirectory(tempDir.resolve(lingId + "-" + version));
            String yaml = ""
                    + "id: " + lingId + "\n"
                    + "version: " + version + "\n"
                    + "mainClass: \"demo.Main\"\n";
            Files.write(dir.resolve("ling.yml"), yaml.getBytes(StandardCharsets.UTF_8));
            return dir.toFile();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create test ling directory", e);
        }
    }
}
