package com.lingframe.dashboard.service;

import com.lingframe.core.ling.InstancePool;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.dashboard.dto.LingResourceMetricsDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.net.URL;
import java.net.URLClassLoader;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("灵元资源指标采集器")
class LingResourceMetricsCollectorTest {

    private LingRepository lingRepository;
    private LingResourceMetricsCollector collector;

    @BeforeEach
    void setUp() {
        lingRepository = mock(LingRepository.class);
        collector = new LingResourceMetricsCollector(lingRepository, 10240);
        collector.init();
    }

    @Test
    @DisplayName("无灵元时返回空列表")
    void shouldReturnEmptyWhenNoLings() {
        when(lingRepository.getAllRuntimes()).thenReturn(Collections.emptyList());
        collector.sample();
        assertTrue(collector.getMetrics().isEmpty());
    }

    @Test
    @DisplayName("有灵元时应采集到类数和估算Metaspace")
    void shouldCollectClassCountAndMetaspace() throws Exception {
        // 探测当前 JDK 是否支持 ClassLoader.classes 字段，不支持则跳过此断言测试
        try {
            ClassLoader.class.getDeclaredField("classes");
        } catch (NoSuchFieldException e) {
            Assumptions.assumeTrue(false, "ClassLoader does not have 'classes' field, skipping this test: " + e.getMessage());
        }

        // 构造一个真实 URLClassLoader 模拟灵元 ClassLoader
        URLClassLoader lingCl = new URLClassLoader(new URL[0], getClass().getClassLoader()) {
            @Override
            public String toString() {
                return "TestLingClassLoader";
            }
        };
        // URLClassLoader.loadClass 默认委托给父加载器，子加载器的 classes 字段为空。
        // 通过反射向 ClassLoader.classes 字段注入测试类，验证采集逻辑能正确读取 size。
        injectClassIntoLoader(lingCl, DummyClassA.class);
        injectClassIntoLoader(lingCl, DummyClassB.class);

        LingInstance instance = mock(LingInstance.class);
        when(instance.getLingId()).thenReturn("test-ling");
        when(instance.getVersion()).thenReturn("1.0.0");
        when(instance.getClassLoader()).thenReturn(lingCl);

        InstancePool pool = mock(InstancePool.class);
        when(pool.getActiveInstances()).thenReturn(Collections.singletonList(instance));

        LingRuntime runtime = mock(LingRuntime.class);
        when(runtime.getInstancePool()).thenReturn(pool);

        when(lingRepository.getAllRuntimes()).thenReturn(Collections.singletonList(runtime));

        collector.sample();
        List<LingResourceMetricsDTO> metrics = collector.getMetrics();

        assertEquals(1, metrics.size());
        LingResourceMetricsDTO dto = metrics.get(0);
        assertEquals("test-ling", dto.getLingId());
        assertEquals("1.0.0", dto.getVersion());
        assertEquals(2, dto.getLoadedClassCount(), "应采集到注入的2个类");
        assertEquals(2 * 10240L, dto.getEstimatedMetaspaceBytes());
        assertTrue(dto.getTimestamp() > 0);

        lingCl.close();
    }

    /**
     * 反射向 ClassLoader.classes 字段注入类，模拟该 ClassLoader 已加载的类。
     */
    @SuppressWarnings("unchecked")
    private static void injectClassIntoLoader(ClassLoader cl, Class<?> clazz) throws Exception {
        Field f = ClassLoader.class.getDeclaredField("classes");
        f.setAccessible(true);
        Object classes = f.get(cl);
        if (classes instanceof List) {
            ((List<Object>) classes).add(clazz);
        }
    }

    @Test
    @DisplayName("destroy 后缓存应清空")
    void shouldClearCacheOnDestroy() {
        when(lingRepository.getAllRuntimes()).thenReturn(Collections.emptyList());
        collector.sample();
        collector.destroy();
        assertTrue(collector.getMetrics().isEmpty());
    }

    @Test
    @DisplayName("多版本灵元应分别采集")
    void shouldCollectMultipleVersions() throws Exception {
        URLClassLoader cl1 = new URLClassLoader(new URL[0], getClass().getClassLoader());
        URLClassLoader cl2 = new URLClassLoader(new URL[0], getClass().getClassLoader());

        LingInstance inst1 = mock(LingInstance.class);
        when(inst1.getLingId()).thenReturn("ling-x");
        when(inst1.getVersion()).thenReturn("1.0");
        when(inst1.getClassLoader()).thenReturn(cl1);

        LingInstance inst2 = mock(LingInstance.class);
        when(inst2.getLingId()).thenReturn("ling-x");
        when(inst2.getVersion()).thenReturn("2.0");
        when(inst2.getClassLoader()).thenReturn(cl2);

        InstancePool pool = mock(InstancePool.class);
        when(pool.getActiveInstances()).thenReturn(Arrays.asList(inst1, inst2));

        LingRuntime runtime = mock(LingRuntime.class);
        when(runtime.getInstancePool()).thenReturn(pool);

        when(lingRepository.getAllRuntimes()).thenReturn(Collections.singletonList(runtime));

        collector.sample();
        List<LingResourceMetricsDTO> metrics = collector.getMetrics();

        assertEquals(2, metrics.size(), "两个版本应分别采集");
        assertEquals("1.0", metrics.get(0).getVersion());
        assertEquals("2.0", metrics.get(1).getVersion());

        cl1.close();
        cl2.close();
    }

    /** 占位类，用于测试类加载 */
    public static class DummyClassA {
    }

    public static class DummyClassB {
    }
}
