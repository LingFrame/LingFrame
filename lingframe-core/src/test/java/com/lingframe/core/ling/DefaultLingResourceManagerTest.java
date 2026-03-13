package com.lingframe.core.ling;

import com.lingframe.core.event.InstanceDestroyedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultLingResourceManagerTest {

    @Test
    @DisplayName("仍有实例时不应回收线程池")
    void shouldNotReclaimThreadPoolWhenInstancesRemain() throws Exception {
        LingRepository repository = mock(LingRepository.class);
        LingRuntime runtime = mock(LingRuntime.class);
        InstancePool instancePool = mock(InstancePool.class);

        when(repository.getRuntime("ling-a")).thenReturn(runtime);
        when(runtime.getInstancePool()).thenReturn(instancePool);
        when(instancePool.getAllInstances()).thenReturn(Collections.singletonList(mock(LingInstance.class)));

        DefaultLingResourceManager manager = new DefaultLingResourceManager(repository, null, null);
        manager.allocateThreadPool("ling-a", 1);

        manager.onEvent(new InstanceDestroyedEvent("ling-a", "1.0.0"));

        Map<String, ExecutorService> pools = getThreadPools(manager);
        assertTrue(pools.containsKey("ling-a"));

        manager.shutdown();
    }

    @Test
    @DisplayName("无实例时应回收线程池")
    void shouldReclaimThreadPoolWhenNoInstancesRemain() throws Exception {
        LingRepository repository = mock(LingRepository.class);
        LingRuntime runtime = mock(LingRuntime.class);
        InstancePool instancePool = mock(InstancePool.class);

        when(repository.getRuntime("ling-a")).thenReturn(runtime);
        when(runtime.getInstancePool()).thenReturn(instancePool);
        when(instancePool.getAllInstances()).thenReturn(Collections.emptyList());

        DefaultLingResourceManager manager = new DefaultLingResourceManager(repository, null, null);
        manager.allocateThreadPool("ling-a", 1);

        manager.onEvent(new InstanceDestroyedEvent("ling-a", "1.0.0"));

        Map<String, ExecutorService> pools = getThreadPools(manager);
        assertFalse(pools.containsKey("ling-a"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, ExecutorService> getThreadPools(DefaultLingResourceManager manager) throws Exception {
        Field field = DefaultLingResourceManager.class.getDeclaredField("threadPools");
        field.setAccessible(true);
        return (Map<String, ExecutorService>) field.get(manager);
    }
}
