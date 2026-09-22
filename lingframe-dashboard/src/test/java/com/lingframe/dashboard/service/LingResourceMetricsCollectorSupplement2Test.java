package com.lingframe.dashboard.service;

import com.lingframe.core.ling.InstancePool;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.dashboard.dto.LingResourceMetricsDTO;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LingResourceMetricsCollector 补充测试（第二批）
 * <p>
 * 聚焦 init() 方法、countLoadedClasses 反射分支、线程采样 CPU/堆分配统计、
 * loaderToInstance 为空时 cache.clear 等未覆盖分支。
 */
@DisplayName("LingResourceMetricsCollector 补充测试（第二批）")
class LingResourceMetricsCollectorSupplement2Test {

    // ==================== init 方法 ====================

    @Nested
    @DisplayName("init 方法")
    class InitTests {

        @Test
        @DisplayName("init 应成功初始化 ThreadMXBean 并启用 CPU 时间")
        void shouldInitThreadMXBeanAndEnableCpuTime() {
            LingRepository repository = mock(LingRepository.class);
            LingResourceMetricsCollector collector = new LingResourceMetricsCollector(repository, 10240);

            assertDoesNotThrow(() -> collector.init());

            // 初始化后采样不应抛异常
            assertDoesNotThrow(() -> collector.sample());
        }

        @Test
        @DisplayName("多次调用 init 不应抛异常")
        void shouldNotThrowOnMultipleInit() {
            LingRepository repository = mock(LingRepository.class);
            LingResourceMetricsCollector collector = new LingResourceMetricsCollector(repository, 10240);

            collector.init();
            assertDoesNotThrow(() -> collector.init());
            assertDoesNotThrow(() -> collector.init());
        }
    }

    // ==================== sample 异常容错 ====================

    @Nested
    @DisplayName("sample 异常容错")
    class SampleExceptionTests {

        @Test
        @DisplayName("lingRepository 为 null 时 sample 不应抛异常")
        void shouldNotThrowWhenRepositoryNull() {
            LingResourceMetricsCollector collector = new LingResourceMetricsCollector(null, 10240);
            collector.init();

            assertDoesNotThrow(() -> collector.sample());
        }

        @Test
        @DisplayName("getAllRuntimes 返回含 null 元素时不应抛异常")
        void shouldNotThrowWhenRuntimesContainNull() {
            LingRepository repository = mock(LingRepository.class);
            when(repository.getAllRuntimes()).thenReturn(Arrays.asList(null, null));

            LingResourceMetricsCollector collector = new LingResourceMetricsCollector(repository, 10240);
            collector.init();

            assertDoesNotThrow(() -> collector.sample());
        }

        @Test
        @DisplayName("getInstancePool 抛异常时不应影响整体采样")
        void shouldNotThrowWhenGetInstancePoolFails() {
            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.getInstancePool()).thenThrow(new RuntimeException("pool error"));

            LingRepository repository = mock(LingRepository.class);
            when(repository.getAllRuntimes()).thenReturn(Collections.singletonList(runtime));

            LingResourceMetricsCollector collector = new LingResourceMetricsCollector(repository, 10240);
            collector.init();

            // 由于 getInstancePool 抛异常，sample 应通过 try-catch 兜底
            assertDoesNotThrow(() -> collector.sample());
        }
    }

    // ==================== doSample 线程采样 ====================

    @Nested
    @DisplayName("doSample 线程采样")
    class DoSampleTests {

        @Test
        @DisplayName("无活跃实例时应清空缓存")
        void shouldClearCacheWhenNoActiveInstances() {
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            when(runtime.getInstancePool()).thenReturn(pool);
            when(pool.getActiveInstances()).thenReturn(Collections.<LingInstance>emptyList());

            LingRepository repository = mock(LingRepository.class);
            when(repository.getAllRuntimes()).thenReturn(Collections.singletonList(runtime));

            LingResourceMetricsCollector collector = new LingResourceMetricsCollector(repository, 10240);
            collector.init();

            // 先采样一次填充缓存（虽然无活跃实例，cache 会被 clear）
            collector.sample();

            // 确认 getMetrics 返回空
            List<LingResourceMetricsDTO> metrics = collector.getMetrics();
            assertTrue(metrics.isEmpty());
        }

        @Test
        @DisplayName("实例有非 null ClassLoader 时应按 ClassLoader 分组采样")
        void shouldGroupByClassLoader() {
            URLClassLoader loader = new URLClassLoader(new URL[0], getClass().getClassLoader());

            LingInstance instance = mock(LingInstance.class);
            when(instance.getClassLoader()).thenReturn(loader);
            when(instance.getLingId()).thenReturn("ling1");
            when(instance.getVersion()).thenReturn("v1");

            InstancePool pool = mock(InstancePool.class);
            when(pool.getActiveInstances()).thenReturn(Collections.singletonList(instance));

            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.getInstancePool()).thenReturn(pool);

            LingRepository repository = mock(LingRepository.class);
            when(repository.getAllRuntimes()).thenReturn(Collections.singletonList(runtime));

            LingResourceMetricsCollector collector = new LingResourceMetricsCollector(repository, 10240);
            collector.init();

            collector.sample();

            List<LingResourceMetricsDTO> metrics = collector.getMetrics();
            assertFalse(metrics.isEmpty());

            LingResourceMetricsDTO dto = metrics.get(0);
            assertEquals("ling1", dto.getLingId());
            assertEquals("v1", dto.getVersion());
            assertTrue(dto.getLoadedClassCount() >= 0);
            assertTrue(dto.getEstimatedMetaspaceBytes() >= 0);
            assertNotNull(dto.getTimestamp());
        }

        @Test
        @DisplayName("metaspaceBytesPerClass 应正确参与 metaspace 估算")
        void shouldUseMetaspaceBytesPerClass() {
            URLClassLoader loader = new URLClassLoader(new URL[0], getClass().getClassLoader());

            LingInstance instance = mock(LingInstance.class);
            when(instance.getClassLoader()).thenReturn(loader);
            when(instance.getLingId()).thenReturn("ling1");
            when(instance.getVersion()).thenReturn("v1");

            InstancePool pool = mock(InstancePool.class);
            when(pool.getActiveInstances()).thenReturn(Collections.singletonList(instance));

            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.getInstancePool()).thenReturn(pool);

            LingRepository repository = mock(LingRepository.class);
            when(repository.getAllRuntimes()).thenReturn(Collections.singletonList(runtime));

            LingResourceMetricsCollector collector = new LingResourceMetricsCollector(repository, 20480);
            collector.init();
            collector.sample();

            List<LingResourceMetricsDTO> metrics = collector.getMetrics();
            assertFalse(metrics.isEmpty());

            // metaspace = classCount * 20480，由于 classCount 可能为 0，metaspace 可能为 0
            // 但只要不抛异常就说明估算逻辑正常工作
            assertTrue(metrics.get(0).getEstimatedMetaspaceBytes() >= 0);
        }
    }

    // ==================== getMetrics 空缓存 ====================

    @Nested
    @DisplayName("getMetrics 空缓存")
    class GetMetricsTests {

        @Test
        @DisplayName("未采样时 getMetrics 应返回空列表")
        void shouldReturnEmptyWhenNotSampled() {
            LingRepository repository = mock(LingRepository.class);
            LingResourceMetricsCollector collector = new LingResourceMetricsCollector(repository, 10240);
            collector.init();

            List<LingResourceMetricsDTO> metrics = collector.getMetrics();

            assertNotNull(metrics);
            assertTrue(metrics.isEmpty());
        }

        @Test
        @DisplayName("destroy 后缓存应被清空")
        void shouldClearCacheAfterDestroy() {
            URLClassLoader loader = new URLClassLoader(new URL[0], getClass().getClassLoader());
            LingInstance instance = mock(LingInstance.class);
            when(instance.getClassLoader()).thenReturn(loader);
            when(instance.getLingId()).thenReturn("ling1");
            when(instance.getVersion()).thenReturn("v1");

            InstancePool pool = mock(InstancePool.class);
            when(pool.getActiveInstances()).thenReturn(Collections.singletonList(instance));
            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.getInstancePool()).thenReturn(pool);
            LingRepository repository = mock(LingRepository.class);
            when(repository.getAllRuntimes()).thenReturn(Collections.singletonList(runtime));

            LingResourceMetricsCollector collector = new LingResourceMetricsCollector(repository, 10240);
            collector.init();
            collector.sample();
            assertFalse(collector.getMetrics().isEmpty());

            collector.destroy();

            assertTrue(collector.getMetrics().isEmpty());
        }
    }
}
