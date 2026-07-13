package com.lingframe.core.router;

import com.lingframe.core.ling.ProviderDescriptor;
import com.lingframe.core.ling.ProviderKind;
import com.lingframe.core.pipeline.InvocationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * ProviderWeightRouter 测试。
 * 覆盖：单 provider 直选、默认权重（CORE=100/LING=0）、Dashboard 覆盖、权重分布。
 */
@DisplayName("ProviderWeightRouter 测试")
class ProviderWeightRouterTest {

    private ProviderWeightRouter router;
    private InvocationContext ctx;

    @BeforeEach
    void setUp() {
        router = new ProviderWeightRouter();
        ctx = InvocationContext.obtain();
    }

    @AfterEach
    void tearDown() {
        ctx.recycle();
    }

    // ==================== 单 provider 直选 ====================

    @Test
    @DisplayName("候选为空时返回 null")
    void emptyCandidatesReturnNull() {
        assertNull(router.selectProvider(Collections.emptyList(), ctx));
        assertNull(router.selectProvider(null, ctx));
    }

    @Test
    @DisplayName("单 provider 时直接选中，不检查权重")
    void singleCandidateSelectedDirectly() {
        ProviderDescriptor core = new ProviderDescriptor("svc", "ling-core", ProviderKind.CORE, 0);
        assertSame(core, router.selectProvider(Collections.singletonList(core), ctx));
    }

    // ==================== 默认权重 ====================

    @Nested
    @DisplayName("无 Dashboard 配置时的默认权重")
    class AdrDefaults {

        @Test
        @DisplayName("多 provider 无配置时灵核承接全量，灵元零流量")
        void coreWinsAllTrafficByDefault() {
            ProviderDescriptor core = new ProviderDescriptor("svc", "ling-core", ProviderKind.CORE, 100);
            ProviderDescriptor ling = new ProviderDescriptor("svc", "ling-a", ProviderKind.LING, 100);

            // 默认 CORE=100/LING=0，确定性结果——无需统计循环
            for (int i = 0; i < 10; i++) {
                ProviderDescriptor selected = router.selectProvider(Arrays.asList(core, ling), ctx);
                assertSame(core, selected, "无 Dashboard 配置时灵核应承接全量流量");
            }
        }

        @Test
        @DisplayName("无灵核 provider 且全未配置时兜底选第一个")
        void fallbackToFirstWhenAllZero() {
            ProviderDescriptor ling1 = new ProviderDescriptor("svc", "ling-a", ProviderKind.LING, 0);
            ProviderDescriptor ling2 = new ProviderDescriptor("svc", "ling-b", ProviderKind.LING, 0);

            ProviderDescriptor selected = router.selectProvider(Arrays.asList(ling1, ling2), ctx);
            assertNotNull(selected);
            assertSame(ling1, selected, "全 0 权重时兜底选第一个");
        }
    }

    // ==================== Dashboard 覆盖 ====================

    @Nested
    @DisplayName("Dashboard 权重覆盖")
    class DashboardOverride {

        @Test
        @DisplayName("Dashboard 配置后按权重分配流量")
        void weightedDistributionAfterDashboardConfig() {
            ProviderDescriptor core = new ProviderDescriptor("svc", "ling-core", ProviderKind.CORE, 100);
            ProviderDescriptor ling = new ProviderDescriptor("svc", "ling-a", ProviderKind.LING, 0);

            // Dashboard 配置：灵核 70%，灵元 30%
            router.setProviderWeight("svc", "ling-core", 70);
            router.setProviderWeight("svc", "ling-a", 30);

            Map<String, Integer> counts = new HashMap<>();
            for (int i = 0; i < 2000; i++) {
                ProviderDescriptor selected = router.selectProvider(Arrays.asList(core, ling), ctx);
                counts.merge(selected.getLingId(), 1, Integer::sum);
            }

            int coreCount = counts.getOrDefault("ling-core", 0);
            int lingCount = counts.getOrDefault("ling-a", 0);
            // 70/30 分布，允许 ±5% 容差
            assertEquals(2000, coreCount + lingCount);
            assertWithinTolerance(1400, coreCount, 100, "灵核应约 70% 流量");
            assertWithinTolerance(600, lingCount, 100, "灵元应约 30% 流量");
        }

        @Test
        @DisplayName("clearProviderWeight 后回退到默认值")
        void clearOverrideFallsBackToAdr() {
            ProviderDescriptor core = new ProviderDescriptor("svc", "ling-core", ProviderKind.CORE, 100);
            ProviderDescriptor ling = new ProviderDescriptor("svc", "ling-a", ProviderKind.LING, 0);

            router.setProviderWeight("svc", "ling-a", 50);
            router.clearProviderWeight("svc", "ling-a");

            // 清除覆盖后回到默认 CORE=100/LING=0，确定性结果
            ProviderDescriptor selected = router.selectProvider(Arrays.asList(core, ling), ctx);
            assertSame(core, selected, "清除覆盖后灵核应重新承接全量");
        }

        @Test
        @DisplayName("Dashboard 权重截断到 0-100 范围")
        void weightClampedToRange() {
            router.setProviderWeight("svc", "ling-a", 200);
            router.setProviderWeight("svc", "ling-b", -10);

            ProviderDescriptor a = new ProviderDescriptor("svc", "ling-a", ProviderKind.LING, 0);
            ProviderDescriptor b = new ProviderDescriptor("svc", "ling-b", ProviderKind.LING, 0);

            // ling-a=200 截断为 100，ling-b=-10 截断为 0 → 确定性选 ling-a
            ProviderDescriptor selected = router.selectProvider(Arrays.asList(a, b), ctx);
            assertSame(a, selected, "ling-a 权重 200 截断为 100，承接全量");
        }
    }

    private static void assertWithinTolerance(int expected, int actual, int tolerance, String message) {
        int diff = Math.abs(expected - actual);
        if (diff > tolerance) {
            throw new AssertionError(message + " —— 预期约 " + expected + "，实际 " + actual
                    + "，容差 ±" + tolerance + "，偏差 " + diff);
        }
    }
}
