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
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("DashboardLingSourceResolver 测试")
class DashboardLingSourceResolverTest {

    @Test
    @DisplayName("应基于现有实例生成下一个热重载版本号")
    void shouldBuildNextReloadVersion() {
        LingRuntime runtime = mock(LingRuntime.class);
        InstancePool pool = mock(InstancePool.class);
        when(runtime.getInstancePool()).thenReturn(pool);
        when(pool.getAllInstances()).thenReturn(Arrays.asList(
                instance("ling1", "1.0.0-reload-1"),
                instance("ling1", "1.0.0-reload-3")));

        DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(
                LingFrameConfig.builder().lingHome("lings").build());

        assertEquals("1.0.0-reload-4", resolver.buildReloadVersion(runtime, "1.0.0"));
    }

    @Test
    @DisplayName("标记热重载时应写入标签与属性")
    void shouldMarkReloadMetadata() {
        DashboardLingSourceResolver resolver = new DashboardLingSourceResolver(
                LingFrameConfig.builder().lingHome("lings").build());
        LingDefinition definition = new LingDefinition();
        definition.setId("ling1");
        definition.setVersion("1.0.0");
        definition.setMainClass("demo.Main");
        Map<String, String> labels = new HashMap<String, String>();

        resolver.markReload(definition, labels, "1.0.0-reload-1");

        assertEquals("true", labels.get("reload"));
        assertEquals("1.0.0-reload-1", labels.get("reloadVersion"));
        assertTrue(Boolean.TRUE.equals(definition.getProperties().get("reload")));
        assertEquals("1.0.0-reload-1", definition.getProperties().get("reloadVersion"));
    }

    private LingInstance instance(String lingId, String version) {
        LingDefinition definition = new LingDefinition();
        definition.setId(lingId);
        definition.setVersion(version);
        definition.setMainClass("demo.Main");
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