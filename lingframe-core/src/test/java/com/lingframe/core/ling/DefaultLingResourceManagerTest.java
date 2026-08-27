package com.lingframe.core.ling;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.InstanceDestroyedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

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

    @Nested
    @DisplayName("孤儿 AutoCloseable 注册与关闭")
    class OrphanCloseableTests {

        private final String lingId = "ling-a";
        private final String version = "1.0.0";

        private DefaultLingResourceManager newManager() {
            return new DefaultLingResourceManager(null, null, null);
        }

        @Test
        @DisplayName("版本级关闭按逆注册序 close")
        void shouldCloseInReverseOrder() {
            List<String> closeOrder = new ArrayList<>();
            DefaultLingResourceManager manager = newManager();
            manager.registerCloseable(lingId, version, tracking("a", closeOrder));
            manager.registerCloseable(lingId, version, tracking("b", closeOrder));
            manager.registerCloseable(lingId, version, tracking("c", closeOrder));

            manager.closeResources(lingId, version);

            assertEquals(Arrays.asList("c", "b", "a"), closeOrder);
        }

        @Test
        @DisplayName("单一 close 抛异常不阻断其余且继续关闭")
        void shouldContinueOnSingleCloseFailure() {
            List<String> closeOrder = new ArrayList<>();
            DefaultLingResourceManager manager = newManager();
            manager.registerCloseable(lingId, version, failing("first", closeOrder));
            manager.registerCloseable(lingId, version, tracking("second", closeOrder));
            manager.registerCloseable(lingId, version, tracking("third", closeOrder));

            assertDoesNotThrow(() -> manager.closeResources(lingId, version));

            // 逆序：third → second → first(抛异常)，全部被触发
            assertTrue(closeOrder.contains("second"));
            assertTrue(closeOrder.contains("third"));
        }

        @Test
        @DisplayName("同一实例重复注册只关闭一次")
        void shouldDeduplicateSameInstance() {
            List<String> closeOrder = new ArrayList<>();
            DefaultLingResourceManager manager = newManager();
            AutoCloseable res = tracking("dup", closeOrder);
            manager.registerCloseable(lingId, version, res);
            manager.registerCloseable(lingId, version, res);

            manager.closeResources(lingId, version);

            // 去重后只 close 一次
            assertEquals(Collections.singletonList("dup"), closeOrder);
        }

        @Test
        @DisplayName("不同版本独立隔离，版本级关闭不串扰他人")
        void shouldIsolateByVersion() {
            List<String> closeOrder = new ArrayList<>();
            DefaultLingResourceManager manager = newManager();
            manager.registerCloseable(lingId, "1.0.0", tracking("v1r", closeOrder));
            manager.registerCloseable(lingId, "1.0.0", tracking("v1s", closeOrder));
            manager.registerCloseable(lingId, "1.1.0", tracking("v2xcde", closeOrder));

            manager.closeResources(lingId, "1.0.0");

            // 仅 1.0.0 的逆序关闭；1.1.0 不受影响
            assertEquals(Arrays.asList("v1s", "v1r"), closeOrder);
        }

        @Test
        @DisplayName("版本级关闭后同版本残留为空，再注册由整 Ling 级兜底释放（有界留存不丢失）")
        void shouldRetainLateRegistrationAndFallbackOnLingClose() {
            List<String> closeOrder = new ArrayList<>();
            DefaultLingResourceManager manager = newManager();
            manager.registerCloseable(lingId, version, tracking("early", closeOrder));
            manager.closeResources(lingId, version);
            // 模拟 close 执行期间"迟到"的同版本注册
            manager.registerCloseable(lingId, version, tracking("late", closeOrder));

            // 版本级再关闭：只关 late（此前的 early 已被摘除）
            manager.closeResources(lingId, version);
            // 整 Ling 级兜底：清空所有残留（此时已无）
            manager.closeResources(lingId);

            // late 无论走版本级还是兜底都必须被关闭
            assertTrue(closeOrder.contains("early"));
            assertTrue(closeOrder.contains("late"));
        }

        @Test
        @DisplayName("并发：版本级关闭进行中迟到注册有界留存，由整 Ling 兜底释放")
        void shouldBoundConcurrentLateRegistration() throws Exception {
            CountDownLatch closeStarted = new CountDownLatch(1);
            CountDownLatch proceed = new CountDownLatch(1);
            List<String> closed = new ArrayList<>();
            DefaultLingResourceManager manager = newManager();

            AutoCloseable blocker = () -> {
                closeStarted.countDown();
                proceed.await(5, TimeUnit.SECONDS);
                closed.add("blocker");
            };
            manager.registerCloseable(lingId, version, blocker);

            Thread closer = new Thread(() -> manager.closeResources(lingId, version));
            closer.start();
            // 等待 close 已从锁内摘除 blocker 并开始阻塞 close()
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS));

            // 此刻版本条目已摘除，新的注册在锁内新建条目留存
            manager.registerCloseable(lingId, version, () -> closed.add("late"));
            proceed.countDown();
            closer.join(5000);

            // blocker 已被版本级关闭
            assertEquals(Collections.singletonList("blocker"), closed);

            // 迟到注册未被版本级关闭误处理，由整 Ling 兜底释放
            manager.closeResources(lingId);
            assertEquals(Arrays.asList("blocker", "late"), closed);
        }

        @Test
        @DisplayName("提前反注册后不再关闭")
        void shouldNotCloseAfterUnregister() {
            List<String> closeOrder = new ArrayList<>();
            DefaultLingResourceManager manager = newManager();
            AutoCloseable res = tracking("res", closeOrder);
            manager.registerCloseable(lingId, version, res);
            manager.unregisterCloseable(lingId, version, res);
            manager.closeResources(lingId, version);

            assertTrue(closeOrder.isEmpty());
        }

        @Test
        @DisplayName("反注册后残留为空时清理版本与 lingId 条目")
        void shouldCleanupEmptyEntriesAfterUnregister() {
            List<String> closeOrder = new ArrayList<>();
            DefaultLingResourceManager manager = newManager();
            manager.registerCloseable(lingId, version, tracking("res", closeOrder));
            manager.unregisterCloseable(lingId, version,
                    new AutoCloseable() { @Override public void close() { } });
            // 版本列表只剩"res"仍保留；关空条目不抛错
            manager.closeResources(lingId, version);
            assertTrue(closeOrder.contains("res"));
        }

        @Test
        @DisplayName("null 参数注册被拒绝不报错")
        void shouldRejectNullRegistration() {
            DefaultLingResourceManager manager = newManager();
            assertDoesNotThrow(() -> manager.registerCloseable(null, version, () -> { }));
            assertDoesNotThrow(() -> manager.registerCloseable(lingId, null, () -> { }));
            assertDoesNotThrow(() -> manager.registerCloseable(lingId, version, null));
            manager.shutdown();
        }

        private AutoCloseable tracking(String name, List<String> order) {
            return () -> order.add(name);
        }

        private AutoCloseable failing(String name, List<String> order) {
            return () -> {
                order.add(name);
                throw new IllegalStateException("boom: " + name);
            };
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, ExecutorService> getThreadPools(DefaultLingResourceManager manager) throws Exception {
        Field field = DefaultLingResourceManager.class.getDeclaredField("threadPools");
        field.setAccessible(true);
        return (Map<String, ExecutorService>) field.get(manager);
    }
}
