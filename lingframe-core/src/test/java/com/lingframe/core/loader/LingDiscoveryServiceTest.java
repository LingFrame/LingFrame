package com.lingframe.core.loader;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.ling.LingLifecycleEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("LingDiscoveryService 测试")
class LingDiscoveryServiceTest {

    @TempDir
    File tempDir;

    private LingFrameConfig config;
    private LingLifecycleEngine lifecycleEngine;
    private LingDiscoveryService service;

    @BeforeEach
    void setUp() {
        LingFrameConfig.clear();
        config = LingFrameConfig.builder()
                .autoScan(true)
                .lingHome("")
                .lingRoots(Collections.emptyList())
                .build();
        LingFrameConfig.init(config);
        lifecycleEngine = mock(LingLifecycleEngine.class);
        service = new LingDiscoveryService(config, lifecycleEngine);
    }

    @AfterEach
    void tearDown() {
        LingFrameConfig.clear();
    }

    @Test
    @DisplayName("autoScan 为 false 时不扫描")
    void shouldSkipWhenAutoScanDisabled() {
        LingFrameConfig noScanConfig = LingFrameConfig.builder()
                .autoScan(false)
                .lingHome("")
                .lingRoots(Collections.emptyList())
                .build();
        LingFrameConfig.clear();
        LingFrameConfig.init(noScanConfig);

        LingDiscoveryService noScanService = new LingDiscoveryService(noScanConfig, lifecycleEngine);
        noScanService.scanAndLoad();

        verify(lifecycleEngine, never()).deploy(any(LingDefinition.class), any(File.class), anyBoolean(), any(Map.class));
    }

    @Test
    @DisplayName("扫描有效灵元目录并调用 deploy")
    @SuppressWarnings("unchecked")
    void shouldDeployValidLingFromDirectory() throws IOException {
        File lingDir = new File(tempDir, "my-ling");
        lingDir.mkdirs();
        String yaml = "id: my-ling\nversion: 1.0.0\n";
        Files.write(new File(lingDir, "ling.yml").toPath(), yaml.getBytes());

        LingFrameConfig cfg = LingFrameConfig.builder()
                .autoScan(true)
                .lingHome(tempDir.getAbsolutePath())
                .lingRoots(Collections.emptyList())
                .build();
        LingFrameConfig.clear();
        LingFrameConfig.init(cfg);

        new LingDiscoveryService(cfg, lifecycleEngine).scanAndLoad();

        verify(lifecycleEngine, times(1)).deploy(any(LingDefinition.class), eq(lingDir), eq(true), any(Map.class));
    }

    @Test
    @DisplayName("扫描金丝雀灵元时 deploy 参数为 false")
    @SuppressWarnings("unchecked")
    void shouldDeployCanaryLingWithDefaultFalse() throws IOException {
        File lingDir = new File(tempDir, "canary-ling");
        lingDir.mkdirs();
        String yaml = "id: canary-ling\nversion: 2.0.0\nproperties:\n  canary: true\n";
        Files.write(new File(lingDir, "ling.yml").toPath(), yaml.getBytes());

        LingFrameConfig cfg = LingFrameConfig.builder()
                .autoScan(true)
                .lingHome(tempDir.getAbsolutePath())
                .lingRoots(Collections.emptyList())
                .build();
        LingFrameConfig.clear();
        LingFrameConfig.init(cfg);

        new LingDiscoveryService(cfg, lifecycleEngine).scanAndLoad();

        verify(lifecycleEngine, times(1)).deploy(any(LingDefinition.class), eq(lingDir), eq(false), any(Map.class));
    }

    @Test
    @DisplayName("无效灵元目录不调用 deploy")
    @SuppressWarnings("unchecked")
    void shouldSkipInvalidLingDirectory() throws IOException {
        File emptyDir = new File(tempDir, "empty-dir");
        emptyDir.mkdirs();
        // 无 ling.yml

        LingFrameConfig cfg = LingFrameConfig.builder()
                .autoScan(true)
                .lingHome(tempDir.getAbsolutePath())
                .lingRoots(Collections.emptyList())
                .build();
        LingFrameConfig.clear();
        LingFrameConfig.init(cfg);

        new LingDiscoveryService(cfg, lifecycleEngine).scanAndLoad();

        verify(lifecycleEngine, never()).deploy(any(LingDefinition.class), any(File.class), anyBoolean(), any(Map.class));
    }

    @Test
    @DisplayName("lingHome 为空时不扫描 home 目录")
    @SuppressWarnings("unchecked")
    void shouldSkipWhenLingHomeEmpty() {
        LingFrameConfig cfg = LingFrameConfig.builder()
                .autoScan(true)
                .lingHome("  ")
                .lingRoots(Collections.emptyList())
                .build();
        LingFrameConfig.clear();
        LingFrameConfig.init(cfg);

        new LingDiscoveryService(cfg, lifecycleEngine).scanAndLoad();

        verify(lifecycleEngine, never()).deploy(any(LingDefinition.class), any(File.class), anyBoolean(), any(Map.class));
    }

    @Test
    @DisplayName("不存在的 lingHome 目录不报错")
    @SuppressWarnings("unchecked")
    void shouldHandleNonExistentLingHome() {
        LingFrameConfig cfg = LingFrameConfig.builder()
                .autoScan(true)
                .lingHome("/nonexistent/path")
                .lingRoots(Collections.emptyList())
                .build();
        LingFrameConfig.clear();
        LingFrameConfig.init(cfg);

        new LingDiscoveryService(cfg, lifecycleEngine).scanAndLoad();

        verify(lifecycleEngine, never()).deploy(any(LingDefinition.class), any(File.class), anyBoolean(), any(Map.class));
    }

    @Test
    @DisplayName("deploy 异常不阻断整个扫描")
    @SuppressWarnings("unchecked")
    void shouldContinueOnDeployException() throws IOException {
        File lingDir1 = new File(tempDir, "ling-a");
        lingDir1.mkdirs();
        Files.write(new File(lingDir1, "ling.yml").toPath(),
                "id: ling-a\nversion: 1.0.0\n".getBytes());

        File lingDir2 = new File(tempDir, "ling-b");
        lingDir2.mkdirs();
        Files.write(new File(lingDir2, "ling.yml").toPath(),
                "id: ling-b\nversion: 1.0.0\n".getBytes());

        doThrow(new RuntimeException("deploy failed"))
                .when(lifecycleEngine).deploy(any(LingDefinition.class), eq(lingDir1), anyBoolean(), any(Map.class));

        LingFrameConfig cfg = LingFrameConfig.builder()
                .autoScan(true)
                .lingHome(tempDir.getAbsolutePath())
                .lingRoots(Collections.emptyList())
                .build();
        LingFrameConfig.clear();
        LingFrameConfig.init(cfg);

        new LingDiscoveryService(cfg, lifecycleEngine).scanAndLoad();

        // 第二个灵元仍然应该被尝试部署
        verify(lifecycleEngine, atLeastOnce()).deploy(any(LingDefinition.class), eq(lingDir2), anyBoolean(), any(Map.class));
    }
}
