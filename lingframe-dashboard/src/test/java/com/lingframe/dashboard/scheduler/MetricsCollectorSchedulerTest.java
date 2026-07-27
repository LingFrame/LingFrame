package com.lingframe.dashboard.scheduler;

import com.lingframe.core.metrics.JVMMetrics;
import com.lingframe.dashboard.storage.MetricsStorage;
import com.lingframe.dashboard.storage.StorageProperties;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Assertions;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 定时采集调度器单元测试
 * 覆盖：start/stop 生命周期、interval≤0 默认值、collectAndSave 正常与异常隔离
 */
class MetricsCollectorSchedulerTest {

    private MetricsStorage mockStorage() {
        return mock(MetricsStorage.class);
    }

    private StorageProperties propsWithInterval(int seconds) {
        StorageProperties p = mock(StorageProperties.class);
        when(p.getMetricsCollectIntervalSeconds()).thenReturn(seconds);
        return p;
    }

    private ScheduledExecutorService schedulerField(MetricsCollectorScheduler s) throws Exception {
        Field f = MetricsCollectorScheduler.class.getDeclaredField("scheduler");
        f.setAccessible(true);
        return (ScheduledExecutorService) f.get(s);
    }

    private void invokeCollectAndSave(MetricsCollectorScheduler s) throws Exception {
        Method m = MetricsCollectorScheduler.class.getDeclaredMethod("collectAndSave");
        m.setAccessible(true);
        m.invoke(s);
    }

    @Test
    @DisplayName("start 应创建 scheduler 并按配置 interval 调度")
    void shouldStartWithConfiguredInterval() throws Exception {
        MetricsCollectorScheduler s = new MetricsCollectorScheduler(mockStorage(), propsWithInterval(30));
        assertNull(schedulerField(s));
        s.start();
        assertNotNull(schedulerField(s), "start 后 scheduler 应非空");
        s.stop();
    }

    @Test
    @DisplayName("interval≤0 时应回退默认 30 秒，不抛异常")
    void shouldFallbackToDefaultWhenIntervalNonPositive() throws Exception {
        MetricsCollectorScheduler s = new MetricsCollectorScheduler(mockStorage(), propsWithInterval(0));
        s.start();
        assertNotNull(schedulerField(s));
        s.stop();

        MetricsCollectorScheduler s2 = new MetricsCollectorScheduler(mockStorage(), propsWithInterval(-5));
        s2.start();
        assertNotNull(schedulerField(s2));
        s2.stop();
    }

    @Test
    @DisplayName("stop 应关闭 scheduler")
    void shouldShutdownOnStop() throws Exception {
        MetricsCollectorScheduler s = new MetricsCollectorScheduler(mockStorage(), propsWithInterval(30));
        s.start();
        assertFalse(schedulerField(s).isShutdown());
        s.stop();
        assertTrue(schedulerField(s).isShutdown(), "stop 后 scheduler 应已关闭");
    }

    @Test
    @DisplayName("未 start 直接 stop 不应抛 NPE")
    void shouldNotThrowWhenStopWithoutStart() {
        MetricsCollectorScheduler s = new MetricsCollectorScheduler(mockStorage(), propsWithInterval(30));
        s.stop();
        s.stop();
    }

    @Test
    @DisplayName("collectAndSave 正常时应调用 metricsStorage.saveSnapshot")
    void shouldSaveSnapshotOnCollect() throws Exception {
        MetricsStorage storage = mockStorage();
        MetricsCollectorScheduler s = new MetricsCollectorScheduler(storage, propsWithInterval(30));
        invokeCollectAndSave(s);
        verify(storage).saveSnapshot(ArgumentMatchers.any(JVMMetrics.class));
    }

    @Test
    @DisplayName("collectAndSave 中 saveSnapshot 抛异常应被吞掉，不向上传播")
    void shouldSwallowExceptionInCollectAndSave() throws Exception {
        MetricsStorage storage = mockStorage();
        doThrow(new RuntimeException("db down")).when(storage).saveSnapshot(ArgumentMatchers.any());
        MetricsCollectorScheduler s = new MetricsCollectorScheduler(storage, propsWithInterval(30));
        invokeCollectAndSave(s);
        verify(storage).saveSnapshot(ArgumentMatchers.any());
    }

    private static void assertNull(Object o) {
        Assertions.assertNull(o);
    }
}
