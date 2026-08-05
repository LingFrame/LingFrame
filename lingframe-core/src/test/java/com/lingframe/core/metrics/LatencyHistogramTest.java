package com.lingframe.core.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LatencyHistogram 测试")
class LatencyHistogramTest {

    private LatencyHistogram histogram;

    @BeforeEach
    void setUp() {
        histogram = new LatencyHistogram();
    }

    @Test
    @DisplayName("无数据时百分位返回 0")
    void shouldReturnZeroWhenEmpty() {
        assertEquals(0, histogram.getP50());
        assertEquals(0, histogram.getP90());
        assertEquals(0, histogram.getP95());
        assertEquals(0, histogram.getP99());
    }

    @Test
    @DisplayName("记录延迟后应正确计算百分位")
    void shouldCalculatePercentiles() {
        for (int i = 1; i <= 100; i++) {
            histogram.record(i);
        }

        assertTrue(histogram.getP50() <= 100);
        assertTrue(histogram.getP90() <= 200);
        assertTrue(histogram.getP99() <= 1000);
    }

    @Test
    @DisplayName("reset 后数据清空")
    void shouldResetCorrectly() {
        histogram.record(100);
        histogram.record(200);

        histogram.reset();

        assertEquals(0, histogram.getP50());
        assertEquals(0, histogram.getP99());
    }

    @Test
    @DisplayName("getBucketBounds 返回桶边界副本")
    void shouldReturnBucketBoundsCopy() {
        long[] bounds = histogram.getBucketBounds();

        assertNotNull(bounds);
        assertTrue(bounds.length > 0);
        // 修改返回值不影响内部状态
        bounds[0] = -1;
        assertNotEquals(-1, histogram.getBucketBounds()[0]);
    }

    @Test
    @DisplayName("getBucketCounts 返回各桶计数")
    void shouldReturnBucketCounts() {
        histogram.record(5);   // <= 10
        histogram.record(50);  // <= 50
        histogram.record(200); // <= 200

        long[] counts = histogram.getBucketCounts();

        assertEquals(3, counts.length - (int) Arrays.stream(counts).filter(c -> c == 0).count());
    }

    @Test
    @DisplayName("超大延迟应落入最后一个桶")
    void shouldHandleVeryLargeLatency() {
        histogram.record(Long.MAX_VALUE);

        assertTrue(histogram.getP99() > 0);
    }
}
