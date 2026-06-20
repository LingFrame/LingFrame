package com.lingframe.core.pipeline;

import com.lingframe.core.spi.ThreadPoolStatsProvider.ThreadPoolStats;
import com.lingframe.core.ling.LingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DisplayName("ThreadIsolationGovernanceFilter 线程池状态查询")
class ThreadIsolationGovernanceFilterStatsTest {

    @Test
    @DisplayName("无线程池时应返回空列表")
    void shouldReturnEmptyWhenNoExecutors() {
        ThreadIsolationGovernanceFilter filter = new ThreadIsolationGovernanceFilter(mock(LingRepository.class));
        assertTrue(filter.getThreadPoolStats().isEmpty());
    }

    @Test
    @DisplayName("有活跃线程池时应返回状态行")
    void shouldReturnStatsForActivePool() throws Exception {
        ThreadIsolationGovernanceFilter filter = new ThreadIsolationGovernanceFilter(mock(LingRepository.class));
        // 通过反射创建一个测试用 ExecutorHolder 并放入 executors map
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(
                1, 4, 60, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>());
        java.lang.reflect.Field executorsField = ThreadIsolationGovernanceFilter.class
                .getDeclaredField("executors");
        executorsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> executors = (java.util.Map<String, Object>) executorsField.get(filter);

        java.lang.reflect.Constructor<?> holderCtor = Class
                .forName("com.lingframe.core.pipeline.ThreadIsolationGovernanceFilter$ExecutorHolder")
                .getDeclaredConstructor(int.class, java.util.concurrent.ThreadPoolExecutor.class);
        holderCtor.setAccessible(true);
        executors.put("test-ling", holderCtor.newInstance(4, pool));

        List<ThreadPoolStats> stats = filter.getThreadPoolStats();
        assertEquals(1, stats.size());
        assertEquals("test-ling", stats.get(0).getLingId());
        assertEquals(4, stats.get(0).getMaxThreads());

        pool.shutdown();
    }

    @Test
    @DisplayName("已关闭的线程池应被过滤")
    void shouldSkipShutdownPool() throws Exception {
        ThreadIsolationGovernanceFilter filter = new ThreadIsolationGovernanceFilter(mock(LingRepository.class));
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(
                1, 2, 60, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>());
        pool.shutdownNow();

        java.lang.reflect.Field executorsField = ThreadIsolationGovernanceFilter.class
                .getDeclaredField("executors");
        executorsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> executors = (java.util.Map<String, Object>) executorsField.get(filter);

        java.lang.reflect.Constructor<?> holderCtor = Class
                .forName("com.lingframe.core.pipeline.ThreadIsolationGovernanceFilter$ExecutorHolder")
                .getDeclaredConstructor(int.class, java.util.concurrent.ThreadPoolExecutor.class);
        holderCtor.setAccessible(true);
        executors.put("dead-ling", holderCtor.newInstance(2, pool));

        assertTrue(filter.getThreadPoolStats().isEmpty(), "已关闭线程池不应出现在结果中");
    }
}
