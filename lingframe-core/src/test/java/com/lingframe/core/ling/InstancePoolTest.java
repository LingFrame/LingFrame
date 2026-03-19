package com.lingframe.core.ling;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.spi.LingContainer;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InstancePoolTest {

    private static final String LING_ID = "test-ling";
    private static final int MAX_DYING = 5;

    private InstancePool pool;
    private InstanceCoordinator instanceCoordinator;

    @BeforeEach
    void setUp() {
        pool = new InstancePool(LING_ID, MAX_DYING);
        instanceCoordinator = new InstanceCoordinator(null);
        pool.setInstanceCoordinator(instanceCoordinator);
    }

    @Nested
    class InitialStateTests {

        @Test
        void newPoolShouldHaveNoDefault() {
            assertNull(pool.getDefault());
            assertNull(pool.getVersion());
        }

        @Test
        void newPoolShouldHaveNoActiveInstances() {
            assertTrue(pool.getActiveInstances().isEmpty());
        }

        @Test
        void newPoolShouldAllowAddInstance() {
            assertTrue(pool.canAddInstance());
        }

        @Test
        void newPoolStatsShouldBeCorrect() {
            InstancePool.PoolStats stats = pool.getStats();
            assertEquals(0, stats.activeCount());
            assertEquals(0, stats.dyingCount());
            assertFalse(stats.hasDefault());
        }
    }

    @Nested
    class AddInstanceTests {

        @Test
        void addDefaultInstanceShouldSetDefault() {
            LingInstance instance = createMockInstance("1.0.0");

            LingInstance old = pool.addInstance(instance, true);

            assertNull(old);
            assertEquals(instance, pool.getDefault());
            assertEquals("1.0.0", pool.getVersion());
            assertEquals(1, pool.getActiveInstances().size());
        }

        @Test
        void addNonDefaultInstanceShouldNotSetDefault() {
            LingInstance instance = createMockInstance("1.0.0");

            pool.addInstance(instance, false);

            assertNull(pool.getDefault());
            assertEquals(1, pool.getActiveInstances().size());
        }

        @Test
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
        void addNullShouldThrow() {
            assertThrows(InvalidArgumentException.class, () -> pool.addInstance(null, true));
        }
    }

    @Nested
    class DyingQueueTests {

        @Test
        void moveToDyingShouldWork() {
            LingInstance instance = createMockInstance("1.0.0");
            pool.addInstance(instance, true);

            pool.moveToDying(instance);

            assertTrue(instance.isDying());
            assertEquals(0, pool.getActiveInstances().size());
            assertEquals(1, pool.getDyingCount());
        }

        @Test
        void moveToDyingNullShouldBeSafe() {
            assertDoesNotThrow(() -> pool.moveToDying(null));
        }

        @Test
        void canAddInstanceShouldReturnFalseWhenFull() {
            for (int i = 0; i < MAX_DYING; i++) {
                LingInstance instance = createMockInstance("1.0." + i);
                pool.addInstance(instance, false);
                pool.moveToDying(instance);
            }

            assertFalse(pool.canAddInstance());
            assertEquals(MAX_DYING, pool.getDyingCount());
        }
    }

    @Nested
    class CleanupTests {

        @Test
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
    }

    @Nested
    class ShutdownTests {

        @Test
        void shutdownShouldClearDefault() {
            LingInstance instance = createMockInstance("1.0.0");
            pool.addInstance(instance, true);

            pool.shutdown();

            assertNull(pool.getDefault());
        }

        @Test
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
        void shutdownInstancesShouldBeDying() {
            LingInstance instance = createMockInstance("1.0.0");
            pool.addInstance(instance, true);

            List<LingInstance> dying = pool.shutdown();

            assertTrue(dying.get(0).isDying());
        }
    }

    @Nested
    class AvailabilityTests {

        @Test
        void hasAvailableShouldReturnFalseWhenEmpty() {
            assertFalse(pool.hasAvailableInstance());
        }

        @Test
        void hasAvailableShouldReturnTrueWhenReady() {
            LingInstance instance = createMockInstance("1.0.0");
            pool.addInstance(instance, true);

            assertTrue(pool.hasAvailableInstance());
        }

        @Test
        void hasAvailableShouldReturnFalseWhenDying() {
            LingInstance instance = createMockInstance("1.0.0");
            pool.addInstance(instance, true);
            instanceCoordinator.stop(instance);

            assertFalse(pool.hasAvailableInstance());
        }
    }

    @Nested
    class ConcurrencyTests {

        @Test
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
    }

    @Nested
    class StatsTests {

        @Test
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
        void poolStatsToStringShouldWork() {
            pool.addInstance(createMockInstance("1.0.0"), true);

            String value = pool.getStats().toString();

            assertTrue(value.contains("active=1"));
            assertTrue(value.contains("dying=0"));
            assertTrue(value.contains("hasDefault=true"));
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
