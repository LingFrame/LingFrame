package com.lingframe.core.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JVMMetrics 测试")
class JVMMetricsTest {

    @Test
    @DisplayName("collect 应返回完整的 JVM 指标")
    void shouldCollectAllMetrics() {
        JVMMetrics metrics = JVMMetrics.collect();

        assertNotNull(metrics);
        assertTrue(metrics.getTimestamp() > 0);

        // 内存指标
        assertTrue(metrics.getHeapUsedMB() >= 0);
        assertTrue(metrics.getHeapMaxMB() > 0);
        assertTrue(metrics.getHeapUsagePercent() >= 0);
        assertTrue(metrics.getTotalMemoryMB() > 0);
        assertTrue(metrics.getUsedMemoryMB() > 0);

        // 类加载指标
        assertTrue(metrics.getLoadedClassCount() > 0);
        assertTrue(metrics.getTotalLoadedClassCount() > 0);

        // 线程指标
        assertTrue(metrics.getThreadCount() > 0);
        assertTrue(metrics.getDaemonThreadCount() > 0);
        assertTrue(metrics.getPeakThreadCount() > 0);

        // GC 指标
        assertTrue(metrics.getGcCount() >= 0);
        assertTrue(metrics.getGcTimeMs() >= 0);

        // CPU 指标
        assertTrue(metrics.getAvailableProcessors() > 0);
    }

    @Test
    @DisplayName("Metaspace 指标应正常采集")
    void shouldCollectMetaspaceMetrics() {
        JVMMetrics metrics = JVMMetrics.collect();

        assertTrue(metrics.getMetaspaceUsedKB() >= 0);
        assertTrue(metrics.getMetaspaceUsagePercent() >= 0);
    }

    @Test
    @DisplayName("多次采集指标应递增或稳定")
    void shouldCollectConsistently() {
        JVMMetrics first = JVMMetrics.collect();
        JVMMetrics second = JVMMetrics.collect();

        assertTrue(second.getTimestamp() >= first.getTimestamp());
        // 类加载数应只增不减
        assertTrue(second.getTotalLoadedClassCount() >= first.getTotalLoadedClassCount());
    }
}
