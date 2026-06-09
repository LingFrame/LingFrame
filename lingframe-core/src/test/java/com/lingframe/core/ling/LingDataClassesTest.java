package com.lingframe.core.ling;

import com.lingframe.core.spi.LeakRiskLevel;
import com.lingframe.core.spi.LeakRiskReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Ling 数据类测试")
class LingDataClassesTest {

    @Nested
    @DisplayName("LingUninstallResult 测试")
    class LingUninstallResultTests {

        @Test
        @DisplayName("triggered 工厂方法")
        void shouldCreateTriggeredResult() {
            LeakRiskReport report = LeakRiskReport.noRisk("ling-a", "1.0", "ok", null, "c");
            LingUninstallResult result = LingUninstallResult.triggered("ling-a", "1.0",
                    Arrays.asList(report));

            assertEquals("ling-a", result.getLingId());
            assertEquals("1.0", result.getVersion());
            assertTrue(result.isUninstallTriggered());
            assertEquals(LeakRiskLevel.NO_RISK, result.getOverallRiskLevel());
            assertEquals(1, result.getReports().size());
        }

        @Test
        @DisplayName("notTriggered 工厂方法")
        void shouldCreateNotTriggeredResult() {
            LingUninstallResult result = LingUninstallResult.notTriggered("ling-b", "2.0", null);

            assertFalse(result.isUninstallTriggered());
            assertTrue(result.getReports().isEmpty());
            assertEquals(LeakRiskLevel.NO_RISK, result.getOverallRiskLevel());
        }

        @Test
        @DisplayName("reports 中最高风险等级聚合")
        void shouldAggregateRiskLevel() {
            LeakRiskReport r1 = LeakRiskReport.noRisk("ling", "1.0", "ok", null, "c");
            LeakRiskReport r2 = LeakRiskReport.riskDetected("ling", "1.0", "leak", null, "c");
            LingUninstallResult result = LingUninstallResult.triggered("ling", "1.0",
                    Arrays.asList(r1, r2));

            assertEquals(LeakRiskLevel.RISK_DETECTED, result.getOverallRiskLevel());
        }

        @Test
        @DisplayName("reports 列表不可变")
        void shouldReturnImmutableReports() {
            LeakRiskReport report = LeakRiskReport.noRisk("ling", "1.0", "ok", null, "c");
            LingUninstallResult result = LingUninstallResult.triggered("ling", "1.0",
                    Arrays.asList(report));
            assertThrows(UnsupportedOperationException.class, () -> result.getReports().add(report));
        }
    }

    @Nested
    @DisplayName("ActiveInvocationSnapshot 测试")
    class ActiveInvocationSnapshotTests {

        @Test
        @DisplayName("构造和 getter")
        void shouldCreateSnapshot() {
            ActiveInvocationSnapshot snapshot = new ActiveInvocationSnapshot(
                    "trace-1", "ling:svc", "doWork", "caller-ling",
                    "res-1", "1.0.0", 1000L, 42L, "worker-thread");

            assertEquals("trace-1", snapshot.getTraceId());
            assertEquals("ling:svc", snapshot.getServiceFQSID());
            assertEquals("doWork", snapshot.getMethodName());
            assertEquals("caller-ling", snapshot.getCallerLingId());
            assertEquals("res-1", snapshot.getResourceId());
            assertEquals("1.0.0", snapshot.getInstanceVersion());
            assertEquals(1000L, snapshot.getStartTimeMillis());
            assertEquals(42L, snapshot.getThreadId());
            assertEquals("worker-thread", snapshot.getThreadName());
        }

        @Test
        @DisplayName("ageMillis 计算正确")
        void shouldCalculateAge() {
            ActiveInvocationSnapshot snapshot = new ActiveInvocationSnapshot(
                    "t", "s", "m", "c", "r", "v", 1000L, 1L, "th");
            assertEquals(500L, snapshot.ageMillis(1500L));
            assertEquals(0L, snapshot.ageMillis(500L));
        }

        @Test
        @DisplayName("toSummary 包含关键信息")
        void shouldContainKeyInfoInSummary() {
            ActiveInvocationSnapshot snapshot = new ActiveInvocationSnapshot(
                    "trace-1", "ling:svc", "doWork", "caller",
                    "res-1", "2.0", 1000L, 99L, "thread-1");
            String summary = snapshot.toSummary(2000L);

            assertTrue(summary.contains("trace-1"));
            assertTrue(summary.contains("ling:svc"));
            assertTrue(summary.contains("doWork"));
            assertTrue(summary.contains("1000"));
        }

        @Test
        @DisplayName("toSummary 空值显示为 -")
        void shouldDisplayDashForEmptyOrNull() {
            ActiveInvocationSnapshot snapshot = new ActiveInvocationSnapshot(
                    null, "", null, null, null, null, 1000L, 1L, null);
            String summary = snapshot.toSummary(2000L);

            assertTrue(summary.contains("-"));
        }
    }
}
