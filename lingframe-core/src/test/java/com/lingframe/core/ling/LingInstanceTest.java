package com.lingframe.core.ling;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.spi.LingContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LingInstanceTest {

    @Mock
    private LingContainer container;

    private LingDefinition definition;
    private LingInstance instance;
    private InstanceCoordinator instanceCoordinator;

    @BeforeEach
    void setUp() {
        definition = createDefinition();
        when(container.isActive()).thenReturn(true);
        instance = new LingInstance(container, definition, new EventBus());
        instanceCoordinator = new InstanceCoordinator(null);
    }

    @Nested
    class ConstructorTests {

        @Test
        void shouldConstructSuccessfully() {
            assertNotNull(instance);
            assertEquals("1.0.0", instance.getVersion());
            assertNotNull(instance.getContainer());
            assertNotNull(instance.getDefinition());
        }

        @Test
        void shouldThrowWhenVersionIsNull() {
            definition.setVersion(null);
            assertThrows(InvalidArgumentException.class,
                    () -> new LingInstance(container, definition, new EventBus()));
        }

        @Test
        void shouldThrowWhenVersionIsBlank() {
            definition.setVersion(" ");
            assertThrows(InvalidArgumentException.class,
                    () -> new LingInstance(container, definition, new EventBus()));
        }

        @Test
        void shouldThrowWhenContainerIsNull() {
            assertThrows(NullPointerException.class,
                    () -> new LingInstance(null, definition, new EventBus()));
        }

        @Test
        void shouldThrowWhenDefinitionIsNull() {
            assertThrows(NullPointerException.class,
                    () -> new LingInstance(container, null, new EventBus()));
        }
    }

    @Nested
    class StateManagementTests {

        @Test
        void newInstanceShouldNotBeReady() {
            assertFalse(instance.isReady());
        }

        @Test
        void shouldBeReadyAfterCoordinatorMarksReady() {
            prepareReady(instance);
            assertTrue(instance.isReady());
        }

        @Test
        void shouldNotBeReadyWhenContainerInactive() {
            prepareReady(instance);
            assertTrue(instance.isReady());

            when(container.isActive()).thenReturn(false);
            assertFalse(instance.isReady());
        }

        @Test
        void shouldNotBeReadyWhenStopping() {
            prepareReady(instance);
            assertTrue(instance.isReady());

            stop(instance);
            assertFalse(instance.isReady());
            assertTrue(instance.isDying());
        }

        @Test
        void shouldNotBeReadyAfterTearDown() {
            prepareReady(instance);
            destroy(instance);

            assertFalse(instance.isReady());
            assertTrue(instance.isDestroyed());
        }

        @Test
        void tearDownShouldBeIdempotent() {
            prepareReady(instance);

            destroy(instance);
            destroy(instance);
            destroy(instance);

            verify(container, times(1)).stop();
        }
    }

    @Nested
    class ReferenceCountingTests {

        @Test
        void shouldBeIdleInitially() {
            assertTrue(instance.isIdle());
            assertEquals(0, instance.getActiveRequestCount());
        }

        @Test
        void tryEnterShouldFailWhenNotReady() {
            assertFalse(instance.tryEnter());
            assertEquals(0, instance.getActiveRequestCount());
        }

        @Test
        void tryEnterShouldSucceedWhenReady() {
            prepareReady(instance);

            assertTrue(instance.tryEnter());
            assertEquals(1, instance.getActiveRequestCount());
            assertFalse(instance.isIdle());
        }

        @Test
        void tryEnterShouldFailWhenDying() {
            prepareReady(instance);
            stop(instance);

            assertFalse(instance.tryEnter());
            assertEquals(0, instance.getActiveRequestCount());
        }

        @Test
        void exitShouldDecrementCount() {
            prepareReady(instance);

            instance.tryEnter();
            instance.tryEnter();
            assertEquals(2, instance.getActiveRequestCount());

            instance.exit();
            assertEquals(1, instance.getActiveRequestCount());

            instance.exit();
            assertEquals(0, instance.getActiveRequestCount());
            assertTrue(instance.isIdle());
        }

        @Test
        void exitShouldNotGoNegative() {
            prepareReady(instance);
            instance.tryEnter();

            instance.exit();
            instance.exit();
            instance.exit();

            assertTrue(instance.getActiveRequestCount() >= 0);
        }
    }

    @Nested
    class LabelManagementTests {

        @Test
        void getLabelsShouldReturnUnmodifiableView() {
            Map<String, String> labels = instance.getLabels();
            assertThrows(UnsupportedOperationException.class, () -> labels.put("key", "value"));
        }

        @Test
        void addLabelShouldWork() {
            instance.addLabel("env", "canary");
            instance.addLabel("tenant", "T1");

            Map<String, String> labels = instance.getLabels();
            assertEquals("canary", labels.get("env"));
            assertEquals("T1", labels.get("tenant"));
        }

        @Test
        void addLabelShouldIgnoreNulls() {
            instance.addLabel(null, "value");
            instance.addLabel("key", null);
            instance.addLabel(null, null);

            assertTrue(instance.getLabels().isEmpty());
        }

        @Test
        void addLabelsShouldAddBatch() {
            HashMap<String, String> labels = new HashMap<>();
            labels.put("a", "1");
            labels.put("b", "2");
            instance.addLabels(labels);

            assertEquals(2, instance.getLabels().size());
        }
    }

    @Nested
    class ConcurrencyTests {

        @Test
        void concurrentEnterExitShouldBeConsistent() throws InterruptedException {
            prepareReady(instance);

            int threadCount = 100;
            int operationsPerThread = 1000;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successfulEnters = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < operationsPerThread; j++) {
                            if (instance.tryEnter()) {
                                successfulEnters.incrementAndGet();
                                Thread.yield();
                                instance.exit();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertTrue(completed);
            assertEquals(0, instance.getActiveRequestCount());
            assertTrue(instance.isIdle());
            assertTrue(successfulEnters.get() > 0);
        }

        @Test
        void stoppingShouldBlockNewEnters() throws InterruptedException {
            prepareReady(instance);

            int threadCount = 50;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successAfterStopping = new AtomicInteger(0);

            for (int i = 0; i < 10; i++) {
                assertTrue(instance.tryEnter());
            }

            stop(instance);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        if (instance.tryEnter()) {
                            successAfterStopping.incrementAndGet();
                            instance.exit();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertTrue(completed);
            assertEquals(0, successAfterStopping.get());
            assertEquals(10, instance.getActiveRequestCount());
        }
    }

    @Test
    void toStringShouldContainKeyInfo() {
        prepareReady(instance);
        instance.tryEnter();

        String value = instance.toString();

        assertTrue(value.contains("1.0.0"));
        assertTrue(value.contains("state=READY"));
        assertTrue(value.contains("activeRequests=1"));
    }

    private LingDefinition createDefinition() {
        LingDefinition current = new LingDefinition();
        current.setId("test-ling");
        current.setVersion("1.0.0");
        return current;
    }

    private void prepareReady(LingInstance target) {
        instanceCoordinator.prepare(target);
        instanceCoordinator.start(target);
        instanceCoordinator.markReady(target);
    }

    private void stop(LingInstance target) {
        instanceCoordinator.stop(target);
    }

    private void destroy(LingInstance target) {
        instanceCoordinator.tearDown(target);
    }
}
