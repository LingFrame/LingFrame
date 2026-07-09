package com.lingframe.dashboard.service;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.context.LingContext;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingRuntimeConfig;
import com.lingframe.core.spi.LingContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ==================== isCanary ====================

    @Nested
    @DisplayName("isCanary")
    class IsCanaryTests {

        @Test
        @DisplayName("null definition 应返回 false")
        void shouldReturnFalseForNullDefinition() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(
                    LingFrameConfig.builder().build());

            assertFalse(resolver.isCanary((LingDefinition) null));
        }

        @Test
        @DisplayName("null properties 应返回 false")
        void shouldReturnFalseForNullProperties() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(
                    LingFrameConfig.builder().build());
            LingDefinition def = new LingDefinition();
            def.setProperties(null);

            assertFalse(resolver.isCanary(def));
        }

        @Test
        @DisplayName("canary 属性不存在时应返回 false")
        void shouldReturnFalseWhenCanaryAbsent() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(
                    LingFrameConfig.builder().build());
            LingDefinition def = new LingDefinition();
            Map<String, Object> props = new HashMap<String, Object>();
            def.setProperties(props);

            assertFalse(resolver.isCanary(def));
        }

        @Test
        @DisplayName("Boolean true 应返回 true")
        void shouldReturnTrueForBooleanTrue() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(
                    LingFrameConfig.builder().build());
            LingDefinition def = newDefinitionWithCanary(Boolean.TRUE);

            assertTrue(resolver.isCanary(def));
        }

        @Test
        @DisplayName("Boolean false 应返回 false")
        void shouldReturnFalseForBooleanFalse() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(
                    LingFrameConfig.builder().build());
            LingDefinition def = newDefinitionWithCanary(Boolean.FALSE);

            assertFalse(resolver.isCanary(def));
        }

        @Test
        @DisplayName("非零 Number 应返回 true")
        void shouldReturnTrueForNonZeroNumber() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(
                    LingFrameConfig.builder().build());
            LingDefinition def = newDefinitionWithCanary(Integer.valueOf(1));

            assertTrue(resolver.isCanary(def));
        }

        @Test
        @DisplayName("零 Number 应返回 false")
        void shouldReturnFalseForZeroNumber() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(
                    LingFrameConfig.builder().build());
            LingDefinition def = newDefinitionWithCanary(Integer.valueOf(0));

            assertFalse(resolver.isCanary(def));
        }

        @Test
        @DisplayName("字符串 'true' 应返回 true（大小写不敏感）")
        void shouldReturnTrueForStringTrueCaseInsensitive() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(
                    LingFrameConfig.builder().build());
            LingDefinition def = newDefinitionWithCanary("TRUE");

            assertTrue(resolver.isCanary(def));
        }

        @Test
        @DisplayName("字符串 'false' 应返回 false")
        void shouldReturnFalseForStringFalse() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(
                    LingFrameConfig.builder().build());
            LingDefinition def = newDefinitionWithCanary("false");

            assertFalse(resolver.isCanary(def));
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
            EventBus eventBus = new EventBus();
            RuntimeCoordinator coordinator = new RuntimeCoordinator(eventBus);
            LingRuntime runtime = new LingRuntime("ling1",
                    LingRuntimeConfig.defaults(), eventBus, coordinator);
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(
                    LingFrameConfig.builder().build());

            LingInstance result = resolver.selectStableInstance(runtime);

            assertNull(result);
        }

        @Test
        @DisplayName("存在非灰度实例时应优先返回非灰度实例")
        void shouldReturnNonCanaryInstanceFirst() {
            EventBus eventBus = new EventBus();
            RuntimeCoordinator coordinator = new RuntimeCoordinator(eventBus);
            LingRuntime runtime = new LingRuntime("ling1",
                    LingRuntimeConfig.defaults(), eventBus, coordinator);
            LingInstance stable = instance("ling1", "1.0.0", false);
            LingInstance canary = instance("ling1", "1.1.0", true);
            runtime.getInstancePool().addInstance(stable, true);
            runtime.getInstancePool().addInstance(canary, false);

            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(
                    LingFrameConfig.builder().build());

            LingInstance result = resolver.selectStableInstance(runtime);

            assertEquals(stable, result);
        }

        @Test
        @DisplayName("仅有灰度实例时应回退到默认实例")
        void shouldFallbackToDefaultWhenOnlyCanary() {
            EventBus eventBus = new EventBus();
            RuntimeCoordinator coordinator = new RuntimeCoordinator(eventBus);
            LingRuntime runtime = new LingRuntime("ling1",
                    LingRuntimeConfig.defaults(), eventBus, coordinator);
            LingInstance canary = instance("ling1", "1.1.0", true);
            runtime.getInstancePool().addInstance(canary, true);

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
