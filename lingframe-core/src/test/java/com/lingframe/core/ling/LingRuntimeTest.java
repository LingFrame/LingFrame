package com.lingframe.core.ling;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.event.LingEventListener;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.RuntimeStateChangedEvent;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.spi.LingContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LingRuntimeTest {

    private static final String LING_ID = "test-ling";

    @Mock
    private EventBus eventBus;

    private InstanceCoordinator instanceCoordinator;
    private RuntimeCoordinator runtimeCoordinator;
    private LingRuntime runtime;

    @BeforeEach
    void setUp() {
        instanceCoordinator = new InstanceCoordinator(eventBus);
        runtimeCoordinator = new RuntimeCoordinator(eventBus);
        runtime = new LingRuntime(LING_ID, LingRuntimeConfig.defaults(), eventBus,
                instanceCoordinator, runtimeCoordinator);
    }

    @Test
    void shouldHaveCorrectInitialValues() {
        assertEquals(LING_ID, runtime.getLingId());
        assertNotNull(runtime.getConfig());
        assertNotNull(runtime.getInstancePool());
        assertEquals(RuntimeStatus.INACTIVE, runtime.currentStatus());
        assertFalse(runtime.isAvailable());
    }

    @Test
    void shouldGetReadyInstances() {
        assertTrue(runtime.getReadyInstances().isEmpty());

        LingInstance readyInstance = createMockInstance("1.0.0", true);
        runtime.getInstancePool().addInstance(readyInstance, true);

        List<LingInstance> readyInstances = runtime.getReadyInstances();
        assertEquals(1, readyInstances.size());
        assertSame(readyInstance, readyInstances.get(0));
    }

    @Test
    void availabilityShouldDependOnCoordinatorStateAndReadyInstances() {
        assertFalse(runtime.isAvailable());

        runtime.getInstancePool().addInstance(createMockInstance("1.0.0", true), true);
        assertFalse(runtime.isAvailable());

        runtimeCoordinator.transition(LING_ID, RuntimeStatus.ACTIVE);
        assertTrue(runtime.isAvailable());
    }

    @Test
    void shouldRecordTrafficStats() {
        runtime.recordRequest(false);
        runtime.recordRequest(false);
        runtime.recordRequest(true);

        assertEquals(3, runtime.getTotalRequests().get());
        assertEquals(2, runtime.getStableRequests().get());
        assertEquals(1, runtime.getCanaryRequests().get());
    }

    @Test
    void shouldTrackActiveRequests() {
        runtime.startRequest();
        runtime.startRequest();
        assertEquals(2, runtime.getActiveRequests().get());

        runtime.endRequest();
        assertEquals(1, runtime.getActiveRequests().get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldShutdownPoolOnRemovedEvent() {
        InstancePool pool = runtime.getInstancePool();
        LingInstance oldInstance = createMockInstance("1.0.1", true);
        pool.addInstance(oldInstance, false);

        ArgumentCaptor<LingEventListener<RuntimeStateChangedEvent>> captor =
                ArgumentCaptor.forClass(LingEventListener.class);
        verify(eventBus).subscribe(eq(LING_ID), eq(RuntimeStateChangedEvent.class), captor.capture());

        LingEventListener<RuntimeStateChangedEvent> listener = captor.getValue();
        listener.onEvent(new RuntimeStateChangedEvent(LING_ID, RuntimeStatus.ACTIVE, RuntimeStatus.REMOVED));

        LingInstance newInstance = createMockInstance("1.0.2", true);
        LingInstance result = pool.addInstance(newInstance, false);

        assertNull(result);
        assertTrue(oldInstance.isDying());
        assertTrue(pool.getActiveInstances().isEmpty());
        assertFalse(pool.getActiveInstances().contains(newInstance));
    }

    @Test
    void shouldHandleNullConfig() {
        LingRuntime nullConfigRuntime = new LingRuntime("null-id", null, eventBus,
                new RuntimeCoordinator(eventBus));
        assertNotNull(nullConfigRuntime.getConfig());
        assertEquals(RuntimeStatus.INACTIVE, nullConfigRuntime.currentStatus());
    }

    @Test
    void shouldReflectCoordinatorTransitions() {
        LingRuntime coordinatedRuntime = new LingRuntime(LING_ID, LingRuntimeConfig.defaults(), null,
                instanceCoordinator, runtimeCoordinator);

        assertEquals(RuntimeStatus.INACTIVE, coordinatedRuntime.currentStatus());

        runtimeCoordinator.transition(LING_ID, RuntimeStatus.ACTIVE);
        assertEquals(RuntimeStatus.ACTIVE, coordinatedRuntime.currentStatus());
    }

    private LingInstance createMockInstance(String version, boolean ready) {
        LingContainer container = mock(LingContainer.class);
        lenient().when(container.isActive()).thenReturn(ready);
        lenient().when(container.getClassLoader()).thenReturn(getClass().getClassLoader());

        LingDefinition definition = new LingDefinition();
        definition.setId(LING_ID);
        definition.setVersion(version);

        LingInstance instance = new LingInstance(container, definition, eventBus);
        if (ready) {
            prepareReady(instance);
        }
        return instance;
    }

    private void prepareReady(LingInstance instance) {
        instanceCoordinator.prepare(instance);
        instanceCoordinator.start(instance);
        instanceCoordinator.markReady(instance);
    }
}
