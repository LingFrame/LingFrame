package com.lingframe.core.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UnifiedMetrics 测试")
class UnifiedMetricsTest {

    @Test
    @DisplayName("create 返回有效的统一指标")
    void shouldCreateUnifiedMetrics() {
        UnifiedMetrics metrics = UnifiedMetrics.create();

        assertNotNull(metrics);
        assertTrue(metrics.getTimestamp() > 0);
        assertNotNull(metrics.getJvmMetrics());
        assertNotNull(metrics.getSystemMetrics());
    }

    @Test
    @DisplayName("SystemMetrics 包含有效数据")
    void shouldContainValidSystemMetrics() {
        UnifiedMetrics metrics = UnifiedMetrics.create();
        UnifiedMetrics.SystemMetrics sys = metrics.getSystemMetrics();

        assertTrue(sys.getUptime() > 0);
        assertTrue(sys.getAvailableProcessors() > 0);
    }
}
