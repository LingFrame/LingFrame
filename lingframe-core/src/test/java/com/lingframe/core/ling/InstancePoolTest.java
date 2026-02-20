package com.lingframe.core.ling;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.exception.InvalidArgumentException;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InstancePool 单元测试")
public class InstancePoolTest {

    private static final String Ling_ID = "test-ling";
    private static final int MAX_DYING = 5;

    private InstancePool pool;

    @BeforeEach
    void setUp() {
        pool = new InstancePool(Ling_ID, MAX_DYING);
    }

    // ==================== 辅助方法 ====================

    private LingInstance createMockInstance(String version) {
        LingContainer container = mock(LingContainer.class);
        when(container.isActive()).thenReturn(true);

        LingDefinition definition = new LingDefinition();
        definition.setId(Ling_ID);
        definition.setVersion(version);

        LingInstance instance = new LingInstance(container, definition);
        instance.markReady();
        return instance;
    }

    // ==================== 初始状态测试 ====================

    @Nested
    @DisplayName("初始状态")
    class InitialStateTests {

        @Test
        @DisplayName("新池应该没有默认实例")
        void newPoolShouldHaveNoDefault() {
            assertNull(pool.getDefault());
            assertNull(pool.getVersion());
        }

        @Test
        @DisplayName("新池应该没有活跃实例")
        void newPoolShouldHaveNoActiveInstances() {
            assertTrue(pool.getActiveInstances().isEmpty());
        }

        @Test
        @DisplayName("新池应该可以添加实例")
        void newPoolShouldAllowAddInstance() {
            assertTrue(pool.canAddInstance());
        }

        @Test
        @DisplayName("新池的统计信息应正确")
        void newPoolStatsShouldBeCorrect() {
            InstancePool.PoolStats stats = pool.getStats();

            assertEquals(0, stats.activeCount());
            assertEquals(0, stats.dyingCount());
            assertFalse(stats.hasDefault());
        }
    }

    // ==================== 添加实例测试 ====================

    @Nested
    @DisplayName("添加实例")
    class AddInstanceTests {

        @Test
        @DisplayName("添加默认实例应设置默认引用")
        void addDefaultInstanceShouldSetDefault() {
            LingInstance instance = createMockInstance("1.0.0");

            LingInstance old = pool.addInstance(instance, true);

            assertNull(old);
            assertEquals(instance, pool.getDefault());
            assertEquals("1.0.0", pool.getVersion());
            assertEquals(1, pool.getActiveInstances().size());
        }

        @Test
        @DisplayName("添加非默认实例不应设置默认引用")
        void addNonDefaultInstanceShouldNotSetDefault() {
            LingInstance instance = createMockInstance("1.0.0");

            pool.addInstance(instance, false);

            assertNull(pool.getDefault());
            assertEquals(1, pool.getActiveInstances().size());
        }

        @Test
        @DisplayName("替换默认实例应返回旧实例")
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
        @DisplayName("添加多个非默认实例应该共存")
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
        @DisplayName("添加 null 实例应抛出异常")
        void addNullShouldThrow() {
            assertThrows(InvalidArgumentException.class, () ->
                    pool.addInstance(null, true));
        }
    }

    // ==================== 死亡队列测试 ====================

    @Nested
    @DisplayName("死亡队列")
    class DyingQueueTests {

        @Test
        @DisplayName("moveToDying 应将实例移到死亡队列")
        void moveToDyingShouldWork() {
            LingInstance instance = createMockInstance("1.0.0");
            pool.addInstance(instance, true);

            pool.moveToDying(instance);

            assertTrue(instance.isDying());
            assertEquals(0, pool.getActiveInstances().size());
            assertEquals(1, pool.getDyingCount());
        }

        @Test
        @DisplayName("moveToDying null 应安全处理")
        void moveToDyingNullShouldBeSafe() {
            assertDoesNotThrow(() -> pool.moveToDying(null));
        }

        @Test
        @DisplayName("死亡队列满时 canAddInstance 应返回 false")
        void canAddInstanceShouldReturnFalseWhenFull() {
            // 填满死亡队列
            for (int i = 0; i < MAX_DYING; i++) {
                LingInstance instance = createMockInstance("1.0." + i);
                pool.addInstance(instance, false);
                pool.moveToDying(instance);
            }

            assertFalse(pool.canAddInstance());
            assertEquals(MAX_DYING, pool.getDyingCount());
        }
    }

    // ==================== 清理测试 ====================

    @Nested
    @DisplayName("清理功能")
    class CleanupTests {

        @Test
        @DisplayName("cleanupIdleInstances 应清理空闲实例")
        void cleanupIdleShouldWork() {
            LingInstance instance = createMockInstance("1.0.0");
            pool.addInstance(instance, true);
            pool.moveToDying(instance);

            // 实例是空闲的（没有活跃请求）
            assertTrue(instance.isIdle());

            AtomicInteger destroyCount = new AtomicInteger(0);
            int cleaned = pool.cleanupIdleInstances(i -> destroyCount.incrementAndGet());

            assertEquals(1, cleaned);
            assertEquals(1, destroyCount.get());
            assertEquals(0, pool.getDyingCount());
        }

        @Test
        @DisplayName("cleanupIdleInstances 不应清理忙碌实例")
        void cleanupIdleShouldNotCleanBusy() {
            LingInstance instance = createMockInstance("1.0.0");
            pool.addInstance(instance, true);

            // 模拟有活跃请求
            instance.tryEnter();
            assertFalse(instance.isIdle());

            pool.moveToDying(instance);

            int cleaned = pool.cleanupIdleInstances(LingInstance::destroy);

            assertEquals(0, cleaned);
            assertEquals(1, pool.getDyingCount());

            // 清理
            instance.exit();
        }

        @Test
        @DisplayName("forceCleanupAll 应清理所有实例")
        void forceCleanupAllShouldWork() {
            // 添加一些实例到死亡队列
            for (int i = 0; i < 3; i++) {
                LingInstance instance = createMockInstance("1.0." + i);
                // 模拟有活跃请求
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
    }

    // ==================== 关闭测试 ====================

    @Nested
    @DisplayName("关闭功能")
    class ShutdownTests {

        @Test
        @DisplayName("shutdown 应清空默认实例")
        void shutdownShouldClearDefault() {
            LingInstance instance = createMockInstance("1.0.0");
            pool.addInstance(instance, true);

            pool.shutdown();

            assertNull(pool.getDefault());
        }

        @Test
        @DisplayName("shutdown 应将所有活跃实例移到死亡队列")
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
        @DisplayName("shutdown 返回的实例应该都被标记为 dying")
        void shutdownInstancesShouldBeDying() {
            LingInstance instance = createMockInstance("1.0.0");
            pool.addInstance(instance, true);

            List<LingInstance> dying = pool.shutdown();

            assertTrue(dying.get(0).isDying());
        }
    }

    // ==================== 可用性检查测试 ====================

    @Nested
    @DisplayName("可用性检查")
    class AvailabilityTests {

        @Test
        @DisplayName("无实例时 hasAvailableInstance 应返回 false")
        void hasAvailableShouldReturnFalseWhenEmpty() {
            assertFalse(pool.hasAvailableInstance());
        }

        @Test
        @DisplayName("有就绪实例时 hasAvailableInstance 应返回 true")
        void hasAvailableShouldReturnTrueWhenReady() {
            LingInstance instance = createMockInstance("1.0.0");
            pool.addInstance(instance, true);

            assertTrue(pool.hasAvailableInstance());
        }

        @Test
        @DisplayName("实例 dying 时 hasAvailableInstance 应返回 false")
        void hasAvailableShouldReturnFalseWhenDying() {
            LingInstance instance = createMockInstance("1.0.0");
            pool.addInstance(instance, true);
            instance.markDying();

            assertFalse(pool.hasAvailableInstance());
        }
    }

    // ==================== 并发测试 ====================

    @Nested
    @DisplayName("并发安全")
    class ConcurrencyTests {

        @Test
        @DisplayName("并发添加实例应安全")
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
                    } catch (Exception e) {
                        // ignore
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
        @DisplayName("并发 moveToDying 应安全")
        void concurrentMoveToDyingShouldBeSafe() throws InterruptedException {
            // 先添加一些实例
            for (int i = 0; i < 5; i++) {
                pool.addInstance(createMockInstance("1.0." + i), i == 0);
            }

            List<LingInstance> instances = pool.getActiveInstances();

            int threadCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        pool.moveToDying(instances.get(index));
                    } catch (Exception e) {
                        // ignore
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
    }

    // ==================== 统计信息测试 ====================

    @Nested
    @DisplayName("统计信息")
    class StatsTests {

        @Test
        @DisplayName("getStats 应返回正确的统计")
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
        @DisplayName("PoolStats toString 应包含关键信息")
        void poolStatsToStringShouldWork() {
            pool.addInstance(createMockInstance("1.0.0"), true);

            String str = pool.getStats().toString();

            assertTrue(str.contains("active=1"));
            assertTrue(str.contains("dying=0"));
            assertTrue(str.contains("hasDefault=true"));
        }
    }
}