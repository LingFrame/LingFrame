package com.lingframe.core.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

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

    @Test
    @DisplayName("create() 不含灵元指标（lingMetrics 保持 null）")
    void createWithoutCollector_hasNoLingMetrics() {
        UnifiedMetrics metrics = UnifiedMetrics.create();
        assertNull(metrics.getLingMetrics(),
                "无 MetricsCollector 时 lingMetrics 应保持 null，供 Dashboard 按需聚灵元指标");
    }

    @Test
    @DisplayName("create(MetricsCollector) 聚合所有灵元快照到 lingMetrics")
    void createWithCollector_aggregatesLingSnapshots() {
        MetricsSnapshot ling1 = new MetricsSnapshot();
        ling1.setLingId("ling-1");
        MetricsSnapshot ling2 = new MetricsSnapshot();
        ling2.setLingId("ling-2");
        MetricsCollector collector = mock(MetricsCollector.class);
        doReturn(Arrays.asList(ling1, ling2)).when(collector).getAllSnapshots();

        UnifiedMetrics metrics = UnifiedMetrics.create(collector);

        assertNotNull(metrics.getLingMetrics());
        assertEquals(2, metrics.getLingMetrics().size());
        assertTrue(metrics.getLingMetrics().containsKey("ling-1"));
        assertTrue(metrics.getLingMetrics().containsKey("ling-2"));
        assertSame(ling1, metrics.getLingMetrics().get("ling-1"));
    }

    @Test
    @DisplayName("create(null) 与 create() 等价，lingMetrics 保持 null")
    void createWithNullCollector_equivalentToNoArg() {
        UnifiedMetrics metrics = UnifiedMetrics.create(null);
        assertNotNull(metrics.getJvmMetrics());
        assertNull(metrics.getLingMetrics());
    }

    @Test
    @DisplayName("create(MetricsCollector) 跳过 lingId 为 null 的损坏快照")
    void createWithCollector_skipsCorruptSnapshots() {
        MetricsSnapshot valid = new MetricsSnapshot();
        valid.setLingId("valid");
        MetricsSnapshot corruptNoLingId = new MetricsSnapshot(); // lingId 保持 null
        MetricsCollector collector = mock(MetricsCollector.class);
        doReturn(Arrays.asList(valid, corruptNoLingId, null)).when(collector).getAllSnapshots();

        UnifiedMetrics metrics = UnifiedMetrics.create(collector);

        assertNotNull(metrics.getLingMetrics());
        assertEquals(1, metrics.getLingMetrics().size(),
                "lingId 为 null 的损坏快照与 null 元素应被跳过，不污染聚合结果");
        assertTrue(metrics.getLingMetrics().containsKey("valid"));
    }

    @Test
    @DisplayName("create(MetricsCollector) 空快照列表时 lingMetrics 保持 null")
    void createWithCollector_emptySnapshots_keepsNull() {
        MetricsCollector collector = mock(MetricsCollector.class);
        doReturn(Collections.emptyList()).when(collector).getAllSnapshots();

        UnifiedMetrics metrics = UnifiedMetrics.create(collector);

        assertNotNull(metrics.getJvmMetrics());
        assertNull(metrics.getLingMetrics(),
                "空快照列表不应产生空 Map，保持 null 避免下游误判为「已聚合」");
    }
}
