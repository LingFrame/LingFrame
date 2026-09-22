package com.lingframe.core.metrics;

import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("MetricsCollector 测试")
class MetricsCollectorTest {

    private MetricsCollector collector;

    @BeforeEach
    void setUp() {
        collector = new MetricsCollector(null);
    }

    @Test
    @DisplayName("应同时维护 ling 汇总指标与 version 维度指标")
    void shouldKeepLingSummaryAndVersionMetrics() {
        collector.getOrCreate("order-ling").recordSuccess(100);
        collector.getOrCreate("order-ling").recordFailure(200, false);

        collector.getOrCreate("order-ling", "1.0.0").recordSuccess(100);
        collector.getOrCreate("order-ling", "1.1.0").recordFailure(200, true);

        MetricsSnapshot summary = collector.getSnapshot("order-ling");
        Map<String, MetricsSnapshot> versions = collector.getVersionSnapshots("order-ling");

        assertEquals(2, summary.getTotalRequests());
        assertEquals(2, versions.size());
        assertNotNull(versions.get("1.0.0"));
        assertNotNull(versions.get("1.1.0"));
        assertEquals(1, versions.get("1.0.0").getSuccessRequests());
        assertEquals(1, versions.get("1.1.0").getFailedRequests());
        assertEquals(1, versions.get("1.1.0").getTimeoutRequests());
    }

    @Test
    @DisplayName("getOrCreate 空版本号退化为 lingId 维度")
    void shouldFallbackToLingIdWhenVersionEmpty() {
        LingHealthMetrics m1 = collector.getOrCreate("ling-a", null);
        LingHealthMetrics m2 = collector.getOrCreate("ling-a", "");

        LingHealthMetrics mNoVersion = collector.getOrCreate("ling-a");

        assertSame(mNoVersion, m1);
        assertSame(mNoVersion, m2);
    }

    @Test
    @DisplayName("get 不存在时返回 null")
    void shouldReturnNullForNonExistent() {
        assertNull(collector.get("nonexistent"));
    }

    @Test
    @DisplayName("getSnapshot 不存在时返回空快照")
    void shouldReturnEmptySnapshotForNonExistent() {
        MetricsSnapshot snapshot = collector.getSnapshot("nonexistent");

        assertNotNull(snapshot);
        assertEquals("nonexistent", snapshot.getLingId());
        assertEquals(0, snapshot.getTotalRequests());
        assertEquals(MetricsSnapshot.HealthStatus.UNKNOWN, snapshot.getHealthStatus());
    }

    @Test
    @DisplayName("getAllSnapshots 返回所有快照")
    void shouldReturnAllSnapshots() {
        collector.getOrCreate("ling-a").recordSuccess(50);
        collector.getOrCreate("ling-b").recordSuccess(100);

        List<MetricsSnapshot> snapshots = collector.getAllSnapshots();

        assertEquals(2, snapshots.size());
    }

    @Test
    @DisplayName("reset 清零指定 ling 指标")
    void shouldResetSpecificLing() {
        collector.getOrCreate("ling-a").recordSuccess(100);
        collector.getOrCreate("ling-a", "1.0.0").recordSuccess(50);

        collector.reset("ling-a");

        assertEquals(0, collector.getSnapshot("ling-a").getTotalRequests());
    }

    @Test
    @DisplayName("resetAll 清零所有指标")
    void shouldResetAll() {
        collector.getOrCreate("ling-a").recordSuccess(100);
        collector.getOrCreate("ling-b").recordSuccess(200);

        collector.resetAll();

        assertEquals(0, collector.getSnapshot("ling-a").getTotalRequests());
        assertEquals(0, collector.getSnapshot("ling-b").getTotalRequests());
    }

    @Test
    @DisplayName("remove 删除指定 ling 指标")
    void shouldRemoveSpecificLing() {
        collector.getOrCreate("ling-a").recordSuccess(100);

        collector.remove("ling-a");

        assertNull(collector.get("ling-a"));
    }

    @Test
    @DisplayName("updateVersion 更新版本号")
    void shouldUpdateVersion() {
        collector.updateVersion("ling-a", "2.0.0");

        assertEquals("2.0.0", collector.getOrCreate("ling-a").getVersion());
    }

    @Test
    @DisplayName("getAllVersionSnapshots 返回所有版本快照")
    void shouldReturnAllVersionSnapshots() {
        collector.getOrCreate("ling-a", "1.0.0").recordSuccess(50);
        collector.getOrCreate("ling-a", "2.0.0").recordSuccess(100);

        Map<String, Map<String, MetricsSnapshot>> all = collector.getAllVersionSnapshots();

        assertTrue(all.containsKey("ling-a"));
        assertEquals(2, all.get("ling-a").size());
    }

    @Test
    @DisplayName("syncWithRuntime 同步活跃灵元")
    void shouldSyncWithRuntime() {
        LingRepository repo = mock(LingRepository.class);
        LingRuntime rt1 = mock(LingRuntime.class);
        when(rt1.getLingId()).thenReturn("active-ling");
        when(repo.getAllRuntimes()).thenReturn(Collections.singletonList(rt1));

        MetricsCollector syncCollector = new MetricsCollector(repo);
        syncCollector.getOrCreate("active-ling").recordSuccess(100);
        syncCollector.getOrCreate("stale-ling").recordSuccess(100);

        syncCollector.syncWithRuntime();

        assertNull(syncCollector.get("stale-ling"));
        assertNotNull(syncCollector.get("active-ling"));
    }

    @Test
    @DisplayName("syncWithRuntime repository 为 null 时安全返回")
    void shouldHandleNullRepository() {
        MetricsCollector nullRepoCollector = new MetricsCollector(null);
        nullRepoCollector.getOrCreate("ling-a").recordSuccess(100);

        // 不应抛异常
        nullRepoCollector.syncWithRuntime();

        assertNotNull(nullRepoCollector.get("ling-a"));
    }

    @Test
    @DisplayName("getAllMetrics 返回所有指标映射")
    void shouldReturnAllMetrics() {
        collector.getOrCreate("ling-a");
        collector.getOrCreate("ling-b");

        Map<String, LingHealthMetrics> all = collector.getAllMetrics();

        assertEquals(2, all.size());
        assertTrue(all.containsKey("ling-a"));
        assertTrue(all.containsKey("ling-b"));
    }

    @Test
    @DisplayName("getSnapshot 聚合多个版本快照，计算汇总指标")
    void shouldAggregateVersionSnapshotsIntoSummary() {
        collector.getOrCreate("ling-a", "1.0.0").recordSuccess(100);
        collector.getOrCreate("ling-a", "1.0.0").recordSuccess(50);
        collector.getOrCreate("ling-a", "2.0.0").recordFailure(200, true);

        MetricsSnapshot summary = collector.getSnapshot("ling-a");

        assertEquals(3, summary.getTotalRequests());
        assertEquals(2, summary.getSuccessRequests());
        assertEquals(1, summary.getFailedRequests());
        assertEquals(1, summary.getTimeoutRequests());
        assertTrue(summary.getAvgLatencyMs() > 0);
        assertTrue(summary.getMaxLatencyMs() > 0);
        assertTrue(summary.getSuccessRate() > 0);
        assertTrue(summary.getErrorRate() > 0);
        assertTrue(summary.getTimeoutRate() > 0);
    }

    @Test
    @DisplayName("getSnapshot 聚合时健康状态为 UNHEALTHY 时整体为 UNHEALTHY")
    void shouldReturnUnhealthyWhenAnyVersionUnhealthy() {
        LingHealthMetrics healthy = collector.getOrCreate("ling-b", "1.0.0");
        healthy.recordSuccess(10);

        LingHealthMetrics unhealthy = collector.getOrCreate("ling-b", "2.0.0");
        for (int i = 0; i < 100; i++) {
            unhealthy.recordFailure(10, false);
        }

        MetricsSnapshot summary = collector.getSnapshot("ling-b");
        assertNotNull(summary.getHealthStatus());
    }

    @Test
    @DisplayName("getSnapshot 聚合时所有版本 WARNING 则整体为 WARNING")
    void shouldReturnWarningWhenAllVersionsWarning() {
        LingHealthMetrics m = collector.getOrCreate("ling-c", "1.0.0");
        for (int i = 0; i < 50; i++) {
            m.recordSuccess(10);
        }
        for (int i = 0; i < 30; i++) {
            m.recordFailure(10, false);
        }

        MetricsSnapshot summary = collector.getSnapshot("ling-c");
        assertNotNull(summary.getHealthStatus());
    }

    @Test
    @DisplayName("syncWithRuntime 同步时也清理版本指标")
    void shouldSyncVersionMetricsWithRuntime() {
        LingRepository repo = mock(LingRepository.class);
        LingRuntime rt1 = mock(LingRuntime.class);
        when(rt1.getLingId()).thenReturn("active-ling");
        when(repo.getAllRuntimes()).thenReturn(Collections.singletonList(rt1));

        MetricsCollector syncCollector = new MetricsCollector(repo);
        syncCollector.getOrCreate("active-ling", "1.0.0").recordSuccess(100);
        syncCollector.getOrCreate("stale-ling", "2.0.0").recordSuccess(100);

        syncCollector.syncWithRuntime();

        Map<String, MetricsSnapshot> activeVersions = syncCollector.getVersionSnapshots("active-ling");
        assertNotNull(activeVersions);
        Map<String, MetricsSnapshot> staleVersions = syncCollector.getVersionSnapshots("stale-ling");
        assertTrue(staleVersions.isEmpty());
    }

    @Test
    @DisplayName("remove 同时清理版本指标")
    void shouldRemoveVersionMetrics() {
        collector.getOrCreate("ling-a", "1.0.0").recordSuccess(100);
        collector.getOrCreate("ling-a", "2.0.0").recordSuccess(200);

        collector.remove("ling-a");

        assertNull(collector.get("ling-a"));
        assertTrue(collector.getVersionSnapshots("ling-a").isEmpty());
    }

    @Test
    @DisplayName("reset 同时清零版本指标")
    void shouldResetVersionMetrics() {
        collector.getOrCreate("ling-a", "1.0.0").recordSuccess(100);
        collector.getOrCreate("ling-a", "2.0.0").recordSuccess(200);

        collector.reset("ling-a");

        Map<String, MetricsSnapshot> versions = collector.getVersionSnapshots("ling-a");
        for (MetricsSnapshot s : versions.values()) {
            assertEquals(0, s.getTotalRequests());
        }
    }

    @Test
    @DisplayName("getVersionSnapshots 不存在时返回空 Map")
    void shouldReturnEmptyMapForNonExistentVersionSnapshots() {
        Map<String, MetricsSnapshot> versions = collector.getVersionSnapshots("nonexistent");
        assertNotNull(versions);
        assertTrue(versions.isEmpty());
    }
}
