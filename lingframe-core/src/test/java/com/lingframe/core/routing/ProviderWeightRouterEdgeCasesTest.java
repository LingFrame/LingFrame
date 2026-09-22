package com.lingframe.core.routing;

import com.lingframe.core.pipeline.InvocationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * ProviderWeightRouter 生产极端边界与并发安全测试。
 * 覆盖：全 0 权重兜底、非法极值截断、并发更新权重争用、Candidate 动态空化。
 */
@DisplayName("ProviderWeightRouter 生产极端边界测试")
class ProviderWeightRouterEdgeCasesTest {

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

    @Test
    @DisplayName("极端场景 1：当所有节点权重均为 0 时，绝对不能除以 0，且优雅安全降级选中首个节点")
    void allZeroWeightsSafetyFallback() {
        ProviderDescriptor p1 = new ProviderDescriptor("svc", "node-1", 0);
        ProviderDescriptor p2 = new ProviderDescriptor("svc", "node-2", 0);
        ProviderDescriptor p3 = new ProviderDescriptor("svc", "node-3", 0);

        // 显示设置全部权重为 0
        router.setProviderWeight("svc", "node-1", 0);
        router.setProviderWeight("svc", "node-2", 0);
        router.setProviderWeight("svc", "node-3", 0);

        for (int i = 0; i < 100; i++) {
            ProviderDescriptor selected = router.selectProvider(Arrays.asList(p1, p2, p3), ctx);
            assertNotNull(selected, "全 0 权重时选路结果不得为 null");
            assertSame(p1, selected, "全 0 权重时必须安全降级选中首个候选节点");
        }
    }

    @Test
    @DisplayName("极端场景 2：非法极值（负数/大数）必须被安全截断在 0-100 范围内，不引起溢出")
    void extremeNegativeAndOverflowWeightClamping() {
        ProviderDescriptor p1 = new ProviderDescriptor("svc", "node-1", 0);
        ProviderDescriptor p2 = new ProviderDescriptor("svc", "node-2", 0);

        // 设置非法极值：-999900 与 999900
        router.setProviderWeight("svc", "node-1", -999900);
        router.setProviderWeight("svc", "node-2", 999900);

        // node-1 截断为 0，node-2 截断为 100 → 选路必落 node-2
        for (int i = 0; i < 50; i++) {
            ProviderDescriptor selected = router.selectProvider(Arrays.asList(p1, p2), ctx);
            assertSame(p2, selected, "极值截断后 node-2 承接 100% 流量");
        }
    }

    @Test
    @DisplayName("极端场景 3：选路过程中 Candidate 集合动态空化或传入 null 描述符，不引起 NPE")
    void dynamicCandidateEmptiedOrNullElementSafety() {
        assertNull(router.selectProvider(Collections.emptyList(), ctx));
        assertNull(router.selectProvider(null, ctx));

        List<ProviderDescriptor> candidates = new ArrayList<>();
        candidates.add(null);
        // 包含 null 元素的极端损坏列表不崩溃
        assertNull(router.selectProvider(candidates, ctx));
    }

    @Test
    @DisplayName("极端场景 4：高并发多线程争用写权重与读选路，无 CME、无死锁，保证线程安全")
    void concurrentReadWriteThreadSafety() throws Exception {
        int threadCount = 20;
        int loopCount = 500;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCounter = new AtomicInteger(0);

        ProviderDescriptor p1 = new ProviderDescriptor("svc", "node-1", 50);
        ProviderDescriptor p2 = new ProviderDescriptor("svc", "node-2", 50);
        List<ProviderDescriptor> candidates = Arrays.asList(p1, p2);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < loopCount; j++) {
                        // 10% 概率写权重，90% 概率读选路
                        if (j % 10 == 0) {
                            int weight = (threadId * j) % 100;
                            router.setProviderWeight("svc", "node-1", weight);
                        } else {
                            ProviderDescriptor selected = router.selectProvider(candidates, null);
                            assertNotNull(selected);
                        }
                        successCounter.incrementAndGet();
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        assertEquals(true, completed, "高并发读写选路必须在 10 秒内安全完成且无死锁");
        assertEquals(threadCount * loopCount, successCounter.get(), "所有并发选路与更新均须成功");
    }
}
