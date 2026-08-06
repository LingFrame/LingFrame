package com.lingframe.dashboard.service;

import com.lingframe.core.ling.InstancePool;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.dashboard.dto.LingResourceMetricsDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LingResourceMetricsCollector 补充测试
 * <p>
 * 现有测试覆盖无灵元、有灵元正常采样、getMetrics 排序。
 * 本类补齐 destroy 清理、sample 异常容错、多灵元采样、loaderToInstance 为空时 cache.clear、
 * ClassLoader 为 null 的实例被跳过等分支。
 */
@DisplayName("LingResourceMetricsCollector 补充测试")
class LingResourceMetricsCollectorSupplementTest {

    private LingRepository lingRepository;
    private LingResourceMetricsCollector collector;

    @BeforeEach
    void setUp() {
        lingRepository = mock(LingRepository.class);
        collector = new LingResourceMetricsCollector(lingRepository, 10240);
        collector.init();
    }

    // ==================== destroy ====================

    @Nested
    @DisplayName("destroy")
    class DestroyTests {

        @Test
        @DisplayName("destroy 后应清空 cache，getMetrics 返回空列表")
        void shouldClearCacheOnDestroy() {
            when(lingRepository.getAllRuntimes()).thenReturn(Collections.emptyList());
            collector.sample();
            collector.destroy();

            assertTrue(collector.getMetrics().isEmpty());
        }

        @Test
        @DisplayName("多次调用 destroy 不应抛异常")
        void shouldNotThrowOnMultipleDestroy() {
            collector.destroy();
            assertDoesNotThrow(() -> collector.destroy());
        }
    }

    // ==================== sample 异常容错 ====================

    @Nested
    @DisplayName("sample 异常容错")
    class SampleExceptionTests {

        @Test
        @DisplayName("lingRepository.getAllRuntimes 抛异常时 sample 不应抛出")
        void shouldNotThrowWhenGetAllRuntimesFails() {
            when(lingRepository.getAllRuntimes()).thenThrow(new RuntimeException("db error"));

            assertDoesNotThrow(() -> collector.sample());
        }

        @Test
        @DisplayName("runtime.getInstancePool 抛异常时 sample 不应抛出")
        void shouldNotThrowWhenGetInstancePoolFails() {
            LingRuntime runtime = mock(LingRuntime.class);
            when(lingRepository.getAllRuntimes()).thenReturn(Collections.singletonList(runtime));
            when(runtime.getInstancePool()).thenThrow(new RuntimeException("pool error"));

            assertDoesNotThrow(() -> collector.sample());
        }

        @Test
        @DisplayName("runtime 为 null 时应被跳过")
        void shouldSkipNullRuntime() {
            when(lingRepository.getAllRuntimes()).thenReturn(Arrays.asList(null, null));
            collector.sample();
            assertTrue(collector.getMetrics().isEmpty());
        }
    }

    // ==================== ClassLoader 为 null 的实例 ====================

    @Nested
    @DisplayName("ClassLoader 为 null 的实例")
    class NullClassLoaderTests {

        @Test
        @DisplayName("实例 getClassLoader 返回 null 应被跳过")
        void shouldSkipInstanceWithNullClassLoader() {
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            LingInstance instance = mock(LingInstance.class);
            when(lingRepository.getAllRuntimes()).thenReturn(Collections.singletonList(runtime));
            when(runtime.getInstancePool()).thenReturn(pool);
            when(pool.getActiveInstances()).thenReturn(Collections.singletonList(instance));
            when(instance.getClassLoader()).thenReturn(null);

            collector.sample();

            // 无有效 ClassLoader，cache 应被清空
            assertTrue(collector.getMetrics().isEmpty());
        }
    }

    // ==================== 多灵元采样 ====================

    @Nested
    @DisplayName("多灵元采样")
    class MultipleLingTests {

        @Test
        @DisplayName("应同时采集多个灵元的资源指标")
        void shouldCollectMetricsForMultipleLings() throws Exception {
            URLClassLoader cl1 = new URLClassLoader(new URL[0], getClass().getClassLoader());
            URLClassLoader cl2 = new URLClassLoader(new URL[0], getClass().getClassLoader());

            LingRuntime runtime1 = mock(LingRuntime.class);
            LingRuntime runtime2 = mock(LingRuntime.class);
            InstancePool pool1 = mock(InstancePool.class);
            InstancePool pool2 = mock(InstancePool.class);
            LingInstance instance1 = mock(LingInstance.class);
            LingInstance instance2 = mock(LingInstance.class);

            when(lingRepository.getAllRuntimes()).thenReturn(Arrays.asList(runtime1, runtime2));
            when(runtime1.getInstancePool()).thenReturn(pool1);
            when(runtime2.getInstancePool()).thenReturn(pool2);
            when(pool1.getActiveInstances()).thenReturn(Collections.singletonList(instance1));
            when(pool2.getActiveInstances()).thenReturn(Collections.singletonList(instance2));
            when(instance1.getClassLoader()).thenReturn(cl1);
            when(instance2.getClassLoader()).thenReturn(cl2);
            when(instance1.getLingId()).thenReturn("ling1");
            when(instance1.getVersion()).thenReturn("1.0.0");
            when(instance2.getLingId()).thenReturn("ling2");
            when(instance2.getVersion()).thenReturn("2.0.0");

            collector.sample();

            List<LingResourceMetricsDTO> metrics = collector.getMetrics();
            assertEquals(2, metrics.size());
            // 应按 lingId 排序
            assertEquals("ling1", metrics.get(0).getLingId());
            assertEquals("ling2", metrics.get(1).getLingId());
        }
    }

    // ==================== getMetrics 排序 ====================

    @Nested
    @DisplayName("getMetrics 排序")
    class GetMetricsSortTests {

        @Test
        @DisplayName("应按 lingId 然后按 version 排序")
        void shouldSortByLingIdThenVersion() throws Exception {
            URLClassLoader cl1 = new URLClassLoader(new URL[0], getClass().getClassLoader());
            URLClassLoader cl2 = new URLClassLoader(new URL[0], getClass().getClassLoader());

            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            LingInstance instance1 = mock(LingInstance.class);
            LingInstance instance2 = mock(LingInstance.class);

            when(lingRepository.getAllRuntimes()).thenReturn(Collections.singletonList(runtime));
            when(runtime.getInstancePool()).thenReturn(pool);
            when(pool.getActiveInstances()).thenReturn(Arrays.asList(instance2, instance1));
            when(instance1.getClassLoader()).thenReturn(cl1);
            when(instance2.getClassLoader()).thenReturn(cl2);
            when(instance1.getLingId()).thenReturn("ling1");
            when(instance1.getVersion()).thenReturn("1.0.0");
            when(instance2.getLingId()).thenReturn("ling1");
            when(instance2.getVersion()).thenReturn("0.9.0");

            collector.sample();

            List<LingResourceMetricsDTO> metrics = collector.getMetrics();
            assertEquals(2, metrics.size());
            // 同 lingId 时按 version 升序
            assertEquals("0.9.0", metrics.get(0).getVersion());
            assertEquals("1.0.0", metrics.get(1).getVersion());
        }
    }
}
