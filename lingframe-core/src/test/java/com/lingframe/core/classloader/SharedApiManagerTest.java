package com.lingframe.core.classloader;

import com.lingframe.core.config.LingFrameConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("SharedApiManager 测试")
class SharedApiManagerTest {

    private SharedApiManager manager;
    private LingFrameConfig config;

    @TempDir
    File tempDir;

    @BeforeEach
    void setUp() {
        config = mock(LingFrameConfig.class);
        when(config.getLingHome()).thenReturn(tempDir.getAbsolutePath());
        when(config.getPreloadApiJars()).thenReturn(null);
        SharedApiClassLoader.resetInstance();
        LingClassLoader.resetSharedApiBoundary();
        manager = new SharedApiManager(Thread.currentThread().getContextClassLoader(), config);
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    @DisplayName("preloadFromConfig 无配置时不报错")
    void shouldPreloadWithNoConfig() {
        assertDoesNotThrow(() -> manager.preloadFromConfig());
    }

    @Test
    @DisplayName("preloadFromConfig 空列表时不报错")
    void shouldPreloadWithEmptyList() {
        when(config.getPreloadApiJars()).thenReturn(Arrays.asList());
        assertDoesNotThrow(() -> manager.preloadFromConfig());
    }

    @Test
    @DisplayName("freezeSharedBoundary 后不允许 addApi")
    void shouldNotAllowAddApiAfterFreeze() throws IOException {
        manager.freezeSharedBoundary();
        File jar = new File(tempDir, "test.jar");
        jar.createNewFile();

        assertThrows(IllegalStateException.class, () -> manager.addApi(jar));
    }

    @Test
    @DisplayName("freezeSharedBoundary 后不允许 preloadFromConfig")
    void shouldNotAllowPreloadAfterFreeze() {
        manager.freezeSharedBoundary();
        assertThrows(IllegalStateException.class, () -> manager.preloadFromConfig());
    }

    @Test
    @DisplayName("isFrozen 初始为 false")
    void shouldNotBeFrozenInitially() {
        assertFalse(manager.isFrozen());
    }

    @Test
    @DisplayName("isFrozen freeze 后为 true")
    void shouldBeFrozenAfterFreeze() {
        manager.freezeSharedBoundary();
        assertTrue(manager.isFrozen());
    }

    @Test
    @DisplayName("freezeSharedBoundary 幂等")
    void shouldIdempotentFreeze() {
        manager.freezeSharedBoundary();
        assertDoesNotThrow(() -> manager.freezeSharedBoundary());
    }

    @Test
    @DisplayName("getSharedApiClassLoader 返回非空")
    void shouldReturnSharedApiClassLoader() {
        assertNotNull(manager.getSharedApiClassLoader());
    }

    @Test
    @DisplayName("addApi 目录不报错")
    void shouldAddDirectory() throws IOException {
        File dir = new File(tempDir, "classes");
        dir.mkdirs();
        assertTrue(manager.addApi(dir));
    }

    @Test
    @DisplayName("addApi JAR 文件返回 false 当冲突检测失败")
    void shouldReturnFalseForInvalidJarFile() throws IOException {
        File jar = new File(tempDir, "test.jar");
        jar.createNewFile();
        // 空 JAR 文件无法通过冲突检测，返回 false
        assertFalse(manager.addApi(jar));
    }

    @Test
    @DisplayName("addApi 不存在的文件返回 false")
    void shouldReturnFalseForNonExistentFile() {
        assertFalse(manager.addApi(new File(tempDir, "nonexistent.jar")));
    }

    @Test
    @DisplayName("addApis 批量添加空 JAR 返回 0")
    void shouldAddMultipleApis() throws IOException {
        File jar1 = new File(tempDir, "a.jar");
        File jar2 = new File(tempDir, "b.jar");
        jar1.createNewFile();
        jar2.createNewFile();

        // 空 JAR 文件无法通过冲突检测
        int count = manager.addApis(Arrays.asList(jar1, jar2));
        assertEquals(0, count);
    }

    @Test
    @DisplayName("isSharedClass 对非共享类返回 false")
    void shouldReturnFalseForNonSharedClass() {
        assertFalse(manager.isSharedClass("com.example.NonShared"));
    }

    @Test
    @DisplayName("getStats 返回非空字符串")
    void shouldReturnStats() {
        assertNotNull(manager.getStats());
        assertTrue(manager.getStats().contains("SharedApiClassLoader"));
    }

    @Test
    @DisplayName("shutdown 后状态重置")
    void shouldResetAfterShutdown() {
        manager.freezeSharedBoundary();
        assertTrue(manager.isFrozen());
        manager.shutdown();
        assertFalse(manager.isFrozen());
    }

    @Test
    @DisplayName("preloadFromConfig 加载不存在的路径不报错")
    void shouldPreloadNonExistentPath() {
        when(config.getPreloadApiJars()).thenReturn(Arrays.asList("/nonexistent/path.jar"));
        assertDoesNotThrow(() -> manager.preloadFromConfig());
    }

    @Test
    @DisplayName("preloadFromConfig 加载 Maven 模块目录")
    void shouldPreloadMavenModuleDir() throws IOException {
        File mavenDir = new File(tempDir, "my-module");
        File targetClasses = new File(mavenDir, "target/classes");
        targetClasses.mkdirs();
        new File(mavenDir, "pom.xml").createNewFile();

        when(config.getPreloadApiJars()).thenReturn(Arrays.asList(mavenDir.getAbsolutePath()));
        assertDoesNotThrow(() -> manager.preloadFromConfig());
    }

    @Test
    @DisplayName("preloadFromConfig 加载 JAR 目录")
    void shouldPreloadJarDirectory() throws IOException {
        File jarDir = new File(tempDir, "jars");
        jarDir.mkdirs();
        File jar = new File(jarDir, "lib.jar");
        jar.createNewFile();

        when(config.getPreloadApiJars()).thenReturn(Arrays.asList(jarDir.getAbsolutePath()));
        assertDoesNotThrow(() -> manager.preloadFromConfig());
    }

    @Test
    @DisplayName("preloadFromConfig 加载 classes 目录")
    void shouldPreloadClassesDirectory() throws IOException {
        File classesDir = new File(tempDir, "classes");
        classesDir.mkdirs();

        when(config.getPreloadApiJars()).thenReturn(Arrays.asList(classesDir.getAbsolutePath()));
        assertDoesNotThrow(() -> manager.preloadFromConfig());
    }

    @Test
    @DisplayName("preloadFromConfig 通配符路径匹配")
    void shouldPreloadWildcardPath() throws IOException {
        File jarDir = new File(tempDir, "libs");
        jarDir.mkdirs();
        File jar1 = new File(jarDir, "api-1.jar");
        File jar2 = new File(jarDir, "api-2.jar");
        jar1.createNewFile();
        jar2.createNewFile();

        String wildcardPath = new File(jarDir, "*.jar").getAbsolutePath().replace('\\', '/');
        when(config.getPreloadApiJars()).thenReturn(Arrays.asList(wildcardPath));
        assertDoesNotThrow(() -> manager.preloadFromConfig());
    }

    @Test
    @DisplayName("preloadFromConfig 通配符目录不存在不报错")
    void shouldPreloadWildcardNonExistentDir() {
        when(config.getPreloadApiJars()).thenReturn(Arrays.asList("/nonexistent/*.jar"));
        assertDoesNotThrow(() -> manager.preloadFromConfig());
    }

    @Test
    @DisplayName("preloadFromConfig Maven 模块缺少 target/classes 不报错")
    void shouldPreloadMavenModuleMissingTargetClasses() throws IOException {
        File mavenDir = new File(tempDir, "incomplete-module");
        mavenDir.mkdirs();
        new File(mavenDir, "pom.xml").createNewFile();

        when(config.getPreloadApiJars()).thenReturn(Arrays.asList(mavenDir.getAbsolutePath()));
        assertDoesNotThrow(() -> manager.preloadFromConfig());
    }

    @Test
    @DisplayName("preloadFromConfig 不支持的文件类型不报错")
    void shouldPreloadUnsupportedFileType() throws IOException {
        File txtFile = new File(tempDir, "readme.txt");
        txtFile.createNewFile();

        when(config.getPreloadApiJars()).thenReturn(Arrays.asList(txtFile.getAbsolutePath()));
        assertDoesNotThrow(() -> manager.preloadFromConfig());
    }

    @Test
    @DisplayName("addApi 目录添加成功")
    void shouldAddApiDirectory() throws IOException {
        File dir = new File(tempDir, "api-classes");
        dir.mkdirs();
        assertTrue(manager.addApi(dir));
    }

    @Test
    @DisplayName("addApis 混合目录和无效文件")
    void shouldAddApisMixedFiles() throws IOException {
        File dir = new File(tempDir, "api-dir");
        dir.mkdirs();
        File invalid = new File(tempDir, "nonexistent.jar");

        int count = manager.addApis(Arrays.asList(dir, invalid));
        assertEquals(1, count);
    }
}
