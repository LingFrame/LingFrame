package com.lingframe.dashboard.service;

import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.metrics.ProviderMetricsCollector;
import com.lingframe.dashboard.dto.ContractMigrationProgressDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * MigrationProgressService 单元测试。
 * 覆盖：流量分布聚合、灵核 stale 识别、空数据处理。
 * <p>
 * 去身份化后 ProviderMetricsCollector 按 contractId × lingId 二维统计，
 * 灵核识别通过 LingCoreConstants.LINGCORE_LING_ID 常量比较 lingId 实现，
 * 仅 Dashboard 运维视图用，不参与路由决策。
 */
@DisplayName("MigrationProgressService 单元测试")
class MigrationProgressServiceTest {

    private ProviderMetricsCollector collector;
    private MigrationProgressService service;

    @BeforeEach
    void setUp() {
        collector = new ProviderMetricsCollector();
        LingServiceRegistry registry = mock(LingServiceRegistry.class);
        service = new MigrationProgressService(collector, registry);
    }

    @Nested
    @DisplayName("流量分布聚合")
    class TrafficDistribution {

        @Test
        @DisplayName("灵核和灵元调用量正确聚合")
        void coreAndLingAggregated() {
            // 灵核 lingcore-app 30 次，灵元 user-ling 70 次
            for (int i = 0; i < 30; i++) {
                collector.recordInvocation("svc-a", "lingcore-app", true, 10);
            }
            for (int i = 0; i < 70; i++) {
                collector.recordInvocation("svc-a", "user-ling", true, 20);
            }

            ContractMigrationProgressDTO dto = service.getProgress("svc-a");

            assertEquals("svc-a", dto.getContractId());
            assertEquals(30, dto.getCoreInvocations());
            assertEquals(70, dto.getLingInvocations());
            assertEquals(100, dto.getTotalInvocations());
            assertEquals(0.3, dto.getCoreTrafficRatio(), 0.001);
            assertEquals(0.7, dto.getLingTrafficRatio(), 0.001);
            assertEquals(10.0, dto.getCoreAvgDurationMs(), 0.001);
            assertEquals(20.0, dto.getLingAvgDurationMs(), 0.001);
            assertFalse(dto.isCoreStale());
            assertEquals(2, dto.getProviderCount());
        }

        @Test
        @DisplayName("灵核 0 调用且灵元有调用时 coreStale=true")
        void coreStaleWhenZeroCoreInvocations() {
            collector.recordInvocation("svc-a", "user-ling", true, 10);

            ContractMigrationProgressDTO dto = service.getProgress("svc-a");

            assertEquals(0, dto.getCoreInvocations());
            assertEquals(1, dto.getLingInvocations());
            assertTrue(dto.isCoreStale());
        }

        @Test
        @DisplayName("灵核和灵元都 0 调用时 coreStale=false")
        void notStaleWhenNoInvocationsAtAll() {
            ContractMigrationProgressDTO dto = service.getProgress("svc-a");

            assertEquals(0, dto.getTotalInvocations());
            assertFalse(dto.isCoreStale());
        }
    }

    @Nested
    @DisplayName("批量查询")
    class BatchQuery {

        @Test
        @DisplayName("getAllProgress 返回所有契约并按灵核占比升序")
        void getAllProgressSortedByCoreRatio() {
            // svc-a：灵核 50%
            collector.recordInvocation("svc-a", "lingcore-app", true, 10);
            collector.recordInvocation("svc-a", "user-ling", true, 10);

            // svc-b：灵核 0%（stale）
            collector.recordInvocation("svc-b", "user-ling", true, 10);

            // svc-c：灵核 100%
            collector.recordInvocation("svc-c", "lingcore-app", true, 10);

            List<ContractMigrationProgressDTO> list = service.getAllProgress();

            assertEquals(3, list.size());
            // 灵核 0% 排最前
            assertEquals("svc-b", list.get(0).getContractId());
            assertTrue(list.get(0).isCoreStale());
            // 灵核 50% 排中间
            assertEquals("svc-a", list.get(1).getContractId());
            // 灵核 100% 排最后
            assertEquals("svc-c", list.get(2).getContractId());
        }
    }

    @Nested
    @DisplayName("stale 契约识别")
    class StaleContracts {

        @Test
        @DisplayName("getStaleCoreContracts 返回灵核 0 调用的契约")
        void staleContractsReturned() {
            collector.recordInvocation("svc-a", "lingcore-app", true, 10);
            collector.recordInvocation("svc-a", "user-ling", true, 10);

            collector.recordInvocation("svc-b", "user-ling", true, 10);

            List<String> stale = service.getStaleCoreContracts();

            assertEquals(1, stale.size());
            assertTrue(stale.contains("svc-b"));
            assertFalse(stale.contains("svc-a"));
        }
    }
}
