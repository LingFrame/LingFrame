package com.lingframe.core.ling;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.spi.LingContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InstancePool 测试")
class InstancePoolTest {

    private static final String LING_ID = "test-ling";
    private static final int MAX_DYING = 5;

    private InstancePool pool;
    private InstanceCoordinator instanceCoordinator;

    @BeforeEach
    void setUp() {
        instanceCoordinator = new InstanceCoordinator(null);
        pool = new InstancePool(LING_ID, MAX_DYING, instanceCoordinator);
    }

    @Nested
    @DisplayName("初始状态")
    class InitialStateTests {

        @Test
        @DisplayName("新建实例池时不应存在默认实例")
        void newPoolShouldHaveNoDefault() {
            assertNull(pool.getDefault());
            assertNull(pool.getVersion());
        }

        @Test
        @DisplayName("新建实例池时不应存在活跃实例")
        void newPoolShouldHaveNoActiveInstances() {
            assertTrue(pool.getActiveInstances().isEmpty());
        }

        @Test
        @DisplayName("新建实例池时应允许添加实例")
        void newPoolShouldAllowAddInstance() {
            assertTrue(pool.canAddInstance());
        }

        @Test
        @DisplayName("新建实例池时统计信息应正确")
        void newPoolStatsShouldBeCorrect() {
            InstancePool.PoolStats stats = pool.getStats();
            assertEquals(0, stats.activeCount());
            assertEquals(0, stats.dyingCount());
            assertFalse(stats.hasDefault());
        }
    }

    @Nested
    @DisplayName("实例添加")
    class AddInstanceTests {

        @Test
        @DisplayName("添加默认实例时应设置默认实例")
        void addDefaultInstanceShouldSetDefault() {
            LingInstance instance = createMockInstance("1.0.0");

            LingInstance old = pool.addInstance(instance, true);

            assertNull(old);
            assertEquals(instance, pool.getDefault());
            assertEquals("1.0.0", pool.getVersion());
            assertEquals(1, pool.getActiveInstances().size());
        }

        @Test
        @DisplayName("添加非默认实例时不应覆盖默认实例")
        void addNonDefaultInstanceShouldNotSetDefault() {
            LingInstance instance = createMockInstance("1.0.0");

            pool.addInstance(instance, false);

            assertNull(pool.getDefault());
            assertEquals(1, pool.getActiveInstances().size());
        }

        @Test
        @DisplayName("替换默认实例时应返回旧实例")
        void replaceDefaultShouldReturnOld() {
            LingInstance v1 = createMockInstance("1.0.0");
            LingInstance v2 = createMockInstance("2.0.0");

            pool.addInstance(v1, true);
            LingInstance old = pool.addInstance(v2, true);

            assertEquals(v1, old);
            assertEquals(v2, pool.getDefault());
            assertEquals("2.0.0", pool.getVersion());
        }

        @Test
        @DisplayName("多个非默认实例应允许并存")
        void addMultipleNonDefaultShouldCoexist() {
            LingInstance stable = createMockInstance("1.0.0");
            LingInstance canary1 = createMockInstance("2.0.0-canary");
            LingInstance canary2 = createMockInstance("2.0.1-canary");

            pool.addInstance(stable, true);
            pool.addInstance(canary1, false);
            pool.addInstance(canary2, false);

            assertEquals(stable, pool.getDefault());
            assertEquals(3, pool.getActiveInstances().size());
        }

        @Test
        @DisplayName("添加空实例时应抛出异常")
        void addNullShouldThrow() {
            assertThrows(InvalidArgumentException.class, () -> pool.addInstance(null, true));
        }
    }

    @Nested
    @DisplayName("濒死队列")
    class DyingQueueTests {

        @Test
        @DisplayName("迁移到濒死队列时应标记实例并移出活跃列表")
        void moveToDyingShouldWork() {
            LingInstance instance = createMockInstance("1.0.0");
            pool.addInstance(instance, true);

            pool.moveToDying(instance);

            assertTrue(instance.isDying());
            assertEquals(0, pool.getActiveInstances().size());
            assertEquals(1, pool.getDyingCount());
        }

        @Test
        @DisplayName("迁移空实例到濒死队列时应安全忽略")
        void moveToDyingNullShouldBeSafe() {
            assertDoesNotThrow(() -> pool.moveToDying(null));
        }

        @Test
        @DisplayName("濒死队列满载时不应继续允许添加实例")
        void canAddInstanceShouldReturnFalseWhenFull() {
            for (int i = 0; i < MAX_DYING; i++) {
                LingInstance instance = createMockInstance("1.0." + i);
                pool.addInstance(instance, false);
                pool.moveToDying(instance);
            }

            assertFalse(pool.canAddInstance());
            assertEquals(MAX_DYING, pool.getDyingCount());
        }

        @Test
        @DisplayName("对同一实例重复 moveToDying 应被去重，dyingCount 不叠加")
        void duplicateMoveToDyingShouldBeDeduplicated() {
            LingInstance instance = createMockInstance("1.0.0");
            pool.addInstance(instance, true);

            pool.moveToDying(instance);
            // 第二次调用同一实例——已被移出 activePool，应直接 return
            pool.moveToDying(instance);

            assertEquals(1, pool.getDyingCount());
            assertEquals(0, pool.getActiveInstances().size());
        }
    }

    @Nested
    @DisplayName("清理行为")
    class CleanupTests {

        @Test
        @DisplayName("空闲濒死实例应被清理")
        void cleanupIdleShouldWork() {
            LingInstance instance = createMockInstance("1.0.0");
            pool.addInstance(instance, true);
            pool.moveToDying(instance);

            assertTrue(instance.isIdle());

            AtomicInteger destroyCount = new AtomicInteger(0);
            int cleaned = pool.cleanupIdleInstances(i -> destroyCount.incrementAndGet());

            assertEquals(1, cleaned);
            assertEquals(1, destroyCount.get());
            assertEquals(0, pool.getDyingCount());
        }

        @Test
        @DisplayName("忙碌中的濒死实例不应被清理")
        void cleanupIdleShouldNotCleanBusy() {
            LingInstance instance = createMockInstance("1.0.0");
            pool.addInstance(instance, true);

            instance.tryEnter();
            assertFalse(instance.isIdle());

            pool.moveToDying(instance);

            int cleaned = pool.cleanupIdleInstances(instanceCoordinator::tearDown);

            assertEquals(0, cleaned);
            assertEquals(1, pool.getDyingCount());

            instance.exit();
        }

        @Test
        @DisplayName("强制清理应移除所有濒死实例")
        void forceCleanupAllShouldWork() {
            for (int i = 0; i < 3; i++) {
                LingInstance instance = createMockInstance("1.0." + i);
                instance.tryEnter();
                pool.addInstance(instance, false);
                pool.moveToDying(instance);
            }

            assertEquals(3, pool.getDyingCount());

            AtomicInteger destroyCount = new AtomicInteger(0);
            pool.forceCleanupAll(i -> destroyCount.incrementAndGet());

            assertEquals(3, destroyCount.get());
            assertEquals(0, pool.getDyingCount());
        }

        @Test
        @DisplayName("传 null destroyer 应拒绝（仅 tearDown 的半回收会泄漏 ClassLoader）")
        void nullDestroyerShouldBeRejected() {
            assertThrows(NullPointerException.class,
                    () -> pool.cleanupIdleInstances(null),
                    "NULL destroyer allowed a tearDown-only half-reclaim, which leaks LingClassLoader");
            assertThrows(NullPointerException.class,
                    () -> pool.forceCleanupAll(null),
                    "NULL destroyer allowed a tearDown-only half-reclaim, which leaks LingClassLoader");
        }
    }

    @Nested
    @DisplayName("停机行为")
    class ShutdownTests {

        @Test
        @DisplayName("停机后应清空默认实例")
        void shutdownShouldClearDefault() {
            LingInstance instance = createMockInstance("1.0.0");
            pool.addInstance(instance, true);

            pool.shutdown();

            assertNull(pool.getDefault());
        }

        @Test
        @DisplayName("停机后应将所有实例迁移到濒死队列")
        void shutdownShouldMoveAllToDying() {
            for (int i = 0; i < 3; i++) {
                LingInstance instance = createMockInstance("1.0." + i);
                pool.addInstance(instance, i == 0);
            }

            List<LingInstance> dying = pool.shutdown();

            assertEquals(3, dying.size());
            assertEquals(0, pool.getActiveInstances().size());
            assertEquals(3, pool.getDyingCount());
        }

        @Test
        @DisplayName("停机返回的实例都应处于濒死状态")
        void shutdownInstancesShouldBeDying() {
            LingInstance instance = createMockInstance("1.0.0");
            pool.addInstance(instance, true);

            List<LingInstance> dying = pool.shutdown();

            assertTrue(dying.get(0).isDying());
        }
    }

    @Nested
    @DisplayName("可用性判断")
    class AvailabilityTests {

        @Test
        @DisplayName("实例池为空时不应判定为可用")
        void hasAvailableShouldReturnFalseWhenEmpty() {
            assertFalse(pool.hasAvailableInstance());
        }

        @Test
        @DisplayName("存在就绪实例时应判定为可用")
        void hasAvailableShouldReturnTrueWhenReady() {
            LingInstance instance = createMockInstance("1.0.0");
            pool.addInstance(instance, true);

            assertTrue(pool.hasAvailableInstance());
        }

        @Test
        @DisplayName("实例进入濒死状态后不应再判定为可用")
        void hasAvailableShouldReturnFalseWhenDying() {
            LingInstance instance = createMockInstance("1.0.0");
            pool.addInstance(instance, true);
            instanceCoordinator.stop(instance);

            assertFalse(pool.hasAvailableInstance());
        }
    }

    @Nested
    @DisplayName("并发安全")
    class ConcurrencyTests {

        @Test
        @DisplayName("并发添加实例时应保持线程安全")
        void concurrentAddShouldBeSafe() throws InterruptedException {
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        LingInstance instance = createMockInstance("1.0." + index);
                        pool.addInstance(instance, index == 0);
                    } catch (Exception ignored) {
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertTrue(completed);
            assertEquals(threadCount, pool.getActiveInstances().size());
        }

        @Test
        @DisplayName("并发迁移到濒死队列时应保持线程安全")
        void concurrentMoveToDyingShouldBeSafe() throws InterruptedException {
            for (int i = 0; i < 5; i++) {
                pool.addInstance(createMockInstance("1.0." + i), i == 0);
            }

            LingInstance[] instances = pool.getActiveInstances().toArray(new LingInstance[0]);

            int threadCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        pool.moveToDying(instances[index]);
                    } catch (Exception ignored) {
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertTrue(completed);
            assertEquals(0, pool.getActiveInstances().size());
            assertEquals(5, pool.getDyingCount());
        }

        @Test
        @DisplayName("多线程并发 moveToDying 同一实例应只入队一次")
        void concurrentMoveToDyingSameInstanceShouldNotDuplicate() throws InterruptedException {
            LingInstance instance = createMockInstance("1.0.0");
            pool.addInstance(instance, true);

            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        pool.moveToDying(instance);
                    } catch (Exception ignored) {
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertTrue(completed);
            // 无论多少线程竞争，dyingCount 必须为 1——不重复入队
            assertEquals(1, pool.getDyingCount(),
                    "多线程并发 moveToDying 同一实例应只入队一次");
            assertEquals(0, pool.getActiveInstances().size());
        }
    }

    @Nested
    @DisplayName("统计信息")
    class StatsTests {

        @Test
        @DisplayName("统计快照应反映当前实例池状态")
        void getStatsShouldBeCorrect() {
            pool.addInstance(createMockInstance("1.0.0"), true);
            pool.addInstance(createMockInstance("1.0.1"), false);

            LingInstance dying = createMockInstance("0.9.0");
            pool.addInstance(dying, false);
            pool.moveToDying(dying);

            InstancePool.PoolStats stats = pool.getStats();

            assertEquals(2, stats.activeCount());
            assertEquals(1, stats.dyingCount());
            assertTrue(stats.hasDefault());
        }

        @Test
        @DisplayName("统计对象字符串应包含关键信息")
        void poolStatsToStringShouldWork() {
            pool.addInstance(createMockInstance("1.0.0"), true);

            String value = pool.getStats().toString();

            assertTrue(value.contains("active=1"));
            assertTrue(value.contains("dying=0"));
            assertTrue(value.contains("hasDefault=true"));
        }
    }

    @Nested
    @DisplayName("默认实例选举")
    class DefaultElectionTests {

        @Test
        @DisplayName("默认实例下线时应按语义版本号选举最新版本为新主")
        void shouldElectHighestSemanticVersionAsDefault() {
            // 场景：稳定版 1.9.0（默认）与新版 1.10.0 并存。
            // 字典序降序会错选 1.9.0（'9'>'1'），语义版本序应选 1.10.0。
            LingInstance stable = createMockInstance("1.9.0");
            LingInstance newer = createMockInstance("1.10.0");
            pool.addInstance(stable, true);
            pool.addInstance(newer, false);

            pool.moveToDying(stable);

            assertEquals(newer, pool.getDefault(),
                    "默认实例下线后应选举语义版本号最大的实例为新主，而非字典序最大");
        }

        @Test
        @DisplayName("多位数字版本号应按数值比较，避免字典序陷阱")
        void shouldCompareMultiDigitVersionsNumerically() {
            // 2.10.0 应优先于 2.9.0，验证多位数字段的数值比较
            LingInstance v2_9 = createMockInstance("2.9.0");
            LingInstance v2_10 = createMockInstance("2.10.0");
            pool.addInstance(v2_9, true);
            pool.addInstance(v2_10, false);

            pool.moveToDying(v2_9);

            assertEquals(v2_10, pool.getDefault(),
                    "2.10.0 应优先于 2.9.0 被选举为新主");
        }

        @Test
        @DisplayName("版本段数不一致时缺失段应视为零")
        void shouldTreatMissingSegmentsAsZero() {
            // 1.2 与 1.2.0 应等价；1.3 应优先于 1.2.9
            LingInstance v1_2_9 = createMockInstance("1.2.9");
            LingInstance v1_3 = createMockInstance("1.3");
            pool.addInstance(v1_2_9, true);
            pool.addInstance(v1_3, false);

            pool.moveToDying(v1_2_9);

            assertEquals(v1_3, pool.getDefault(),
                    "1.3 应优先于 1.2.9，缺失段视为零后 1.3.0 > 1.2.9");
        }

        @Test
        @DisplayName("非数字版本段应回退字典序比较")
        void shouldFallbackToLexicographicForNonNumericSegments() {
            // 1.0.0-RC2 与 1.0.0-RC10：纯数字回退字典序，RC2 > RC10（字典序）
            // 但本测试验证非数字段不抛异常且有确定顺序
            LingInstance rc2 = createMockInstance("1.0.0-RC2");
            LingInstance rc10 = createMockInstance("1.0.0-RC10");
            pool.addInstance(rc2, true);
            pool.addInstance(rc10, false);

            // 不论选哪个，关键是应有确定结果且不抛异常
            pool.moveToDying(rc2);
            assertNotNull(pool.getDefault(),
                    "含非数字段的版本号选举应产生确定结果，不应抛异常");
        }

        @Test
        @DisplayName("removeInstance 移除默认时也应按语义版本选举新主")
        void removeInstanceShouldElectBySemanticVersion() {
            LingInstance v1_5 = createMockInstance("1.5.0");
            LingInstance v1_11 = createMockInstance("1.11.0");
            pool.addInstance(v1_5, true);
            pool.addInstance(v1_11, false);

            pool.removeInstance(v1_5);

            assertEquals(v1_11, pool.getDefault(),
                    "removeInstance 移除默认后应选举 1.11.0（而非字典序的 1.5.0）");
        }
    }

    private LingInstance createMockInstance(String version) {
        LingContainer container = mock(LingContainer.class);
        when(container.isActive()).thenReturn(true);

        LingDefinition definition = new LingDefinition();
        definition.setId(LING_ID);
        definition.setVersion(version);

        LingInstance instance = new LingInstance(container, definition, new EventBus());
        instanceCoordinator.prepare(instance);
        instanceCoordinator.start(instance);
        instanceCoordinator.markReady(instance);
        return instance;
    }
}
