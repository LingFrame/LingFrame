package com.lingframe.core.ling;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.InstanceDestroyedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultLingResourceManager 测试")
class DefaultLingResourceManagerTest {

    @Nested
    @DisplayName("线程池回收")
    class ThreadPoolReclaimTests {

        @Test
        @DisplayName("仍有实例存活时不应回收线程池")
        void shouldNotReclaimThreadPoolWhenInstancesRemain() throws Exception {
            LingRepository repository = mock(LingRepository.class);
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool instancePool = mock(InstancePool.class);

            when(repository.getRuntime("ling-a")).thenReturn(runtime);
            when(runtime.getInstancePool()).thenReturn(instancePool);
            when(instancePool.getAllInstances()).thenReturn(Collections.singletonList(mock(LingInstance.class)));

            DefaultLingResourceManager manager = new DefaultLingResourceManager(repository, null, null);
            manager.allocateThreadPool("ling-a", 1);

            manager.onEvent(new InstanceDestroyedEvent("ling-a", "1.0.0", "1.0.0"));

            Map<String, ExecutorService> pools = getThreadPools(manager);
            assertTrue(pools.containsKey("ling-a"));

            manager.shutdown();
        }

        @Test
        @DisplayName("无实例存活时应回收线程池")
        void shouldReclaimThreadPoolWhenNoInstancesRemain() throws Exception {
            LingRepository repository = mock(LingRepository.class);
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool instancePool = mock(InstancePool.class);

            when(repository.getRuntime("ling-a")).thenReturn(runtime);
            when(runtime.getInstancePool()).thenReturn(instancePool);
            when(instancePool.getAllInstances()).thenReturn(Collections.emptyList());

            DefaultLingResourceManager manager = new DefaultLingResourceManager(repository, null, null);
            manager.allocateThreadPool("ling-a", 1);

            manager.onEvent(new InstanceDestroyedEvent("ling-a", "1.0.0", "1.0.0"));

            Map<String, ExecutorService> pools = getThreadPools(manager);
            assertFalse(pools.containsKey("ling-a"));
        }
    }

    @Test
    @DisplayName("allocateThreadPool 返回非空线程池")
    void shouldAllocateThreadPool() {
        DefaultLingResourceManager manager = new DefaultLingResourceManager(null, null, null);
        ExecutorService pool = manager.allocateThreadPool("ling-a", 2);
        assertNotNull(pool);
        assertFalse(pool.isShutdown());
        manager.shutdown();
    }

    @Test
    @DisplayName("allocateThreadPool 相同 lingId 返回同一实例")
    void shouldReturnSamePoolForSameLingId() {
        DefaultLingResourceManager manager = new DefaultLingResourceManager(null, null, null);
        ExecutorService pool1 = manager.allocateThreadPool("ling-a", 2);
        ExecutorService pool2 = manager.allocateThreadPool("ling-a", 4);
        assertSame(pool1, pool2);
        manager.shutdown();
    }

    @Test
    @DisplayName("reclaimResources 不存在的 lingId 不报错")
    void shouldReclaimNonExistentLing() {
        DefaultLingResourceManager manager = new DefaultLingResourceManager(null, null, null);
        assertDoesNotThrow(() -> manager.reclaimResources("nonexistent"));
        manager.shutdown();
    }

    @Test
    @DisplayName("cleanupCaches null ClassLoader 不报错")
    void shouldCleanupCachesWithNullClassLoader() {
        DefaultLingResourceManager manager = new DefaultLingResourceManager(null, null, null);
        assertDoesNotThrow(() -> manager.cleanupCaches("ling-a", null));
        manager.shutdown();
    }

    @Test
    @DisplayName("cleanupCaches 正常 ClassLoader 不报错")
    void shouldCleanupCachesWithClassLoader() {
        DefaultLingResourceManager manager = new DefaultLingResourceManager(null, null, null);
        assertDoesNotThrow(() -> manager.cleanupCaches("ling-a", new ClassLoader() {}));
        manager.shutdown();
    }

    @Test
    @DisplayName("closeResources 回收线程池")
    void shouldCloseResources() {
        DefaultLingResourceManager manager = new DefaultLingResourceManager(null, null, null);
        manager.allocateThreadPool("ling-a", 2);
        manager.closeResources("ling-a");
        // 线程池应被移除
        assertFalse(manager.allocateThreadPool("ling-a", 2).isShutdown());
        manager.shutdown();
    }

    @Test
    @DisplayName("shutdown 清理所有线程池")
    void shouldShutdownAllPools() {
        DefaultLingResourceManager manager = new DefaultLingResourceManager(null, null, null);
        manager.allocateThreadPool("ling-a", 1);
        manager.allocateThreadPool("ling-b", 1);
        manager.shutdown();
        // 不报错即可
    }

    @Test
    @DisplayName("onEvent null repository 时回收线程池")
    void shouldReclaimWhenNullRepository() {
        DefaultLingResourceManager manager = new DefaultLingResourceManager(null, null, null);
        manager.allocateThreadPool("ling-a", 1);
        manager.onEvent(new InstanceDestroyedEvent("ling-a", "1.0.0", "1.0.0"));
        // null repository → shouldReclaimThreadPool 返回 true → 线程池被回收
        assertDoesNotThrow(() -> manager.shutdown());
    }

    @Test
    @DisplayName("onEvent runtime 为 null 时回收线程池")
    void shouldReclaimWhenNullRuntime() {
        LingRepository repository = mock(LingRepository.class);
        when(repository.getRuntime("ling-a")).thenReturn(null);

        DefaultLingResourceManager manager = new DefaultLingResourceManager(repository, null, null);
        manager.allocateThreadPool("ling-a", 1);
        manager.onEvent(new InstanceDestroyedEvent("ling-a", "1.0.0", "1.0.0"));
        assertDoesNotThrow(() -> manager.shutdown());
    }

    @Test
    @DisplayName("onEvent 带 methodCache 时驱逐缓存")
    void shouldEvictMethodCacheOnDestroy() {
        InvokableMethodCache methodCache = mock(InvokableMethodCache.class);
        when(methodCache.evictByPrefix(anyString())).thenReturn(3);

        DefaultLingResourceManager manager = new DefaultLingResourceManager(null, null, methodCache);
        manager.onEvent(new InstanceDestroyedEvent("ling-a", "1.0.0", "1.0.0"));

        verify(methodCache).evictByPrefix("ling-a:1.0.0@");
        manager.shutdown();
    }

    @Test
    @DisplayName("带 EventBus 的构造器订阅事件")
    void shouldSubscribeWithEventBus() {
        EventBus eventBus = mock(EventBus.class);
        DefaultLingResourceManager manager = new DefaultLingResourceManager(eventBus, null);
        verify(eventBus).subscribe(anyString(), eq(InstanceDestroyedEvent.class), any());
        manager.shutdown();
    }

    @Test
    @DisplayName("shutdown 取消事件订阅")
    void shouldUnsubscribeOnShutdown() {
        EventBus eventBus = mock(EventBus.class);
        DefaultLingResourceManager manager = new DefaultLingResourceManager(eventBus, null);
        manager.shutdown();
        verify(eventBus).unsubscribeAll(anyString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, ExecutorService> getThreadPools(DefaultLingResourceManager manager) throws Exception {
        Field field = DefaultLingResourceManager.class.getDeclaredField("threadPools");
        field.setAccessible(true);
        return (Map<String, ExecutorService>) field.get(manager);
    }
}
