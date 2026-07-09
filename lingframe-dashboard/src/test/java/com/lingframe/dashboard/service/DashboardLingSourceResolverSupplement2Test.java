package com.lingframe.dashboard.service;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.ling.InstancePool;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
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
 * DashboardLingSourceResolver 补充测试（第二批）
 * <p>
 * 聚焦 buildReloadVersion / markReload / findFromRoots 等尚未覆盖的分支。
 */
@DisplayName("DashboardLingSourceResolver 补充测试（第二批）")
class DashboardLingSourceResolverSupplement2Test {

    // ==================== buildReloadVersion ====================

    @Nested
    @DisplayName("buildReloadVersion")
    class BuildReloadVersionTests {

        @Test
        @DisplayName("无任何重载历史时应返回 reload-1")
        void shouldReturnReload1WhenNoHistory() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(mock(LingFrameConfig.class));
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            when(runtime.getInstancePool()).thenReturn(pool);
            when(pool.getAllInstances()).thenReturn(Collections.<LingInstance>emptyList());

            String result = resolver.buildReloadVersion(runtime, "1.0.0");

            assertEquals("1.0.0-reload-1", result);
        }

        @Test
        @DisplayName("应识别最大重载序号并加 1")
        void shouldReturnMaxPlusOne() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(mock(LingFrameConfig.class));
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            // 先构建实例再 stub，避免在 when().thenReturn() 参数内嵌套 stub 触发 UnfinishedStubbing
            LingInstance i1 = buildInstance("1.0.0-reload-1");
            LingInstance i2 = buildInstance("1.0.0-reload-3");
            LingInstance i3 = buildInstance("1.0.0-reload-2");
            when(runtime.getInstancePool()).thenReturn(pool);
            when(pool.getAllInstances()).thenReturn(Arrays.asList(i1, i2, i3));

            String result = resolver.buildReloadVersion(runtime, "1.0.0");

            assertEquals("1.0.0-reload-4", result);
        }

        @Test
        @DisplayName("应忽略格式不正确的重载序号")
        void shouldIgnoreMalformedSuffix() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(mock(LingFrameConfig.class));
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            LingInstance i1 = buildInstance("1.0.0-reload-2");
            LingInstance i2 = buildInstance("1.0.0-reload-abc");
            LingInstance i3 = buildInstance("1.0.0-reload-");
            when(runtime.getInstancePool()).thenReturn(pool);
            when(pool.getAllInstances()).thenReturn(Arrays.asList(i1, i2, i3));

            String result = resolver.buildReloadVersion(runtime, "1.0.0");

            assertEquals("1.0.0-reload-3", result);
        }

        @Test
        @DisplayName("应忽略 null 版本实例")
        void shouldIgnoreNullVersion() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(mock(LingFrameConfig.class));
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            when(runtime.getInstancePool()).thenReturn(pool);
            LingInstance nullVersionInstance = mock(LingInstance.class);
            when(nullVersionInstance.getVersion()).thenReturn(null);
            LingInstance normalInstance = mock(LingInstance.class);
            when(normalInstance.getVersion()).thenReturn("1.0.0-reload-5");
            when(pool.getAllInstances()).thenReturn(Arrays.asList(nullVersionInstance, normalInstance));

            String result = resolver.buildReloadVersion(runtime, "1.0.0");

            assertEquals("1.0.0-reload-6", result);
        }

        @Test
        @DisplayName("应忽略不匹配前缀的版本号")
        void shouldIgnoreNonMatchingPrefix() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(mock(LingFrameConfig.class));
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            LingInstance i1 = buildInstance("2.0.0-reload-1");
            LingInstance i2 = buildInstance("1.0.0-reload-3");
            LingInstance i3 = buildInstance("1.0.0");
            when(runtime.getInstancePool()).thenReturn(pool);
            when(pool.getAllInstances()).thenReturn(Arrays.asList(i1, i2, i3));

            String result = resolver.buildReloadVersion(runtime, "1.0.0");

            assertEquals("1.0.0-reload-4", result);
        }

        private LingInstance buildInstance(String version) {
            LingInstance instance = mock(LingInstance.class);
            when(instance.getVersion()).thenReturn(version);
            return instance;
        }
    }

    // ==================== markReload ====================

    @Nested
    @DisplayName("markReload")
    class MarkReloadTests {

        @Test
        @DisplayName("应同时设置 labels 和 properties 的 reload 标记")
        void shouldSetReloadMetadataInBothLabelsAndProperties() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(mock(LingFrameConfig.class));
            LingDefinition definition = new LingDefinition();
            Map<String, String> labels = new HashMap<>();
            Map<String, Object> properties = new HashMap<>();
            definition.setProperties(properties);

            resolver.markReload(definition, labels, "1.0.0-reload-1");

            assertEquals("true", labels.get("reload"));
            assertEquals("1.0.0-reload-1", labels.get("reloadVersion"));
            assertEquals(Boolean.TRUE, properties.get("reload"));
            assertEquals("1.0.0-reload-1", properties.get("reloadVersion"));
        }

        @Test
        @DisplayName("properties 为 null 时应自动创建并设置 reload 标记")
        void shouldCreatePropertiesWhenNull() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(mock(LingFrameConfig.class));
            LingDefinition definition = new LingDefinition();
            Map<String, String> labels = new HashMap<>();

            resolver.markReload(definition, labels, "2.0.0-reload-1");

            assertNotNull(definition.getProperties());
            assertEquals(Boolean.TRUE, definition.getProperties().get("reload"));
            assertEquals("2.0.0-reload-1", definition.getProperties().get("reloadVersion"));
        }

        @Test
        @DisplayName("labels 为 null 时应跳过 labels 设置但仍设置 properties")
        void shouldSkipLabelsWhenNull() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(mock(LingFrameConfig.class));
            LingDefinition definition = new LingDefinition();
            Map<String, Object> properties = new HashMap<>();
            definition.setProperties(properties);

            resolver.markReload(definition, null, "1.0.0-reload-1");

            assertEquals(Boolean.TRUE, properties.get("reload"));
            assertEquals("1.0.0-reload-1", properties.get("reloadVersion"));
        }

        @Test
        @DisplayName("labels 和 properties 均为 null 时应仅创建 properties")
        void shouldCreateOnlyPropertiesWhenBothNull() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(mock(LingFrameConfig.class));
            LingDefinition definition = new LingDefinition();

            resolver.markReload(definition, null, "3.0.0-reload-1");

            assertNotNull(definition.getProperties());
            assertEquals(Boolean.TRUE, definition.getProperties().get("reload"));
        }
    }

    // ==================== findFromRoots (通过 resolveSourceFile 间接测试) ====================

    @Nested
    @DisplayName("findFromRoots (通过 resolveSourceFile 间接测试)")
    class FindFromRootsTests {

        @Test
        @DisplayName("devMode 但 roots 为 null 时应返回 null")
        void shouldReturnNullWhenRootsNull() {
            LingFrameConfig config = LingFrameConfig.builder().devMode(true).build();
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(config);

            File result = resolver.resolveSourceFile("ling1", "1.0.0");

            assertNull(result);
        }

        @Test
        @DisplayName("devMode 但 roots 为空列表时应返回 null")
        void shouldReturnNullWhenRootsEmpty() {
            LingFrameConfig config = LingFrameConfig.builder()
                    .devMode(true)
                    .lingRoots(Collections.<String>emptyList())
                    .build();
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(config);

            File result = resolver.resolveSourceFile("ling1", "1.0.0");

            assertNull(result);
        }

        @Test
        @DisplayName("devMode 且 roots 指向不存在的目录时应返回 null")
        void shouldReturnNullWhenRootDirNotExists() {
            LingFrameConfig config = LingFrameConfig.builder()
                    .devMode(true)
                    .lingRoots(Arrays.asList("non-existent-root"))
                    .build();
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(config);

            File result = resolver.resolveSourceFile("ling1", "1.0.0");

            assertNull(result);
        }
    }

    // ==================== selectStableInstance 补充分支 ====================

    @Nested
    @DisplayName("selectStableInstance 补充分支")
    class SelectStableInstanceSupplementTests {

        @Test
        @DisplayName("所有实例均为灰度但无默认实例且活跃列表非空时应返回首个活跃实例")
        void shouldReturnFirstActiveWhenAllCanaryAndNoDefault() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(mock(LingFrameConfig.class));
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            when(runtime.getInstancePool()).thenReturn(pool);

            LingInstance canary1 = buildCanaryInstance("ling1", "v1");
            LingInstance canary2 = buildCanaryInstance("ling1", "v2");
            when(pool.getActiveInstances()).thenReturn(Arrays.asList(canary1, canary2));
            when(pool.getDefault()).thenReturn(null);

            LingInstance result = resolver.selectStableInstance(runtime);

            // 无非灰度实例，无默认，回退到首个活跃实例
            assertNotNull(result);
            assertEquals("v1", result.getVersion());
        }

        @Test
        @DisplayName("所有实例均为灰度但无默认实例且活跃列表为空时应返回 null")
        void shouldReturnNullWhenAllCanaryNoDefaultNoActive() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(mock(LingFrameConfig.class));
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            when(runtime.getInstancePool()).thenReturn(pool);
            when(pool.getActiveInstances()).thenReturn(Collections.<LingInstance>emptyList());
            when(pool.getDefault()).thenReturn(null);

            LingInstance result = resolver.selectStableInstance(runtime);

            assertNull(result);
        }

        @Test
        @DisplayName("实例的 definition 为 null 时应视为稳定实例返回")
        void shouldTreatInstanceWithNullDefinitionAsStable() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(mock(LingFrameConfig.class));
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            when(runtime.getInstancePool()).thenReturn(pool);

            LingInstance nullDefInstance = mock(LingInstance.class);
            when(nullDefInstance.getDefinition()).thenReturn(null);
            when(nullDefInstance.getVersion()).thenReturn("v1");

            LingInstance stableInstance = buildStableInstance("ling1", "v2");
            when(pool.getActiveInstances()).thenReturn(Arrays.asList(nullDefInstance, stableInstance));
            when(pool.getDefault()).thenReturn(null);

            LingInstance result = resolver.selectStableInstance(runtime);

            // isCanary(null definition) 返回 false，null-def 实例被视为稳定实例并返回
            assertNotNull(result);
            assertEquals("v1", result.getVersion());
        }

        @Test
        @DisplayName("应跳过灰度实例返回首个稳定实例")
        void shouldSkipCanaryAndReturnStable() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(mock(LingFrameConfig.class));
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            when(runtime.getInstancePool()).thenReturn(pool);

            LingInstance canary1 = buildCanaryInstance("ling1", "c1");
            LingInstance canary2 = buildCanaryInstance("ling1", "c2");
            LingInstance stable = buildStableInstance("ling1", "v1");
            when(pool.getActiveInstances()).thenReturn(Arrays.asList(canary1, canary2, stable));
            when(pool.getDefault()).thenReturn(null);

            LingInstance result = resolver.selectStableInstance(runtime);

            assertNotNull(result);
            assertEquals("v1", result.getVersion());
        }

        @Test
        @DisplayName("isCanary(instance) 当 instance 为 null 时应返回 false")
        void shouldReturnFalseForNullInstance() {
            DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(mock(LingFrameConfig.class));
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            when(runtime.getInstancePool()).thenReturn(pool);

            // 列表首个元素为 null，应被 isCanary(null) 判定为 false（即视为稳定实例）
            LingInstance nullInstance = null;
            when(pool.getActiveInstances()).thenReturn(Collections.singletonList(nullInstance));
            when(pool.getDefault()).thenReturn(null);

            // 由于 isCanary(null) 返回 false，null 实例会被当作稳定实例返回
            LingInstance result = resolver.selectStableInstance(runtime);

            assertNull(result);
        }
    }

    // ==================== 辅助方法 ====================

    private LingInstance buildCanaryInstance(String lingId, String version) {
        LingDefinition definition = new LingDefinition();
        definition.setId(lingId);
        definition.setVersion(version);
        Map<String, Object> props = new HashMap<>();
        props.put("canary", true);
        definition.setProperties(props);

        LingInstance instance = mock(LingInstance.class);
        when(instance.getDefinition()).thenReturn(definition);
        when(instance.getVersion()).thenReturn(version);
        when(instance.getLingId()).thenReturn(lingId);
        return instance;
    }

    private LingInstance buildStableInstance(String lingId, String version) {
        LingDefinition definition = new LingDefinition();
        definition.setId(lingId);
        definition.setVersion(version);

        LingInstance instance = mock(LingInstance.class);
        when(instance.getDefinition()).thenReturn(definition);
        when(instance.getVersion()).thenReturn(version);
        when(instance.getLingId()).thenReturn(lingId);
        return instance;
    }
}
