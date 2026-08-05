package com.lingframe.dashboard.service;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.context.LingContext;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.ling.InstancePool;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.spi.LingContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DashboardLingSourceResolver 补充测试
 * <p>
 * 覆盖 listHomeFiles / selectStableInstance / isCanary / resolveSourceFile / getLingFrameConfig
 * 等在 DashboardLingSourceResolverTest 中未触及的方法分支。
 */
@DisplayName("DashboardLingSourceResolver 补充测试")
class DashboardLingSourceResolverSupplementTest {

    @TempDir
    Path tempDir;

    // ==================== listHomeFiles ====================

    @Nested
    @DisplayName("listHomeFiles")
    class ListHomeFilesTests {

        @Test
        @DisplayName("lingHome 为 null 时应返回空列表")
        void shouldReturnEmptyWhenLingHomeNull() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(
                    LingFrameConfig.builder().build());

            List<File> result = resolver.listHomeFiles();

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("lingHome 指向不存在的目录时应返回空列表")
        void shouldReturnEmptyWhenHomeNotExists() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(
                    LingFrameConfig.builder().lingHome("non-existent-xyz-12345").build());

            List<File> result = resolver.listHomeFiles();

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("应仅列出 .jar 文件并过滤其他文件")
        void shouldListOnlyJarFiles() throws IOException {
            Files.createFile(tempDir.resolve("ling1.jar"));
            Files.createFile(tempDir.resolve("ling2.jar"));
            Files.createFile(tempDir.resolve("readme.txt"));
            Files.createFile(tempDir.resolve("config.json"));

            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(
                    LingFrameConfig.builder().lingHome(tempDir.toString()).build());

            List<File> result = resolver.listHomeFiles();

            assertEquals(2, result.size());
            for (File file : result) {
                assertTrue(file.getName().endsWith(".jar"));
            }
        }

        @Test
        @DisplayName("空目录应返回空列表")
        void shouldReturnEmptyForEmptyDir() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(
                    LingFrameConfig.builder().lingHome(tempDir.toString()).build());

            List<File> result = resolver.listHomeFiles();

            assertTrue(result.isEmpty());
        }
    }

    // ==================== getLingFrameConfig ====================

    @Nested
    @DisplayName("getLingFrameConfig")
    class GetConfigTests {

        @Test
        @DisplayName("应返回构造时传入的配置对象")
        void shouldReturnConfigFromConstructor() {
            LingFrameConfig config = LingFrameConfig.builder().lingHome("lings").build();
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(config);

            assertEquals(config, resolver.getLingFrameConfig());
        }
    }


    // ==================== selectStableInstance ====================

    @Nested
    @DisplayName("selectStableInstance")
    class SelectStableInstanceTests {

        @Test
        @DisplayName("null runtime 应返回 null")
        void shouldReturnNullForNullRuntime() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(
                    LingFrameConfig.builder().build());

            LingInstance result = resolver.selectStableInstance(null);

            assertNull(result);
        }

        @Test
        @DisplayName("无活跃实例且无默认实例时应返回 null")
        void shouldReturnNullWhenNoActiveAndNoDefault() {
            LingRuntime runtime = mockRuntimeWithPool();
            when(runtime.getInstancePool().getActiveInstances()).thenReturn(Collections.emptyList());
            when(runtime.getInstancePool().getDefault()).thenReturn(null);
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(
                    LingFrameConfig.builder().build());

            LingInstance result = resolver.selectStableInstance(runtime);

            assertNull(result);
        }

        @Test
        @DisplayName("存在非灰度实例时应优先返回非灰度实例")
        void shouldReturnNonCanaryInstanceFirst() {
            LingInstance stable = instance("ling1", "1.0.0", false);
            LingInstance canary = instance("ling1", "1.1.0", true);
            LingRuntime runtime = mockRuntimeWithPool();
            when(runtime.getInstancePool().getActiveInstances()).thenReturn(Arrays.asList(stable, canary));

            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(
                    LingFrameConfig.builder().build());

            LingInstance result = resolver.selectStableInstance(runtime);

            assertEquals(stable, result);
        }

        @Test
        @DisplayName("仅有灰度实例时应回退到默认实例")
        void shouldFallbackToDefaultWhenOnlyCanary() {
            LingInstance canary = instance("ling1", "1.1.0", true);
            LingRuntime runtime = mockRuntimeWithPool();
            when(runtime.getInstancePool().getActiveInstances()).thenReturn(Arrays.asList(canary));
            when(runtime.getInstancePool().getDefault()).thenReturn(canary);

            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(
                    LingFrameConfig.builder().build());

            LingInstance result = resolver.selectStableInstance(runtime);

            // 所有实例都是灰度，回退到 getDefault
            assertEquals(canary, result);
        }
    }

    // ==================== resolveSourceFile ====================

    @Nested
    @DisplayName("resolveSourceFile")
    class ResolveSourceFileTests {

        @Test
        @DisplayName("非 devMode 且 lingHome 为 null 时应返回 null")
        void shouldReturnNullWhenNotDevModeAndNoHome() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(
                    LingFrameConfig.builder().build());

            File result = resolver.resolveSourceFile("ling1", "1.0.0");

            assertNull(result);
        }

        @Test
        @DisplayName("非 devMode 且 lingHome 不存在时应返回 null")
        void shouldReturnNullWhenHomeNotExists() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(
                    LingFrameConfig.builder().lingHome("non-existent-xyz").build());

            File result = resolver.resolveSourceFile("ling1", "1.0.0");

            assertNull(result);
        }

        @Test
        @DisplayName("devMode 但无 roots 时应回退到 findFromHome")
        void shouldFallbackToHomeWhenNoRoots() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(
                    LingFrameConfig.builder().devMode(true).build());

            File result = resolver.resolveSourceFile("ling1", "1.0.0");

            assertNull(result);
        }
    }

    // ==================== resolveHomePackageFile ====================

    @Nested
    @DisplayName("resolveHomePackageFile")
    class ResolveHomePackageFileTests {

        @Test
        @DisplayName("即使 dev 模式下目标/classes 存在，也只解析 ling-home 下的物理包")
        void shouldIgnoreDevRootsAndOnlyResolveFromHome() throws IOException {
            // 构造 dev root 下的 target/classes，匹配同一 lingId/version
            Path classesDir = tempDir.resolve("target").resolve("classes");
            Files.createDirectories(classesDir);
            Files.write(classesDir.resolve("ling.yml"),
                    ("id: ling1\nversion: 1.0.0\nmainClass: \"demo.Main\"\n").getBytes());

            // 构造 ling-home 下的物理包目录（同样匹配 lingId/version）
            Path homeDir = tempDir.resolve("home");
            Path packageDir = homeDir.resolve("ling1-1.0.0");
            Files.createDirectories(packageDir);
            Files.write(packageDir.resolve("ling.yml"),
                    ("id: ling1\nversion: 1.0.0\nmainClass: \"demo.Main\"\n").getBytes());

            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(
                    LingFrameConfig.builder()
                            .devMode(true)
                            .lingRoots(Collections.singletonList(tempDir.toString()))
                            .lingHome(homeDir.toString())
                            .build());

            // resolveSourceFile 会优先 dev classes，而物理包解析必须只认 ling-home
            File sourceFile = resolver.resolveSourceFile("ling1", "1.0.0");
            File homePackage = resolver.resolveHomePackageFile("ling1", "1.0.0");

            assertEquals(classesDir.toFile(), sourceFile);
            assertEquals(packageDir.toFile(), homePackage);
            assertFalse(homePackage.getPath().contains("target" + File.separator + "classes"));
        }

        @Test
        @DisplayName("lingHome 为 null 时应返回 null")
        void shouldReturnNullWhenHomeNull() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(
                    LingFrameConfig.builder().build());

            assertNull(resolver.resolveHomePackageFile("ling1", "1.0.0"));
        }

        @Test
        @DisplayName("ling-home 下无匹配物理包时应返回 null")
        void shouldReturnNullWhenNoMatchInHome() throws IOException {
            Path homeDir = tempDir.resolve("home");
            Files.createDirectories(homeDir);

            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(
                    LingFrameConfig.builder().lingHome(homeDir.toString()).build());

            assertNull(resolver.resolveHomePackageFile("ling1", "1.0.0"));
        }
    }

    // ==================== 辅助方法 ====================

    private LingDefinition newDefinitionWithCanary(Object canaryValue) {
        LingDefinition def = new LingDefinition();
        Map<String, Object> props = new HashMap<String, Object>();
        props.put("canary", canaryValue);
        def.setProperties(props);
        return def;
    }

    private LingInstance instance(String lingId, String version, boolean canary) {
        LingDefinition definition = new LingDefinition();
        definition.setId(lingId);
        definition.setVersion(version);
        definition.setMainClass("demo.Main");
        if (canary) {
            Map<String, Object> props = new HashMap<String, Object>();
            props.put("canary", true);
            definition.setProperties(props);
        }
        return new LingInstance(new StubLingContainer(), definition, new EventBus());
    }

    /**
     * 构造 mock LingRuntime + mock InstancePool，
     * 供 selectStableInstance / buildReloadVersion 等只读查询测试使用。
     */
    private LingRuntime mockRuntimeWithPool() {
        LingRuntime runtime = mock(LingRuntime.class);
        InstancePool pool = mock(InstancePool.class);
        when(runtime.getInstancePool()).thenReturn(pool);
        return runtime;
    }

    private static final class StubLingContainer implements LingContainer {
        @Override
        public void start(LingContext context) {
        }

        @Override
        public void stop() {
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public <T> T getBean(Class<T> type) {
            return null;
        }

        @Override
        public Object getBean(String beanName) {
            return null;
        }

        @Override
        public String[] getBeanNames() {
            return new String[0];
        }

        @Override
        public ClassLoader getClassLoader() {
            return getClass().getClassLoader();
        }
    }
}
