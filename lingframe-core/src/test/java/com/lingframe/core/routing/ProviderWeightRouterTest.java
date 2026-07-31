package com.lingframe.core.routing;

import com.lingframe.api.event.lifecycle.LingUninstalledEvent;
import com.lingframe.core.event.EventBus;
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
 * 覆盖：单 provider 直选、默认权重（注册时沉淀的 weight）、Dashboard 覆盖、权重分布。
 * <p>
 * 去身份化后不再引用 ProviderKind，权重数值在注册时已沉淀。
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
        ProviderDescriptor core = new ProviderDescriptor("svc", "ling-core", 0);
        assertSame(core, router.selectProvider(Collections.singletonList(core), ctx));
    }

    // ==================== 默认权重 ====================

    @Nested
    @DisplayName("无 Dashboard 配置时的默认权重")
    class RegisteredDefaults {

        @Test
        @DisplayName("多 provider 无配置时按注册 weight 决策：weight=100 承接全量，weight=0 零流量")
        void registeredWeightWinsAllTrafficByDefault() {
            // 灵核注册时 weight=100，灵元注册时 weight=0——身份影响已沉淀为 weight 数值
            ProviderDescriptor core = new ProviderDescriptor("svc", "ling-core", 100);
            ProviderDescriptor ling = new ProviderDescriptor("svc", "ling-a", 0);

            // 注册 weight 确定性结果——无需统计循环
            for (int i = 0; i < 10; i++) {
                ProviderDescriptor selected = router.selectProvider(Arrays.asList(core, ling), ctx);
                assertSame(core, selected, "无 Dashboard 配置时 weight=100 的 provider 应承接全量流量");
            }
        }

        @Test
        @DisplayName("所有 provider weight 均为 0 时兜底选第一个")
        void fallbackToFirstWhenAllZero() {
            ProviderDescriptor ling1 = new ProviderDescriptor("svc", "ling-a", 0);
            ProviderDescriptor ling2 = new ProviderDescriptor("svc", "ling-b", 0);

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
            ProviderDescriptor core = new ProviderDescriptor("svc", "ling-core", 100);
            ProviderDescriptor ling = new ProviderDescriptor("svc", "ling-a", 0);

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
        @DisplayName("clearProviderWeight 后回退到注册时初始 weight")
        void clearOverrideFallsBackToRegistered() {
            ProviderDescriptor core = new ProviderDescriptor("svc", "ling-core", 100);
            ProviderDescriptor ling = new ProviderDescriptor("svc", "ling-a", 0);

            router.setProviderWeight("svc", "ling-a", 50);
            router.clearProviderWeight("svc", "ling-a");

            // 清除覆盖后回到注册时 weight=0，确定性结果——灵核承接全量
            ProviderDescriptor selected = router.selectProvider(Arrays.asList(core, ling), ctx);
            assertSame(core, selected, "清除覆盖后 weight=100 的灵核应重新承接全量");
        }

        @Test
        @DisplayName("Dashboard 权重截断到 0-100 范围")
        void weightClampedToRange() {
            router.setProviderWeight("svc", "ling-a", 200);
            router.setProviderWeight("svc", "ling-b", -10);

            ProviderDescriptor a = new ProviderDescriptor("svc", "ling-a", 0);
            ProviderDescriptor b = new ProviderDescriptor("svc", "ling-b", 0);

            // ling-a=200 截断为 100，ling-b=-10 截断为 0 → 确定性选 ling-a
            ProviderDescriptor selected = router.selectProvider(Arrays.asList(a, b), ctx);
            assertSame(a, selected, "ling-a 权重 200 截断为 100，承接全量");
        }

        @Test
        @DisplayName("支持 N 元（3 个及以上候选节点）多 Provider 按权重比例分配")
        void supportNWayProviderWeightDistribution() {
            ProviderDescriptor p1 = new ProviderDescriptor("svc", "ling-a", 50);
            ProviderDescriptor p2 = new ProviderDescriptor("svc", "ling-b", 30);
            ProviderDescriptor p3 = new ProviderDescriptor("svc", "ling-c", 20);

            Map<ProviderDescriptor, Integer> counts = new HashMap<>();
            int totalRuns = 1000;
            for (int i = 0; i < totalRuns; i++) {
                ProviderDescriptor selected = router.selectProvider(Arrays.asList(p1, p2, p3), ctx);
                counts.put(selected, counts.getOrDefault(selected, 0) + 1);
            }

            // 50:30:20 预期 500:300:200，允许 ±80 容差
            assertWithinTolerance(500, counts.getOrDefault(p1, 0), 80, "P1 流量占比与权重相符");
            assertWithinTolerance(300, counts.getOrDefault(p2, 0), 80, "P2 流量占比与权重相符");
            assertWithinTolerance(200, counts.getOrDefault(p3, 0), 80, "P3 流量占比与权重相符");
        }
    }

    // ==================== 卸载清理 ====================

    @Nested
    @DisplayName("卸载清理 evictProvider")
    class EvictOnUnload {

        @Test
        @DisplayName("清理裸 lingId 和 lingId:version 两种 providerKey（跨所有契约）")
        void evictBothBareAndVersionedKey() {
            router.setProviderWeight("svc-a", "ling-a", 30);
            router.setProviderWeight("svc-a", "ling-a:1.1.0", 50);
            router.setProviderWeight("svc-b", "ling-a", 20);

            router.evictProvider("ling-a");

            assertNull(router.getOverrideWeight("svc-a", "ling-a"));
            assertNull(router.getOverrideWeight("svc-a", "ling-a:1.1.0"));
            assertNull(router.getOverrideWeight("svc-b", "ling-a"));
        }

        @Test
        @DisplayName("清理后空 contractId entry 被回收，无关契约保留")
        void emptyContractEvictedUnrelatedKept() {
            router.setProviderWeight("svc-a", "ling-a", 30);
            router.setProviderWeight("svc-b", "ling-b", 40);

            router.evictProvider("ling-a");

            assertNull(router.getOverrideWeight("svc-a", "ling-a"));
            assertEquals(Integer.valueOf(40), router.getOverrideWeight("svc-b", "ling-b"));
        }

        @Test
        @DisplayName("不误删前缀碰撞的无关条目（user-ling 不清 user-ling-v2）")
        void noFalsePositiveOnPrefixCollision() {
            router.setProviderWeight("svc", "user-ling", 30);
            router.setProviderWeight("svc", "user-ling-v2", 50);

            router.evictProvider("user-ling");

            assertNull(router.getOverrideWeight("svc", "user-ling"));
            assertEquals(Integer.valueOf(50), router.getOverrideWeight("svc", "user-ling-v2"));
        }

        @Test
        @DisplayName("灵元卸载事件自动触发权重清理")
        void unloadEventTriggersEvict() {
            EventBus eventBus = new EventBus();
            ProviderWeightRouter eventRouter = new ProviderWeightRouter(eventBus);
            eventRouter.setProviderWeight("svc", "ling-a", 30);
            assertEquals(Integer.valueOf(30), eventRouter.getOverrideWeight("svc", "ling-a"));

            // LingUninstalledEvent 同步分发，publish 返回时监听器已执行
            eventBus.publish(new LingUninstalledEvent("ling-a"));

            assertNull(eventRouter.getOverrideWeight("svc", "ling-a"));
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
