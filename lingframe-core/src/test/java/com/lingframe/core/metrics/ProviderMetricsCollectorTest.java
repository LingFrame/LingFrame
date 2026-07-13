package com.lingframe.core.metrics;

import com.lingframe.core.ling.ProviderKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProviderMetricsCollector 单元测试。
 * 覆盖：记录调用、按契约查询、evict 清理、空值容错。
 */
@DisplayName("ProviderMetricsCollector 单元测试")
class ProviderMetricsCollectorTest {

    @Nested
    @DisplayName("记录与查询")
    class RecordAndQuery {

        @Test
        @DisplayName("记录后可按契约查询到")
        void recordThenQueryByContract() {
            ProviderMetricsCollector collector = new ProviderMetricsCollector();

            collector.recordInvocation("svc-a", "lingcore-app", ProviderKind.CORE, true, 10);
            collector.recordInvocation("svc-a", "lingcore-app", ProviderKind.CORE, true, 20);
            collector.recordInvocation("svc-a", "user-ling", ProviderKind.LING, false, 5);

            List<ProviderMetricsCollector.ProviderStats> stats = collector.getStatsByContract("svc-a");

            assertEquals(2, stats.size());
            // 灵核：2 次调用，全部成功，总延迟 30ms
            ProviderMetricsCollector.ProviderStats coreStats = stats.stream()
                    .filter(s -> s.getKind() == ProviderKind.CORE)
                    .findFirst().orElse(null);
            assertNotNull(coreStats);
            assertEquals(2, coreStats.getTotalInvocations());
            assertEquals(2, coreStats.getSuccessCount());
            assertEquals(0, coreStats.getFailureCount());
            assertEquals(30, coreStats.getTotalDurationMs());
            assertEquals(15.0, coreStats.getAvgDurationMs(), 0.001);

            // 灵元：1 次调用，失败，延迟 5ms
            ProviderMetricsCollector.ProviderStats lingStats = stats.stream()
                    .filter(s -> s.getKind() == ProviderKind.LING)
                    .findFirst().orElse(null);
            assertNotNull(lingStats);
            assertEquals(1, lingStats.getTotalInvocations());
            assertEquals(0, lingStats.getSuccessCount());
            assertEquals(1, lingStats.getFailureCount());
        }

        @Test
        @DisplayName("getContractIds 返回所有有调用记录的契约")
        void getContractIdsReturnsAll() {
            ProviderMetricsCollector collector = new ProviderMetricsCollector();
            collector.recordInvocation("svc-a", "lingcore-app", ProviderKind.CORE, true, 10);
            collector.recordInvocation("svc-b", "user-ling", ProviderKind.LING, true, 10);

            assertEquals(2, collector.getContractIds().size());
            assertTrue(collector.getContractIds().contains("svc-a"));
            assertTrue(collector.getContractIds().contains("svc-b"));
        }

        @Test
        @DisplayName("未记录的契约返回空列表")
        void emptyForUnknownContract() {
            ProviderMetricsCollector collector = new ProviderMetricsCollector();
            assertTrue(collector.getStatsByContract("unknown").isEmpty());
            assertTrue(collector.getContractIds().isEmpty());
        }
    }

    @Nested
    @DisplayName("空值容错")
    class NullSafety {

        @Test
        @DisplayName("null 参数静默跳过")
        void nullArgsSkipped() {
            ProviderMetricsCollector collector = new ProviderMetricsCollector();
            collector.recordInvocation(null, "ling-1", ProviderKind.CORE, true, 10);
            collector.recordInvocation("svc-a", null, ProviderKind.CORE, true, 10);
            collector.recordInvocation("svc-a", "ling-1", null, true, 10);

            assertTrue(collector.getContractIds().isEmpty());
        }

        @Test
        @DisplayName("null contractId 查询返回空列表")
        void nullContractIdQueryReturnsEmpty() {
            ProviderMetricsCollector collector = new ProviderMetricsCollector();
            assertTrue(collector.getStatsByContract(null).isEmpty());
        }
    }

    @Nested
    @DisplayName("evict 清理")
    class Evict {

        @Test
        @DisplayName("evict 移除指定 lingId 的所有指标")
        void evictRemovesLingId() {
            ProviderMetricsCollector collector = new ProviderMetricsCollector();
            collector.recordInvocation("svc-a", "lingcore-app", ProviderKind.CORE, true, 10);
            collector.recordInvocation("svc-a", "user-ling", ProviderKind.LING, true, 10);
            collector.recordInvocation("svc-b", "user-ling", ProviderKind.LING, true, 10);

            collector.evict("user-ling");

            // svc-a 只剩灵核
            List<ProviderMetricsCollector.ProviderStats> svcA = collector.getStatsByContract("svc-a");
            assertEquals(1, svcA.size());
            assertEquals(ProviderKind.CORE, svcA.get(0).getKind());

            // svc-b 完全清空
            assertTrue(collector.getStatsByContract("svc-b").isEmpty());
        }

        @Test
        @DisplayName("evict null 安全")
        void evictNullSafe() {
            ProviderMetricsCollector collector = new ProviderMetricsCollector();
            collector.recordInvocation("svc-a", "lingcore-app", ProviderKind.CORE, true, 10);

            collector.evict(null); // 不抛异常

            assertEquals(1, collector.getStatsByContract("svc-a").size());
        }
    }

    @Nested
    @DisplayName("并发安全")
    class Concurrency {

        @Test
        @DisplayName("多线程并发记录不丢失")
        void concurrentRecordsNoLoss() throws InterruptedException {
            ProviderMetricsCollector collector = new ProviderMetricsCollector();
            int threads = 4;
            int perThread = 100;

            Thread[] ts = new Thread[threads];
            for (int i = 0; i < threads; i++) {
                ts[i] = new Thread(() -> {
                    for (int j = 0; j < perThread; j++) {
                        collector.recordInvocation("svc-a", "lingcore-app",
                                ProviderKind.CORE, true, 1);
                    }
                });
                ts[i].start();
            }
            for (Thread t : ts) {
                t.join();
            }

            List<ProviderMetricsCollector.ProviderStats> stats = collector.getStatsByContract("svc-a");
            assertEquals(1, stats.size());
            assertEquals(threads * perThread, stats.get(0).getTotalInvocations());
        }
    }
}
