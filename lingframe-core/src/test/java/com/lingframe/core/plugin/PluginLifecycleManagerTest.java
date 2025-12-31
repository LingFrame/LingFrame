package com.lingframe.core.plugin;

import com.lingframe.api.config.PluginDefinition;
import com.lingframe.api.context.PluginContext;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.spi.PluginContainer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PluginLifecycleManager 单元测试")
public class PluginLifecycleManagerTest {

    private static final String PLUGIN_ID = "test-plugin";

    @Mock
    private EventBus eventBus;

    @Mock
    private PluginContext pluginContext;

    private ScheduledExecutorService scheduler;
    private InstancePool instancePool;
    private ServiceRegistry serviceRegistry;
    private PluginRuntimeConfig config;
    private PluginLifecycleManager lifecycleManager;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        config = PluginRuntimeConfig.builder()
                .maxHistorySnapshots(5)
                .dyingCheckIntervalSeconds(1)
                .forceCleanupDelaySeconds(2)
                .build();

        instancePool = new InstancePool(PLUGIN_ID, config.getMaxHistorySnapshots());
        serviceRegistry = new ServiceRegistry(PLUGIN_ID);
        lifecycleManager = new PluginLifecycleManager(
                PLUGIN_ID,
                instancePool,
                serviceRegistry,
                eventBus,
                scheduler,
                config
        );
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

    // ==================== 辅助方法 ====================

    private PluginInstance createMockInstance(String version) {
        PluginContainer container = mock(PluginContainer.class);
        when(container.isActive()).thenReturn(true);
        doNothing().when(container).start(any());
        doNothing().when(container).stop();

        PluginDefinition definition = new PluginDefinition();
        definition.setId(PLUGIN_ID);
        definition.setVersion(version);

        return new PluginInstance(version, container, definition);
    }

    // ==================== 初始状态测试 ====================

    @Nested
    @DisplayName("初始状态")
    class InitialStateTests {

        @Test
        @DisplayName("新创建的管理器不应处于关闭状态")
        void newManagerShouldNotBeShutdown() {
            assertFalse(lifecycleManager.isShutdown());
        }

        @Test
        @DisplayName("初始统计应正确")
        void initialStatsShouldBeCorrect() {
            PluginLifecycleManager.LifecycleStats stats = lifecycleManager.getStats();

            assertFalse(stats.isShutdown());
            assertFalse(stats.forceCleanupScheduled());
            assertEquals(0, stats.dyingCount());
        }
    }

    // ==================== 添加实例测试 ====================

    @Nested
    @DisplayName("添加实例")
    class AddInstanceTests {

        @Test
        @DisplayName("添加实例应成功")
        void addInstanceShouldSucceed() {
            PluginInstance instance = createMockInstance("1.0.0");

            lifecycleManager.addInstance(instance, pluginContext, true);

            assertTrue(instance.isReady());
            assertEquals(instance, instancePool.getDefault());
            verify(eventBus, atLeastOnce()).publish(any());
        }

        @Test
        @DisplayName("添加非默认实例不应替换默认")
        void addNonDefaultShouldNotReplaceDefault() {
            PluginInstance v1 = createMockInstance("1.0.0");
            PluginInstance v2 = createMockInstance("2.0.0");

            lifecycleManager.addInstance(v1, pluginContext, true);
            lifecycleManager.addInstance(v2, pluginContext, false);

            assertEquals(v1, instancePool.getDefault());
            assertEquals(2, instancePool.getActiveInstances().size());
        }

        @Test
        @DisplayName("升级应将旧版本移入死亡队列")
        void upgradeShouldMoveOldToDying() {
            PluginInstance v1 = createMockInstance("1.0.0");
            PluginInstance v2 = createMockInstance("2.0.0");

            lifecycleManager.addInstance(v1, pluginContext, true);
            lifecycleManager.addInstance(v2, pluginContext, true);

            assertTrue(v1.isDying());
            assertFalse(v2.isDying());
            assertEquals(v2, instancePool.getDefault());
        }

        @Test
        @DisplayName("容器启动失败应抛出异常")
        void containerStartFailureShouldThrow() {
            PluginContainer container = mock(PluginContainer.class);
            doThrow(new RuntimeException("Start failed")).when(container).start(any());

            PluginDefinition definition = new PluginDefinition();
            definition.setId(PLUGIN_ID);
            definition.setVersion("1.0.0");

            PluginInstance instance = new PluginInstance("1.0.0", container, definition);

            assertThrows(RuntimeException.class, () ->
                    lifecycleManager.addInstance(instance, pluginContext, true));
        }

        @Test
        @DisplayName("关闭后添加实例应抛出异常")
        void addAfterShutdownShouldThrow() {
            lifecycleManager.shutdown();

            PluginInstance instance = createMockInstance("1.0.0");

            assertThrows(IllegalStateException.class, () ->
                    lifecycleManager.addInstance(instance, pluginContext, true));
        }

        @Test
        @DisplayName("背压检查应阻止过多实例")
        void backpressureShouldPreventTooManyInstances() {
            // 填满死亡队列
            for (int i = 0; i < config.getMaxHistorySnapshots(); i++) {
                PluginInstance instance = createMockInstance("old-" + i);
                instancePool.addInstance(instance, false);
                // 模拟有活跃请求，不会被清理
                instance.tryEnter();
                instancePool.moveToDying(instance);
            }

            PluginInstance newInstance = createMockInstance("new");

            assertThrows(IllegalStateException.class, () ->
                    lifecycleManager.addInstance(newInstance, pluginContext, true));
        }
    }

    // ==================== 关闭测试 ====================

    @Nested
    @DisplayName("关闭功能")
    class ShutdownTests {

        @Test
        @DisplayName("关闭应设置状态")
        void shutdownShouldSetState() {
            lifecycleManager.shutdown();

            assertTrue(lifecycleManager.isShutdown());
        }

        @Test
        @DisplayName("关闭应是幂等的")
        void shutdownShouldBeIdempotent() {
            assertDoesNotThrow(() -> {
                lifecycleManager.shutdown();
                lifecycleManager.shutdown();
                lifecycleManager.shutdown();
            });
        }

        @Test
        @DisplayName("关闭应清空实例池")
        void shutdownShouldClearInstancePool() {
            PluginInstance instance = createMockInstance("1.0.0");
            lifecycleManager.addInstance(instance, pluginContext, true);

            lifecycleManager.shutdown();

            assertNull(instancePool.getDefault());
            assertTrue(instance.isDying());
        }

        @Test
        @DisplayName("关闭应清空服务注册表")
        void shutdownShouldClearServiceRegistry() throws Exception {
            // 注册一个服务
            Object bean = new Object() {
                public String hello() {
                    return "hello";
                }
            };
            serviceRegistry.registerService("test:hello", bean,
                    bean.getClass().getMethod("hello"));

            lifecycleManager.shutdown();

            assertEquals(0, serviceRegistry.getServiceCount());
        }
    }

    // ==================== 清理测试 ====================

    @Nested
    @DisplayName("清理功能")
    class CleanupTests {

        @Test
        @DisplayName("清理应销毁空闲实例")
        void cleanupShouldDestroyIdleInstances() {
            PluginInstance instance = createMockInstance("1.0.0");
            lifecycleManager.addInstance(instance, pluginContext, true);

            // 移到死亡队列
            instancePool.moveToDying(instance);
            assertTrue(instance.isIdle());

            // 手动触发清理
            int cleaned = lifecycleManager.cleanupIdleInstances();

            assertEquals(1, cleaned);
            assertTrue(instance.isDestroyed());
        }

        @Test
        @DisplayName("清理不应销毁忙碌实例")
        void cleanupShouldNotDestroyBusyInstances() {
            PluginInstance instance = createMockInstance("1.0.0");
            lifecycleManager.addInstance(instance, pluginContext, true);

            // 模拟活跃请求
            instance.tryEnter();
            instancePool.moveToDying(instance);

            int cleaned = lifecycleManager.cleanupIdleInstances();

            assertEquals(0, cleaned);
            assertFalse(instance.isDestroyed());

            // 清理
            instance.exit();
        }

        @Test
        @DisplayName("定时清理应自动执行")
        void periodicCleanupShouldRun() {
            PluginInstance instance = createMockInstance("1.0.0");
            lifecycleManager.addInstance(instance, pluginContext, true);
            instancePool.moveToDying(instance);

            // 等待定时清理（间隔 1 秒）
            await()
                    .atMost(3, TimeUnit.SECONDS)
                    .until(instance::isDestroyed);

            assertTrue(instance.isDestroyed());
        }

        @Test
        @DisplayName("强制清理应销毁所有实例")
        void forceCleanupShouldDestroyAll() {
            // 添加一些忙碌的实例
            for (int i = 0; i < 3; i++) {
                PluginInstance instance = createMockInstance("1.0." + i);
                lifecycleManager.addInstance(instance, pluginContext, false);
                instance.tryEnter(); // 模拟忙碌
                instancePool.moveToDying(instance);
            }

            assertEquals(3, instancePool.getDyingCount());

            lifecycleManager.forceCleanupAll();

            assertEquals(0, instancePool.getDyingCount());
        }
    }

    // ==================== 事件发布测试 ====================

    @Nested
    @DisplayName("事件发布")
    class EventPublishingTests {

        @Test
        @DisplayName("添加实例应发布启动事件")
        void addInstanceShouldPublishStartEvents() {
            PluginInstance instance = createMockInstance("1.0.0");

            lifecycleManager.addInstance(instance, pluginContext, true);

            // 验证发布了 Starting 和 Started 事件
            verify(eventBus, atLeast(2)).publish(any());
        }

        @Test
        @DisplayName("销毁实例应发布停止事件")
        void destroyInstanceShouldPublishStopEvents() {
            PluginInstance instance = createMockInstance("1.0.0");
            lifecycleManager.addInstance(instance, pluginContext, true);

            reset(eventBus); // 重置，只验证停止事件

            instancePool.moveToDying(instance);
            lifecycleManager.cleanupIdleInstances();

            // 验证发布了 Stopping 和 Stopped 事件
            verify(eventBus, atLeast(2)).publish(any());
        }
    }

    // ==================== 统计信息测试 ====================

    @Nested
    @DisplayName("统计信息")
    class StatsTests {

        @Test
        @DisplayName("getStats 应返回正确统计")
        void getStatsShouldWork() {
            PluginInstance instance = createMockInstance("1.0.0");
            lifecycleManager.addInstance(instance, pluginContext, true);
            instancePool.moveToDying(instance);

            PluginLifecycleManager.LifecycleStats stats = lifecycleManager.getStats();

            assertFalse(stats.isShutdown());
            assertEquals(1, stats.dyingCount());
        }

        @Test
        @DisplayName("关闭后统计应正确")
        void statsAfterShutdownShouldBeCorrect() {
            lifecycleManager.shutdown();

            PluginLifecycleManager.LifecycleStats stats = lifecycleManager.getStats();

            assertTrue(stats.isShutdown());
        }

        @Test
        @DisplayName("LifecycleStats toString 应包含关键信息")
        void statsToStringShouldWork() {
            String str = lifecycleManager.getStats().toString();

            assertTrue(str.contains("shutdown=false"));
            assertTrue(str.contains("dying=0"));
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
            ExecutorService testExecutor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                testExecutor.submit(() -> {
                    try {
                        startLatch.await();
                        PluginInstance instance = createMockInstance("1.0." + index);
                        lifecycleManager.addInstance(instance, pluginContext, index == 0);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        // 部分失败是可接受的（如背压）
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
        @DisplayName("并发清理应安全")
        void concurrentCleanupShouldBeSafe() throws InterruptedException {
            // 添加一些实例
            for (int i = 0; i < 5; i++) {
                PluginInstance instance = createMockInstance("1.0." + i);
                lifecycleManager.addInstance(instance, pluginContext, i == 0);
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
                    } catch (Exception e) {
                        // ignore
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
}