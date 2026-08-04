package com.lingframe.core.ling;

import com.lingframe.core.spi.LingUnloadHook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LingUnloadCoordinator 两桶并行编排集成测试")
class LingUnloadCoordinatorParallelTest {

    /**
     * 可观测的测试 Hook：记录调用顺序和时间
     */
    static class ObservableHook implements LingUnloadHook {
        final String name;
        final AtomicInteger callCount = new AtomicInteger(0);
        final AtomicLong callTimestamp = new AtomicLong(0);
        final AtomicLong completedTimestamp = new AtomicLong(0);
        volatile CountDownLatch startLatch;
        volatile Runnable beforeAction;

        ObservableHook(String name) {
            this.name = name;
        }

        @Override
        public void cleanup(String lingId, ClassLoader classLoader) {
            callCount.incrementAndGet();
            callTimestamp.set(System.nanoTime());
            if (beforeAction != null) {
                beforeAction.run();
            }
            if (startLatch != null) {
                startLatch.countDown();
            }
            completedTimestamp.set(System.nanoTime());
        }
    }

    static class ThrowingHook implements LingUnloadHook {
        final String name;
        final AtomicInteger callCount = new AtomicInteger(0);

        ThrowingHook(String name) {
            this.name = name;
        }

        @Override
        public void cleanup(String lingId, ClassLoader classLoader) {
            callCount.incrementAndGet();
            throw new RuntimeException("intentional failure from " + name);
        }
    }

    @Nested
    @DisplayName("两桶串行：生态桶先于 JVM 桶执行")
    class PhaseOrderingTest {

        @Test
        @DisplayName("生态 Hook 全部完成后，JVM Hook 才开始")
        void ecosystemBeforeJvm() throws Exception {
            ObservableHook eco1 = new ObservableHook("eco-1");
            ObservableHook eco2 = new ObservableHook("eco-2");
            ObservableHook jvm1 = new ObservableHook("jvm-1");

            // 生态 Hook 启动后阻塞，等测试放行后才继续
            CountDownLatch ecoStarted = new CountDownLatch(2);
            CountDownLatch releaseEco = new CountDownLatch(1);
            eco1.beforeAction = () -> {
                ecoStarted.countDown();
                try { releaseEco.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            };
            eco2.beforeAction = () -> {
                ecoStarted.countDown();
                try { releaseEco.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            };

            LingUnloadCoordinator coordinator = new LingUnloadCoordinator(
                    null,
                    Arrays.asList(eco1, eco2),
                    Collections.singletonList(jvm1),
                    null, null);

            // 异步执行 cleanup，让生态 Hook 阻塞
            Thread t = new Thread(() -> coordinator.onVersionUnload("ling-1", "v1", new ClassLoader() {}));
            t.start();

            // 等两个生态 Hook 都启动并阻塞
            assertTrue(ecoStarted.await(5, TimeUnit.SECONDS));

            // 此时生态桶未完成，JVM 桶还没开始
            assertEquals(0, jvm1.callCount.get());

            // 放行生态 Hook，让其自然完成
            releaseEco.countDown();
            t.join(5000);

            // 现在 JVM Hook 应该已完成
            assertTrue(jvm1.callCount.get() >= 1);
        }
    }

    @Nested
    @DisplayName("桶内并行：同桶 Hook 并发执行")
    class ParallelExecutionTest {

        @Test
        @DisplayName("JVM 桶内多个 Hook 并行执行")
        void jvmHooksRunInParallel() throws Exception {
            int hookCount = 3;
            CountDownLatch allStarted = new CountDownLatch(hookCount);
            CountDownLatch releaseAll = new CountDownLatch(1);

            List<ObservableHook> hooks = new ArrayList<>();
            for (int i = 0; i < hookCount; i++) {
                ObservableHook h = new ObservableHook("jvm-" + i);
                h.beforeAction = () -> {
                    try {
                        allStarted.countDown();
                        releaseAll.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                };
                hooks.add(h);
            }

            List<LingUnloadHook> hookList = new ArrayList<>(hooks);
            LingUnloadCoordinator coordinator = new LingUnloadCoordinator(
                    null,
                    Collections.emptyList(),
                    hookList,
                    null, null);

            Thread t = new Thread(() -> coordinator.onVersionUnload("ling-1", "v1", new ClassLoader() {}));
            t.start();

            // 所有 Hook 都启动了 → 证明并行
            assertTrue(allStarted.await(5, TimeUnit.SECONDS));

            releaseAll.countDown();
            t.join(5000);

            for (ObservableHook h : hooks) {
                assertEquals(1, h.callCount.get(), "Hook " + h.name + " should be called exactly once");
            }
        }

        @Test
        @DisplayName("生态桶内多个 Hook 并行执行")
        void ecosystemHooksRunInParallel() throws Exception {
            int hookCount = 3;
            CountDownLatch allStarted = new CountDownLatch(hookCount);
            CountDownLatch releaseAll = new CountDownLatch(1);

            List<ObservableHook> hooks = new ArrayList<>();
            for (int i = 0; i < hookCount; i++) {
                ObservableHook h = new ObservableHook("eco-" + i);
                h.beforeAction = () -> {
                    try {
                        allStarted.countDown();
                        releaseAll.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                };
                hooks.add(h);
            }

            List<LingUnloadHook> hookList = new ArrayList<>(hooks);
            LingUnloadCoordinator coordinator = new LingUnloadCoordinator(
                    null,
                    hookList,
                    Collections.emptyList(),
                    null, null);

            Thread t = new Thread(() -> coordinator.onVersionUnload("ling-1", "v1", new ClassLoader() {}));
            t.start();

            assertTrue(allStarted.await(5, TimeUnit.SECONDS));

            releaseAll.countDown();
            t.join(5000);

            for (ObservableHook h : hooks) {
                assertEquals(1, h.callCount.get());
            }
        }
    }

    @Nested
    @DisplayName("异常隔离：单个 Hook 异常不阻塞其他 Hook")
    class ExceptionIsolationTest {

        @Test
        @DisplayName("JVM 桶内一个 Hook 抛异常，其他 Hook 仍正常执行")
        void jvmHookExceptionDoesNotBlockOthers() {
            ObservableHook good1 = new ObservableHook("good-1");
            ThrowingHook bad = new ThrowingHook("bad");
            ObservableHook good2 = new ObservableHook("good-2");

            LingUnloadCoordinator coordinator = new LingUnloadCoordinator(
                    null,
                    Collections.emptyList(),
                    Arrays.asList(good1, bad, good2),
                    null, null);

            assertDoesNotThrow(() -> coordinator.onVersionUnload("ling-1", "v1", new ClassLoader() {}));

            assertEquals(1, good1.callCount.get());
            assertEquals(1, bad.callCount.get()); // 异常 Hook 也被调用了
            assertEquals(1, good2.callCount.get());
        }

        @Test
        @DisplayName("生态桶内一个 Hook 抛异常，JVM 桶仍正常执行")
        void ecosystemExceptionDoesNotBlockJvm() {
            ThrowingHook badEco = new ThrowingHook("bad-eco");
            ObservableHook goodJvm = new ObservableHook("good-jvm");

            LingUnloadCoordinator coordinator = new LingUnloadCoordinator(
                    null,
                    Collections.singletonList(badEco),
                    Collections.singletonList(goodJvm),
                    null, null);

            assertDoesNotThrow(() -> coordinator.onVersionUnload("ling-1", "v1", new ClassLoader() {}));

            assertEquals(1, badEco.callCount.get());
            assertEquals(1, goodJvm.callCount.get());
        }
    }

    @Nested
    @DisplayName("空桶和边界场景")
    class EdgeCaseTest {

        @Test
        @DisplayName("两个桶都为空时不报错")
        void emptyBuckets() {
            LingUnloadCoordinator coordinator = new LingUnloadCoordinator(
                    null, Collections.emptyList(), Collections.emptyList(), null, null);

            assertDoesNotThrow(() -> coordinator.onVersionUnload("ling-1", "v1", new ClassLoader() {}));
        }

        @Test
        @DisplayName("单 Hook 无并行开销，直接执行")
        void singleHookDirectExecution() {
            ObservableHook hook = new ObservableHook("single");

            LingUnloadCoordinator coordinator = new LingUnloadCoordinator(
                    null, Collections.emptyList(), Collections.singletonList(hook), null, null);

            assertDoesNotThrow(() -> coordinator.onVersionUnload("ling-1", "v1", new ClassLoader() {}));
            assertEquals(1, hook.callCount.get());
        }

        @Test
        @DisplayName("null ClassLoader 时跳过所有 Hook")
        void nullClassLoaderSkipsAll() {
            ObservableHook hook = new ObservableHook("should-not-run");

            LingUnloadCoordinator coordinator = new LingUnloadCoordinator(
                    null, Collections.emptyList(), Collections.singletonList(hook), null, null);

            coordinator.onVersionUnload("ling-1", "v1", null);
            assertEquals(0, hook.callCount.get());
        }

        @Test
        @DisplayName("兼容旧单桶构造：全部 Hook 归入 JVM 桶")
        void legacySingleBucketConstructor() {
            ObservableHook hook = new ObservableHook("legacy");

            LingUnloadCoordinator coordinator = new LingUnloadCoordinator(
                    null, Collections.emptyList(), Collections.singletonList(hook), null, null);

            coordinator.onVersionUnload("ling-1", "v1", new ClassLoader() {});
            assertEquals(1, hook.callCount.get());
        }
    }

    @Nested
    @DisplayName("shutdown 关闭线程池")
    class ShutdownTest {

        @Test
        @DisplayName("shutdown 不报错")
        void shutdownCleanly() {
            LingUnloadCoordinator coordinator = new LingUnloadCoordinator(
                    null, Collections.emptyList(), Collections.emptyList(), null, null);

            assertDoesNotThrow(() -> coordinator.shutdown());
        }

        @Test
        @DisplayName("shutdown 通知所有 Hook 释放资源")
        void shutdownNotifiesHooks() {
            AtomicBoolean shutdownCalled = new AtomicBoolean(false);
            LingUnloadHook hook = new LingUnloadHook() {
                @Override
                public void cleanup(String lingId, ClassLoader classLoader) {}

                @Override
                public void shutdown() {
                    shutdownCalled.set(true);
                }
            };

            LingUnloadCoordinator coordinator = new LingUnloadCoordinator(
                    null, Collections.singletonList(hook), Collections.emptyList(), null, null);

            coordinator.shutdown();
            assertTrue(shutdownCalled.get());
        }
    }
}
