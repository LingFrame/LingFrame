package com.lingframe.core.ling;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.context.LingContext;
import com.lingframe.api.exception.ServiceUnavailableException;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.exception.LingInstallException;
import com.lingframe.core.spi.LingContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LingLifecycleManagerTest {

    private static final String LING_ID = "test-ling";

    @Mock
    private EventBus externalEventBus;

    @Mock
    private LingContext lingContext;

    private ScheduledExecutorService scheduler;
    private InstancePool instancePool;
    private LingRuntimeConfig config;
    private LingLifecycleManager lifecycleManager;
    private InstanceCoordinator bootstrapCoordinator;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        config = LingRuntimeConfig.builder()
                .maxHistorySnapshots(5)
                .dyingCheckIntervalSeconds(1)
                .forceCleanupDelaySeconds(2)
                .build();

        instancePool = new InstancePool(LING_ID, config.getMaxHistorySnapshots());
        lifecycleManager = new LingLifecycleManager(
                LING_ID,
                instancePool,
                externalEventBus,
                scheduler,
                config,
                null);
        bootstrapCoordinator = new InstanceCoordinator(null);
    }

    @AfterEach
    void tearDown() {
        if (lifecycleManager != null && !lifecycleManager.isShutdown()) {
            lifecycleManager.shutdown();
        }
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
    }

    @Nested
    class InitialStateTests {

        @Test
        void newManagerShouldNotBeShutdown() {
            assertFalse(lifecycleManager.isShutdown());
        }

        @Test
        void initialStatsShouldBeCorrect() {
            LingLifecycleManager.LifecycleStats stats = lifecycleManager.getStats();

            assertFalse(stats.isShutdown());
            assertFalse(stats.forceCleanupScheduled());
            assertEquals(0, stats.dyingCount());
        }
    }

    @Nested
    class AddInstanceTests {

        @Test
        void addInstanceShouldSucceed() {
            LingInstance instance = createMockInstance("1.0.0");

            lifecycleManager.addInstance(instance, lingContext, true);

            assertTrue(instance.isReady());
            assertEquals(instance, instancePool.getDefault());
            verify(externalEventBus, atLeastOnce()).publish(any());
        }

        @Test
        void addNonDefaultShouldNotReplaceDefault() {
            LingInstance v1 = createMockInstance("1.0.0");
            LingInstance v2 = createMockInstance("2.0.0");

            lifecycleManager.addInstance(v1, lingContext, true);
            lifecycleManager.addInstance(v2, lingContext, false);

            assertEquals(v1, instancePool.getDefault());
            assertEquals(2, instancePool.getActiveInstances().size());
        }

        @Test
        void upgradeShouldMoveOldToDying() {
            LingInstance v1 = createMockInstance("1.0.0");
            LingInstance v2 = createMockInstance("2.0.0");

            lifecycleManager.addInstance(v1, lingContext, true);
            lifecycleManager.addInstance(v2, lingContext, true);

            assertTrue(v1.isDying());
            assertFalse(v2.isDying());
            assertEquals(v2, instancePool.getDefault());
        }

        @Test
        void containerStartFailureShouldThrowLingInstallException() {
            LingContainer container = mock(LingContainer.class);
            doThrow(new RuntimeException("Start failed")).when(container).start(any());

            LingDefinition definition = new LingDefinition();
            definition.setId(LING_ID);
            definition.setVersion("1.0.0");

            LingInstance instance = new LingInstance(container, definition, new EventBus());
            bootstrapCoordinator.prepare(instance);
            bootstrapCoordinator.start(instance);

            assertThrows(LingInstallException.class,
                    () -> lifecycleManager.addInstance(instance, lingContext, true));
        }

        @Test
        void addAfterShutdownShouldThrow() {
            lifecycleManager.shutdown();

            LingInstance instance = createMockInstance("1.0.0");

            assertThrows(ServiceUnavailableException.class,
                    () -> lifecycleManager.addInstance(instance, lingContext, true));
        }

        @Test
        void backpressureShouldPreventTooManyInstances() {
            for (int i = 0; i < config.getMaxHistorySnapshots(); i++) {
                LingInstance instance = createMockInstance("old-" + i);
                bootstrapCoordinator.markReady(instance);
                instancePool.addInstance(instance, false);
                instance.tryEnter();
                instancePool.moveToDying(instance);
            }

            LingInstance newInstance = createMockInstance("new");

            assertThrows(ServiceUnavailableException.class,
                    () -> lifecycleManager.addInstance(newInstance, lingContext, true));
        }
    }

    @Nested
    class ShutdownTests {

        @Test
        void shutdownShouldSetState() {
            lifecycleManager.shutdown();
            assertTrue(lifecycleManager.isShutdown());
        }

        @Test
        void shutdownShouldBeIdempotent() {
            assertDoesNotThrow(() -> {
                lifecycleManager.shutdown();
                lifecycleManager.shutdown();
                lifecycleManager.shutdown();
            });
        }

        @Test
        void shutdownShouldClearInstancePool() {
            LingInstance instance = createMockInstance("1.0.0");
            lifecycleManager.addInstance(instance, lingContext, true);

            lifecycleManager.shutdown();

            assertNull(instancePool.getDefault());
            assertTrue(instance.isDying());
        }
    }

    @Nested
    class CleanupTests {

        @Test
        void cleanupShouldDestroyIdleInstances() {
            LingInstance instance = createMockInstance("1.0.0");
            lifecycleManager.addInstance(instance, lingContext, true);

            instancePool.moveToDying(instance);
            assertTrue(instance.isIdle());

            int cleaned = lifecycleManager.cleanupIdleInstances();

            assertEquals(1, cleaned);
            assertTrue(instance.isDestroyed());
        }

        @Test
        void cleanupShouldNotDestroyBusyInstances() {
            LingInstance instance = createMockInstance("1.0.0");
            lifecycleManager.addInstance(instance, lingContext, true);

            instance.tryEnter();
            instancePool.moveToDying(instance);

            int cleaned = lifecycleManager.cleanupIdleInstances();

            assertEquals(0, cleaned);
            assertFalse(instance.isDestroyed());

            instance.exit();
        }

        @Test
        void periodicCleanupShouldRun() {
            LingInstance instance = createMockInstance("1.0.0");
            lifecycleManager.addInstance(instance, lingContext, true);
            instancePool.moveToDying(instance);

            await()
                    .atMost(3, TimeUnit.SECONDS)
                    .until(instance::isDestroyed);

            assertTrue(instance.isDestroyed());
        }

        @Test
        void forceCleanupShouldDestroyAll() {
            for (int i = 0; i < 3; i++) {
                LingInstance instance = createMockInstance("1.0." + i);
                lifecycleManager.addInstance(instance, lingContext, false);
                instance.tryEnter();
                instancePool.moveToDying(instance);
            }

            assertEquals(3, instancePool.getDyingCount());

            lifecycleManager.forceCleanupAll();

            assertEquals(0, instancePool.getDyingCount());
        }
    }

    @Nested
    class ExternalEventTests {

        @Test
        void addInstanceShouldPublishStartEvents() {
            LingInstance instance = createMockInstance("1.0.0");

            lifecycleManager.addInstance(instance, lingContext, true);

            verify(externalEventBus, atLeast(2)).publish(any());
        }

        @Test
        void destroyInstanceShouldPublishStopEvents() {
            LingInstance instance = createMockInstance("1.0.0");
            lifecycleManager.addInstance(instance, lingContext, true);

            reset(externalEventBus);

            instancePool.moveToDying(instance);
            lifecycleManager.cleanupIdleInstances();

            verify(externalEventBus, atLeast(2)).publish(any());
        }
    }

    @Nested
    class StatsTests {

        @Test
        void getStatsShouldWork() {
            LingInstance instance = createMockInstance("1.0.0");
            lifecycleManager.addInstance(instance, lingContext, true);
            instancePool.moveToDying(instance);

            LingLifecycleManager.LifecycleStats stats = lifecycleManager.getStats();

            assertFalse(stats.isShutdown());
            assertEquals(1, stats.dyingCount());
        }

        @Test
        void statsAfterShutdownShouldBeCorrect() {
            lifecycleManager.shutdown();

            LingLifecycleManager.LifecycleStats stats = lifecycleManager.getStats();

            assertTrue(stats.isShutdown());
        }

        @Test
        void statsToStringShouldWork() {
            String value = lifecycleManager.getStats().toString();

            assertTrue(value.contains("shutdown=false"));
            assertTrue(value.contains("dying=0"));
        }
    }

    @Nested
    class ConcurrencyTests {

        @Test
        void concurrentAddShouldBeSafe() throws InterruptedException {
            int threadCount = 10;
            ExecutorService testExecutor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                testExecutor.submit(() -> {
                    try {
                        startLatch.await();
                        LingInstance instance = createMockInstance("1.0." + index);
                        lifecycleManager.addInstance(instance, lingContext, index == 0);
                        successCount.incrementAndGet();
                    } catch (Exception ignored) {
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
            testExecutor.shutdown();

            assertTrue(completed);
            assertTrue(successCount.get() > 0);
        }

        @Test
        void concurrentCleanupShouldBeSafe() throws InterruptedException {
            for (int i = 0; i < 5; i++) {
                LingInstance instance = createMockInstance("1.0." + i);
                lifecycleManager.addInstance(instance, lingContext, i == 0);
                instancePool.moveToDying(instance);
            }

            int threadCount = 10;
            ExecutorService testExecutor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                testExecutor.submit(() -> {
                    try {
                        startLatch.await();
                        lifecycleManager.cleanupIdleInstances();
                    } catch (Exception ignored) {
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
            testExecutor.shutdown();

            assertTrue(completed);
            assertEquals(0, instancePool.getDyingCount());
        }
    }

    private LingInstance createMockInstance(String version) {
        LingContainer container = mock(LingContainer.class);
        when(container.isActive()).thenReturn(true);
        doNothing().when(container).start(any());
        doNothing().when(container).stop();

        LingDefinition definition = new LingDefinition();
        definition.setId(LING_ID);
        definition.setVersion(version);

        LingInstance instance = new LingInstance(container, definition, new EventBus());
        bootstrapCoordinator.prepare(instance);
        bootstrapCoordinator.start(instance);
        return instance;
    }
}
