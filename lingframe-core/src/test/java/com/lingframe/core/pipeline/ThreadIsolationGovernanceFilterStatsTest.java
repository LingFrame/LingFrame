package com.lingframe.core.pipeline;

import com.lingframe.core.spi.ThreadPoolStatsProvider.ThreadPoolStats;
import com.lingframe.core.ling.LingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 4, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>());
        Field executorsField = ThreadIsolationGovernanceFilter.class
                .getDeclaredField("executors");
        executorsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> executors = (Map<String, Object>) executorsField.get(filter);

        Constructor<?> holderCtor = Class
                .forName("com.lingframe.core.pipeline.ThreadIsolationGovernanceFilter$ExecutorHolder")
                .getDeclaredConstructor(int.class, ThreadPoolExecutor.class);
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
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 2, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>());
        pool.shutdownNow();

        Field executorsField = ThreadIsolationGovernanceFilter.class
                .getDeclaredField("executors");
        executorsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> executors = (Map<String, Object>) executorsField.get(filter);

        Constructor<?> holderCtor = Class
                .forName("com.lingframe.core.pipeline.ThreadIsolationGovernanceFilter$ExecutorHolder")
                .getDeclaredConstructor(int.class, ThreadPoolExecutor.class);
        holderCtor.setAccessible(true);
        executors.put("dead-ling", holderCtor.newInstance(2, pool));

        assertTrue(filter.getThreadPoolStats().isEmpty(), "已关闭线程池不应出现在结果中");
    }
}
