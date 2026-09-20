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
import java.util.ConcurrentModificationException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

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

    @Nested
    @DisplayName("按修订原子替换权重覆盖")
    class AtomicReplacement {

        @Test
        @DisplayName("整表替换删除旧键并保持旧快照不变")
        void replacesWholeMap() {
            router.setProviderWeight("svc", "obsolete", 10);
            ProviderWeightSnapshot old = router.getWeightSnapshot("svc");
            Map<String, Integer> weights = weights(90, 10);
            ProviderWeightSnapshot applied = router.replaceProviderWeights("svc", old.getRevision(), weights);
            weights.clear();
            assertEquals(weights(90, 10), applied.getWeights());
            assertEquals(Collections.singletonMap("obsolete", 10), old.getWeights());
            assertNull(router.getOverrideWeight("svc", "obsolete"));
            assertSame(applied, router.getWeightSnapshot("svc"));
            assertNotEquals(old.getRevision(), applied.getRevision());
            Map<String, Integer> legacyCopy = router.getOverrideWeights("svc");
            legacyCopy.clear();
            assertEquals(2, applied.getWeights().size());
        }

        @Test
        @DisplayName("旧修订和不同路由器或作用域的修订均拒绝且不改变状态")
        void rejectsForeignAndStaleRevisions() {
            String initial = router.getWeightSnapshot("svc").getRevision();
            ProviderWeightSnapshot applied = router.replaceProviderWeights("svc", initial, weights(10, 90));
            for (String revision : Arrays.asList(initial,
                    new ProviderWeightRouter().getWeightSnapshot("svc").getRevision(),
                    router.getWeightSnapshot("other").getRevision())) {
                ConcurrentModificationException error = assertThrows(ConcurrentModificationException.class,
                        () -> router.replaceProviderWeights("svc", revision, weights(90, 10)));
                assertTrue(error.getMessage().contains(applied.getRevision()));
                assertSame(applied, router.getWeightSnapshot("svc"));
            }
        }

        @Test
        @DisplayName("非法整表不产生部分发布或新修订")
        void invalidReplacementLeavesStateUntouched() {
            router.setProviderWeight("svc", "a", 10);
            ProviderWeightSnapshot current = router.getWeightSnapshot("svc");
            assertThrows(IllegalArgumentException.class,
                    () -> router.replaceProviderWeights("svc", current.getRevision(), weights(90, 101)));
            assertThrows(NullPointerException.class,
                    () -> router.replaceProviderWeights("svc", current.getRevision(), null));
            assertSame(current, router.getWeightSnapshot("svc"));
        }

        @Test
        @DisplayName("清除后不能复用空作用域旧修订且注册默认权重恢复")
        void clearDoesNotReuseRevision() {
            String absent = router.getWeightSnapshot("svc").getRevision();
            router.setProviderWeight("svc", "a", 0);
            ProviderWeightSnapshot before = router.getWeightSnapshot("svc");
            ProviderWeightSnapshot empty = router.replaceProviderWeights("svc", before.getRevision(), Collections.emptyMap());
            assertEquals(empty.getRevision(), router.getWeightSnapshot("svc").getRevision());
            assertThrows(ConcurrentModificationException.class,
                    () -> router.replaceProviderWeights("svc", absent, weights(100, 0)));
            ProviderDescriptor a = new ProviderDescriptor("svc", "a", 100);
            assertSame(a, router.selectProvider(Arrays.asList(a, new ProviderDescriptor("svc", "b", 0)), ctx));
            router.setProviderWeight("other", "c", 1);
            assertNotEquals(empty.getRevision(), router.getWeightSnapshot("svc").getRevision());
        }

        @Test
        @DisplayName("旧设置清除和卸载均使对应契约的修订失效")
        void legacyWritesInvalidateRevision() {
            router.setProviderWeight("svc", "a", 50);
            String beforeSet = router.getWeightSnapshot("svc").getRevision();
            router.setProviderWeight("svc", "b", 50);
            assertThrows(ConcurrentModificationException.class,
                    () -> router.replaceProviderWeights("svc", beforeSet, weights(10, 90)));
            ProviderWeightSnapshot beforeClear = router.getWeightSnapshot("svc");
            router.clearProviderWeight("svc", "a");
            assertEquals(2, beforeClear.getWeights().size());
            String beforeEvict = router.getWeightSnapshot("svc").getRevision();
            assertNotEquals(beforeClear.getRevision(), beforeEvict);
            router.evictProvider("b");
            assertThrows(ConcurrentModificationException.class,
                    () -> router.replaceProviderWeights("svc", beforeEvict, weights(10, 90)));
            assertTrue(router.getOverrideWeights("svc").isEmpty());
        }

        @Test
        @DisplayName("不相关写入不使已有非空契约修订失效")
        void independentPopulatedScopes() {
            router.setProviderWeight("svc", "a", 50);
            ProviderWeightSnapshot current = router.getWeightSnapshot("svc");
            router.setProviderWeight("other", "b", 50);
            router.evictProvider("b");
            router.clearProviderWeight("svc", "missing");
            assertSame(current, router.getWeightSnapshot("svc"));
            assertDoesNotThrow(() -> router.replaceProviderWeights("svc", current.getRevision(), weights(90, 10)));
        }

        @Test
        @DisplayName("两个控制端使用同一修订时恰好一个发布成功")
        void concurrentWritersHaveOneWinner() throws Exception {
            String revision = router.getWeightSnapshot("svc").getRevision();
            CyclicBarrier barrier = new CyclicBarrier(2);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<Boolean> first = executor.submit(() -> replaceAfterBarrier(barrier, revision, weights(100, 0)));
                Future<Boolean> second = executor.submit(() -> replaceAfterBarrier(barrier, revision, weights(0, 100)));
                assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
                Map<String, Integer> actual = router.getWeightSnapshot("svc").getWeights();
                assertTrue(actual.equals(weights(100, 0)) || actual.equals(weights(0, 100)));
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }

        private boolean replaceAfterBarrier(CyclicBarrier barrier, String revision, Map<String, Integer> weights)
                throws Exception {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                router.replaceProviderWeights("svc", revision, weights);
                return true;
            } catch (ConcurrentModificationException expected) {
                return false;
            }
        }

        @Test
        @DisplayName("选路中途发布新策略时只消费完整旧覆盖表")
        void selectionPinsOneSnapshot() {
            router.replaceProviderWeights("svc", router.getWeightSnapshot("svc").getRevision(), weights(0, 100));
            AtomicBoolean switched = new AtomicBoolean();
            ProviderDescriptor a = new ProviderDescriptor("svc", "a", 0) {
                @Override
                public String providerKey() {
                    if (switched.compareAndSet(false, true)) {
                        router.replaceProviderWeights("svc", router.getWeightSnapshot("svc").getRevision(), weights(100, 0));
                    }
                    return "a";
                }
            };
            ProviderDescriptor b = new ProviderDescriptor("svc", "b", 0);
            assertSame(b, router.selectProvider(Arrays.asList(a, b), ctx));
            assertSame(a, router.selectProvider(Arrays.asList(a, b), ctx));
        }

        private Map<String, Integer> weights(int a, int b) {
            Map<String, Integer> weights = new HashMap<>();
            weights.put("a", a);
            weights.put("b", b);
            return weights;
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
