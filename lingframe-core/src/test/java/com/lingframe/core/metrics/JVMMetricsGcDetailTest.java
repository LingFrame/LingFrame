package com.lingframe.core.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JVMMetricsGcDetailTest {

    @Test
    @DisplayName("collect 应返回按收集器分离的 GC 详情")
    void shouldCollectGcDetails() {
        JVMMetrics metrics = JVMMetrics.collect();

        List<GcDetail> details = metrics.getGcDetails();
        assertNotNull(details, "gcDetails 不应为 null");
        assertFalse(details.isEmpty(), "至少应有一个 GC 收集器");

        for (GcDetail detail : details) {
            assertNotNull(detail.getName(), "收集器名称不应为 null");
            assertTrue(detail.getCount() >= 0, "收集次数应非负");
            assertTrue(detail.getTimeMs() >= 0, "收集耗时应非负");
        }

        // 验证总计与分离统计一致
        long totalCount = details.stream().mapToLong(GcDetail::getCount).sum();
        long totalTime = details.stream().mapToLong(GcDetail::getTimeMs).sum();
        assertEquals(metrics.getGcCount(), totalCount, "GC 总次数应与分离统计之和一致");
        assertEquals(metrics.getGcTimeMs(), totalTime, "GC 总耗时应与分离统计之和一致");
    }
}
