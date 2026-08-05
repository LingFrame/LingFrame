package com.lingframe.core.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SPI 类测试")
class SpiClassesTest {

    @Nested
    @DisplayName("LeakRiskReport 测试")
    class LeakRiskReportTests {

        @Test
        @DisplayName("noRisk 工厂方法")
        void shouldCreateNoRiskReport() {
            LeakRiskReport report = LeakRiskReport.noRisk("ling-a", "1.0", "ok",
                    Arrays.asList("detail1"), "checker");

            assertEquals("ling-a", report.getLingId());
            assertEquals("1.0", report.getVersion());
            assertEquals(LeakRiskLevel.NO_RISK, report.getLevel());
            assertEquals("ok", report.getSummary());
            assertEquals(1, report.getDetails().size());
            assertEquals("checker", report.getChecker());
            assertTrue(report.getTimestamp() > 0);
        }

        @Test
        @DisplayName("riskDetected 工厂方法")
        void shouldCreateRiskDetectedReport() {
            LeakRiskReport report = LeakRiskReport.riskDetected("ling-b", "2.0", "leak",
                    Arrays.asList("d1", "d2"), "my-checker");

            assertEquals(LeakRiskLevel.RISK_DETECTED, report.getLevel());
            assertEquals(2, report.getDetails().size());
        }

        @Test
        @DisplayName("checkFailed 工厂方法")
        void shouldCreateCheckFailedReport() {
            LeakRiskReport report = LeakRiskReport.checkFailed("ling-c", "3.0", "fail",
                    null, "checker");

            assertEquals(LeakRiskLevel.CHECK_FAILED, report.getLevel());
            assertTrue(report.getDetails().isEmpty());
        }

        @Test
        @DisplayName("null level 默认为 NO_RISK")
        void shouldDefaultToNoRiskForNullLevel() {
            LeakRiskReport report = new LeakRiskReport("ling", "1.0", null, "s",
                    null, "c", 0L);
            assertEquals(LeakRiskLevel.NO_RISK, report.getLevel());
        }

        @Test
        @DisplayName("details 列表不可变")
        void shouldReturnImmutableDetails() {
            LeakRiskReport report = LeakRiskReport.noRisk("ling", "1.0", "ok",
                    Arrays.asList("a"), "c");
            assertThrows(UnsupportedOperationException.class, () -> report.getDetails().add("b"));
        }
    }

    @Nested
    @DisplayName("LeakRiskLevel 测试")
    class LeakRiskLevelTests {

        @Test
        @DisplayName("max 取较高等级")
        void shouldReturnHigherSeverity() {
            assertEquals(LeakRiskLevel.RISK_DETECTED,
                    LeakRiskLevel.max(LeakRiskLevel.NO_RISK, LeakRiskLevel.RISK_DETECTED));
            assertEquals(LeakRiskLevel.CHECK_FAILED,
                    LeakRiskLevel.max(LeakRiskLevel.CHECK_FAILED, LeakRiskLevel.NO_RISK));
        }

        @Test
        @DisplayName("max null 参数默认为 NO_RISK")
        void shouldHandleNullInMax() {
            assertEquals(LeakRiskLevel.RISK_DETECTED,
                    LeakRiskLevel.max(null, LeakRiskLevel.RISK_DETECTED));
            assertEquals(LeakRiskLevel.NO_RISK,
                    LeakRiskLevel.max(null, null));
        }

        @Test
        @DisplayName("max 相同等级返回自身")
        void shouldReturnSameForEqualSeverity() {
            assertEquals(LeakRiskLevel.CHECK_FAILED,
                    LeakRiskLevel.max(LeakRiskLevel.CHECK_FAILED, LeakRiskLevel.CHECK_FAILED));
        }
    }
}
