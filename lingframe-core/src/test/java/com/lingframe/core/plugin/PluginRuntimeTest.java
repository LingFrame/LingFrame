package com.lingframe.core.plugin;

import com.lingframe.api.config.PluginDefinition;
import com.lingframe.api.context.PluginContext;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.exception.ServiceUnavailableException;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.spi.PluginContainer;
import com.lingframe.core.spi.PluginServiceInvoker;
import com.lingframe.core.spi.TrafficRouter;
import com.lingframe.core.spi.TransactionVerifier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PluginRuntime 单元测试")
public class PluginRuntimeTest {

    private static final String PLUGIN_ID = "test-plugin";

    @Mock
    private GovernanceKernel governanceKernel;

    @Mock
    private EventBus eventBus;

    @Mock
    private TrafficRouter trafficRouter;

    @Mock
    private TransactionVerifier transactionVerifier;

    @Mock
    private PluginContext pluginContext;

    private ScheduledExecutorService scheduler;
    private ExecutorService executor;
    private PluginServiceInvoker invoker;
    private PluginRuntime runtime;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        executor = Executors.newFixedThreadPool(4);

        // 真实的 invoker
        invoker = (instance, bean, method, args) -> {
            try {
                return method.invoke(bean, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception)
                    throw (Exception) cause;
                throw new RuntimeException(cause);
            }
        };

        runtime = new PluginRuntime(
                PLUGIN_ID,
                PluginRuntimeConfig.defaults(),
                scheduler,
                executor,
                governanceKernel,
                eventBus,
                trafficRouter,
                invoker,
                transactionVerifier,
                Collections.emptyList(),
                null);
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            try {
                runtime.shutdown();
            } catch (Exception ignored) {
            }
        }
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
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

        return new PluginInstance(container, definition);
    }

    public static class TestService {
        public String hello(String name) {
            return "Hello, " + name;
        }

        public int add(int a, int b) {
            return a + b;
        }
    }

    // ==================== 初始状态测试 ====================

    @Nested
    @DisplayName("初始状态")
    class InitialStateTests {

        @Test
        @DisplayName("新运行时应有正确的 ID")
        void newRuntimeShouldHaveCorrectId() {
            assertEquals(PLUGIN_ID, runtime.getPluginId());
        }

        @Test
        @DisplayName("新运行时应有默认配置")
        void newRuntimeShouldHaveDefaultConfig() {
            assertNotNull(runtime.getConfig());
        }

        @Test
        @DisplayName("新运行时不应可用（无实例）")
        void newRuntimeShouldNotBeAvailable() {
            assertFalse(runtime.isAvailable());
        }

        @Test
        @DisplayName("新运行时版本应为 null")
        void newRuntimeVersionShouldBeNull() {
            assertNull(runtime.getVersion());
        }

        @Test
        @DisplayName("应能获取所有组件")
        void shouldAccessAllComponents() {
            assertNotNull(runtime.getInstancePool());
            assertNotNull(runtime.getServiceRegistry());
            assertNotNull(runtime.getInvocationExecutor());
            assertNotNull(runtime.getLifecycleManager());
        }
    }

    // ==================== 生命周期测试 ====================

    @Nested
    @DisplayName("生命周期")
    class LifecycleTests {

        @Test
        @DisplayName("添加实例后应可用")
        void shouldBeAvailableAfterAddInstance() {
            PluginInstance instance = createMockInstance("1.0.0");

            runtime.addInstance(instance, pluginContext, true);
            runtime.activate();

            assertTrue(runtime.isAvailable());
            assertEquals("1.0.0", runtime.getVersion());
        }

        @Test
        @DisplayName("关闭后应不可用")
        void shouldNotBeAvailableAfterShutdown() {
            PluginInstance instance = createMockInstance("1.0.0");
            runtime.addInstance(instance, pluginContext, true);
            runtime.activate();

            runtime.shutdown();

            assertFalse(runtime.isAvailable());
        }

        @Test
        @DisplayName("关闭应是幂等的")
        void shutdownShouldBeIdempotent() {
            assertDoesNotThrow(() -> {
                runtime.shutdown();
                runtime.shutdown();
                runtime.shutdown();
            });
        }
    }

    // ==================== 路由测试 ====================

    @Nested
    @DisplayName("路由")
    class RoutingTests {

        @Test
        @DisplayName("routeToAvailableInstance 应返回可用实例")
        void routeShouldReturnAvailableInstance() {
            PluginInstance instance = createMockInstance("1.0.0");
            runtime.addInstance(instance, pluginContext, true);

            when(trafficRouter.route(anyList(), any())).thenReturn(instance);

            PluginInstance result = runtime.routeToAvailableInstance("test:service");

            assertEquals(instance, result);
        }

        @Test
        @DisplayName("路由无结果时应使用默认实例")
        void routeShouldFallbackToDefault() {
            PluginInstance instance = createMockInstance("1.0.0");
            runtime.addInstance(instance, pluginContext, true);

            when(trafficRouter.route(anyList(), any())).thenReturn(null);

            PluginInstance result = runtime.routeToAvailableInstance("test:service");

            assertEquals(instance, result);
        }

        @Test
        @DisplayName("无可用实例时应抛出异常")
        void routeShouldThrowWhenNoInstance() {
            when(trafficRouter.route(anyList(), any())).thenReturn(null);

            assertThrows(ServiceUnavailableException.class, () -> runtime.routeToAvailableInstance("test:service"));
        }
    }

    // ==================== 服务调用测试 ====================

    @Nested
    @DisplayName("服务调用")
    class InvocationTests {

        @Test
        @DisplayName("invoke 应成功调用服务")
        void invokeShouldSucceed() throws Exception {
            PluginInstance instance = createMockInstance("1.0.0");
            runtime.addInstance(instance, pluginContext, true);
            runtime.activate();

            when(trafficRouter.route(anyList(), any())).thenReturn(instance);

            // 注册服务
            TestService bean = new TestService();
            Method method = TestService.class.getMethod("hello", String.class);
            runtime.getServiceRegistry().registerService("test:hello", bean, method);

            Object result = runtime.invoke("caller", "test:hello", new Object[] { "World" });

            assertEquals("Hello, World", result);
        }

        @Test
        @DisplayName("invoke 服务不存在应抛出异常")
        void invokeShouldThrowWhenServiceNotFound() {
            PluginInstance instance = createMockInstance("1.0.0");
            runtime.addInstance(instance, pluginContext, true);
            runtime.activate();

            when(trafficRouter.route(anyList(), any())).thenReturn(instance);

            assertThrows(NoSuchMethodException.class, () -> runtime.invoke("caller", "non:existent", new Object[] {}));
        }

        @Test
        @DisplayName("无实例时调用应抛出异常")
        void invokeShouldThrowWhenNoInstance() {
            assertThrows(ServiceUnavailableException.class,
                    () -> runtime.invoke("caller", "test:service", new Object[] {}));
        }
    }

    // ==================== hasBean 测试 ====================

    @Nested
    @DisplayName("Bean 检查")
    class HasBeanTests {

        @Test
        @DisplayName("无实例时 hasBean 应返回 false")
        void hasBeanShouldReturnFalseWhenNoInstance() {
            assertFalse(runtime.hasBean(String.class));
        }

        @Test
        @DisplayName("有 Bean 时应返回 true")
        void hasBeanShouldReturnTrueWhenBeanExists() {
            PluginInstance instance = createMockInstance("1.0.0");
            when(instance.getContainer().getBean(String.class)).thenReturn("test");
            runtime.addInstance(instance, pluginContext, true);

            assertTrue(runtime.hasBean(String.class));
        }

        @Test
        @DisplayName("无 Bean 时应返回 false")
        void hasBeanShouldReturnFalseWhenBeanNotExists() {
            PluginInstance instance = createMockInstance("1.0.0");
            when(instance.getContainer().getBean(String.class)).thenReturn(null);
            runtime.addInstance(instance, pluginContext, true);

            assertFalse(runtime.hasBean(String.class));
        }
    }

    // ==================== 统计信息测试 ====================

    @Nested
    @DisplayName("统计信息")
    class StatsTests {

        @Test
        @DisplayName("getStats 应返回完整统计")
        void getStatsShouldReturnCompleteStats() {
            PluginInstance instance = createMockInstance("1.0.0");
            runtime.addInstance(instance, pluginContext, true);
            runtime.activate();

            PluginRuntime.RuntimeStats stats = runtime.getStats();

            assertEquals(PLUGIN_ID, stats.pluginId());
            assertTrue(stats.available());
            assertEquals("1.0.0", stats.version());
            assertNotNull(stats.pool());
            assertNotNull(stats.registry());
            assertNotNull(stats.executor());
            assertNotNull(stats.lifecycle());
        }

        @Test
        @DisplayName("RuntimeStats toString 应包含关键信息")
        void statsToStringShouldWork() {
            PluginInstance instance = createMockInstance("1.0.0");
            runtime.addInstance(instance, pluginContext, true);
            runtime.activate();

            String str = runtime.getStats().toString();

            assertTrue(str.contains(PLUGIN_ID));
            assertTrue(str.contains("available=true"));
            assertTrue(str.contains("version='1.0.0'"));
        }
    }

    // ==================== 升级测试 ====================

    @Nested
    @DisplayName("版本升级")
    class UpgradeTests {

        @Test
        @DisplayName("升级应更新版本号")
        void upgradeShouldUpdateVersion() {
            PluginInstance v1 = createMockInstance("1.0.0");
            PluginInstance v2 = createMockInstance("2.0.0");

            runtime.addInstance(v1, pluginContext, true);
            assertEquals("1.0.0", runtime.getVersion());

            runtime.addInstance(v2, pluginContext, true);
            assertEquals("2.0.0", runtime.getVersion());
        }

        @Test
        @DisplayName("升级后旧版本应进入 dying 状态")
        void upgradeShouldMarkOldAsDying() {
            PluginInstance v1 = createMockInstance("1.0.0");
            PluginInstance v2 = createMockInstance("2.0.0");

            runtime.addInstance(v1, pluginContext, true);
            runtime.addInstance(v2, pluginContext, true);

            assertTrue(v1.isDying());
            assertFalse(v2.isDying());
        }

        @Test
        @DisplayName("旧版本应自动被清理")
        void oldVersionShouldBeCleanedUp() {
            PluginInstance v1 = createMockInstance("1.0.0");
            PluginInstance v2 = createMockInstance("2.0.0");

            runtime.addInstance(v1, pluginContext, true);
            runtime.addInstance(v2, pluginContext, true);

            // 等待定时清理
            await()
                    .atMost(10, TimeUnit.SECONDS)
                    .until(v1::isDestroyed);

            assertTrue(v1.isDestroyed());
        }
    }

    // ==================== 配置测试 ====================

    @Nested
    @DisplayName("配置")
    class ConfigTests {

        @Test
        @DisplayName("应使用传入的配置")
        void shouldUseProvidedConfig() {
            PluginRuntimeConfig customConfig = PluginRuntimeConfig.builder()
                    .defaultTimeoutMs(5000)
                    .bulkheadMaxConcurrent(20)
                    .build();

            PluginRuntime customRuntime = new PluginRuntime(
                    "custom-plugin",
                    customConfig,
                    scheduler,
                    executor,
                    governanceKernel,
                    eventBus,
                    trafficRouter,
                    invoker,
                    transactionVerifier,
                    Collections.emptyList(),
                    null);

            try {
                assertEquals(5000, customRuntime.getConfig().getDefaultTimeoutMs());
                assertEquals(20, customRuntime.getConfig().getBulkheadMaxConcurrent());
            } finally {
                customRuntime.shutdown();
            }
        }

        @Test
        @DisplayName("null 配置应使用默认值")
        void nullConfigShouldUseDefaults() {
            PluginRuntime nullConfigRuntime = new PluginRuntime(
                    "null-config-plugin",
                    null,
                    scheduler,
                    executor,
                    governanceKernel,
                    eventBus,
                    trafficRouter,
                    invoker,
                    transactionVerifier,
                    Collections.emptyList(),
                    null);

            try {
                assertNotNull(nullConfigRuntime.getConfig());
                assertEquals(3000, nullConfigRuntime.getConfig().getDefaultTimeoutMs());
            } finally {
                nullConfigRuntime.shutdown();
            }
        }
    }
}