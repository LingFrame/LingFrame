package com.lingframe.core.plugin;

import com.lingframe.api.config.PluginDefinition;
import com.lingframe.core.spi.PluginContainer;
import com.lingframe.core.spi.PluginServiceInvoker;
import com.lingframe.core.spi.TransactionVerifier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InvocationExecutor 单元测试")
public class InvocationExecutorTest {

    private static final String PLUGIN_ID = "test-plugin";

    @Mock
    private TransactionVerifier transactionVerifier;

    private ExecutorService executor;
    private InvocationExecutor invocationExecutor;

    // 🔥 使用真实的 invoker 而不是 mock
    private PluginServiceInvoker realInvoker;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(4);

        // 🔥 创建真实的 invoker，直接使用 MethodHandle
        realInvoker = (instance, bean, method, args) -> {
            try {
                return method.invoke(bean, args);
            } catch (InvocationTargetException e) {
                // 解包并重新抛出原始异常
                Throwable cause = e.getCause();
                if (cause instanceof Exception) {
                    throw (Exception) cause;
                } else if (cause instanceof Error) {
                    throw (Error) cause;
                } else {
                    throw new RuntimeException(cause);
                }
            }
        };

        PluginRuntimeConfig config = PluginRuntimeConfig.builder()
                .bulkheadMaxConcurrent(5)
                .defaultTimeoutMs(1000)
                .bulkheadAcquireTimeoutMs(500)
                .build();

        invocationExecutor = new InvocationExecutor(
                PLUGIN_ID,
                executor,
                realInvoker,
                transactionVerifier,
                Collections.emptyList(),
                config
        );
    }

    @AfterEach
    void tearDown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    // ==================== 辅助方法 ====================

    private PluginInstance createMockInstance(String version) {
        PluginContainer container = mock(PluginContainer.class);
        when(container.isActive()).thenReturn(true);

        PluginDefinition definition = new PluginDefinition();
        definition.setId(PLUGIN_ID);
        definition.setVersion(version);

        PluginInstance instance = new PluginInstance(container, definition);
        instance.markReady();
        return instance;
    }

    private ServiceRegistry.InvokableService createService(Object bean, String methodName, Class<?>... paramTypes) throws Exception {
        Method method = bean.getClass().getMethod(methodName, paramTypes);
        method.setAccessible(true);
        MethodHandle handle = MethodHandles.lookup().unreflect(method).bindTo(bean);
        return new ServiceRegistry.InvokableService(bean, method, handle);
    }

    // ==================== 测试服务类 ====================

    public static class TestService {
        public String hello(String name) {
            return "Hello, " + name;
        }

        public String slowMethod(long sleepMs) throws InterruptedException {
            Thread.sleep(sleepMs);
            return "done after " + sleepMs + "ms";
        }

        public String trackThread(AtomicReference<Thread> threadRef) {
            threadRef.set(Thread.currentThread());
            return "tracked";
        }
    }

    // ==================== 基础执行测试 ====================

    @Nested
    @DisplayName("基础执行")
    class BasicExecutionTests {

        @Test
        @DisplayName("同步执行应成功")
        void syncExecutionShouldSucceed() throws Exception {
            PluginInstance instance = createMockInstance("1.0.0");
            TestService bean = new TestService();
            ServiceRegistry.InvokableService service = createService(bean, "hello", String.class);

            Object result = invocationExecutor.executeSync(instance, service, new Object[]{"World"});

            assertEquals("Hello, World", result);
        }

        @Test
        @DisplayName("异步执行应成功")
        void asyncExecutionShouldSucceed() throws Exception {
            PluginInstance instance = createMockInstance("1.0.0");
            TestService bean = new TestService();
            ServiceRegistry.InvokableService service = createService(bean, "hello", String.class);

            Object result = invocationExecutor.executeAsync(
                    instance, service, new Object[]{"World"}, "caller", "test:hello");

            assertEquals("Hello, World", result);
        }

        @Test
        @DisplayName("事务方法应同步执行（在当前线程）")
        void transactionalMethodShouldExecuteSync() throws Exception {
            PluginInstance instance = createMockInstance("1.0.0");

            // 用于记录执行线程
            AtomicReference<Thread> executionThread = new AtomicReference<>();
            TestService bean = new TestService();
            ServiceRegistry.InvokableService service = createService(bean, "trackThread", AtomicReference.class);

            // 标记为事务方法
            when(transactionVerifier.isTransactional(any(), any())).thenReturn(true);

            invocationExecutor.execute(instance, service, new Object[]{executionThread}, "caller", "test:track");

            // 应该在当前线程执行
            assertEquals(Thread.currentThread(), executionThread.get());
        }

        @Test
        @DisplayName("非事务方法应异步执行（在线程池）")
        void nonTransactionalMethodShouldExecuteAsync() throws Exception {
            PluginInstance instance = createMockInstance("1.0.0");

            AtomicReference<Thread> executionThread = new AtomicReference<>();
            TestService bean = new TestService();
            ServiceRegistry.InvokableService service = createService(bean, "trackThread", AtomicReference.class);

            // 标记为非事务方法
            when(transactionVerifier.isTransactional(any(), any())).thenReturn(false);

            invocationExecutor.execute(instance, service, new Object[]{executionThread}, "caller", "test:track");

            // 应该在其他线程执行
            assertNotNull(executionThread.get());
            assertNotEquals(Thread.currentThread(), executionThread.get());
        }
    }

    // ==================== 超时测试 ====================

    @Nested
    @DisplayName("超时控制")
    class TimeoutTests {

        @Test
        @DisplayName("超时应抛出 TimeoutException")
        void timeoutShouldThrowException() throws Exception {
            // 创建一个超时时间很短的执行器
            PluginRuntimeConfig shortTimeoutConfig = PluginRuntimeConfig.builder()
                    .bulkheadMaxConcurrent(5)
                    .defaultTimeoutMs(200)  // 200ms 超时
                    .bulkheadAcquireTimeoutMs(500)
                    .build();

            InvocationExecutor shortTimeoutExecutor = new InvocationExecutor(
                    PLUGIN_ID,
                    executor,
                    realInvoker,
                    transactionVerifier,
                    Collections.emptyList(),
                    shortTimeoutConfig
            );

            PluginInstance instance = createMockInstance("1.0.0");
            TestService bean = new TestService();
            ServiceRegistry.InvokableService service = createService(bean, "slowMethod", long.class);

            // 方法 sleep 500ms，但超时设置为 200ms
            assertThrows(TimeoutException.class, () ->
                    shortTimeoutExecutor.executeAsync(
                            instance, service, new Object[]{500L}, "caller", "test:slow"));
        }

        @Test
        @DisplayName("快速方法不应超时")
        void fastMethodShouldNotTimeout() throws Exception {
            PluginInstance instance = createMockInstance("1.0.0");
            TestService bean = new TestService();
            ServiceRegistry.InvokableService service = createService(bean, "slowMethod", long.class);

            // 方法 sleep 50ms，超时设置为 1000ms
            Object result = invocationExecutor.executeAsync(
                    instance, service, new Object[]{50L}, "caller", "test:slow");

            assertEquals("done after 50ms", result);
        }
    }

    // ==================== 舱壁隔离测试 ====================

    @Nested
    @DisplayName("舱壁隔离")
    class BulkheadTests {

        @Test
        @DisplayName("超过并发限制应抛出 RejectedExecutionException")
        void exceedingBulkheadShouldReject() throws Exception {
            // 配置只允许 2 个并发，获取许可超时很短
            PluginRuntimeConfig config = PluginRuntimeConfig.builder()
                    .bulkheadMaxConcurrent(2)
                    .defaultTimeoutMs(5000)
                    .bulkheadAcquireTimeoutMs(50)  // 50ms 等待
                    .build();

            InvocationExecutor limitedExecutor = new InvocationExecutor(
                    PLUGIN_ID, executor, realInvoker, transactionVerifier,
                    Collections.emptyList(), config
            );

            PluginInstance instance = createMockInstance("1.0.0");
            TestService bean = new TestService();
            ServiceRegistry.InvokableService service = createService(bean, "slowMethod", long.class);

            // 用于同步的栅栏
            CountDownLatch tasksStarted = new CountDownLatch(2);
            CountDownLatch canFinish = new CountDownLatch(1);
            AtomicInteger startedCount = new AtomicInteger(0);

            // 自定义的慢方法，可控制何时结束
            ServiceRegistry.InvokableService controlledService = createService(
                    new Object() {
                        public String controlled() throws InterruptedException {
                            startedCount.incrementAndGet();
                            tasksStarted.countDown();
                            canFinish.await(10, TimeUnit.SECONDS);
                            return "done";
                        }
                    }, "controlled"
            );

            // 启动 2 个长时间任务占满舱壁
            ExecutorService testPool = Executors.newFixedThreadPool(3);
            for (int i = 0; i < 2; i++) {
                testPool.submit(() -> {
                    try {
                        limitedExecutor.executeAsync(
                                instance, controlledService, new Object[]{}, "caller", "test:controlled");
                    } catch (Exception ignored) {
                    }
                });
            }

            // 等待两个任务开始执行
            assertTrue(tasksStarted.await(2, TimeUnit.SECONDS), "两个任务应该启动");
            assertEquals(2, startedCount.get());

            // 第 3 个请求应该被拒绝（因为舱壁满了，且等待超时很短）
            assertThrows(RejectedExecutionException.class, () ->
                    limitedExecutor.executeAsync(
                            instance, controlledService, new Object[]{}, "caller", "test:controlled"));

            // 清理
            canFinish.countDown();
            testPool.shutdown();
            testPool.awaitTermination(5, TimeUnit.SECONDS);
        }

        @Test
        @DisplayName("getAvailablePermits 应返回正确值")
        void getAvailablePermitsShouldWork() {
            assertEquals(5, invocationExecutor.getAvailablePermits());
        }

        @Test
        @DisplayName("舱壁许可应在执行后释放")
        void permitsShouldBeReleasedAfterExecution() throws Exception {
            PluginInstance instance = createMockInstance("1.0.0");
            TestService bean = new TestService();
            ServiceRegistry.InvokableService service = createService(bean, "hello", String.class);

            int initialPermits = invocationExecutor.getAvailablePermits();

            // 执行多次
            for (int i = 0; i < 10; i++) {
                invocationExecutor.executeAsync(instance, service, new Object[]{"test"}, "caller", "test:hello");
            }

            // 许可应该全部释放回来
            assertEquals(initialPermits, invocationExecutor.getAvailablePermits());
        }
    }

    // ==================== 统计信息测试 ====================

    @Nested
    @DisplayName("统计信息")
    class StatsTests {

        @Test
        @DisplayName("getStats 应返回正确统计")
        void getStatsShouldWork() {
            InvocationExecutor.ExecutorStats stats = invocationExecutor.getStats();

            assertEquals(5, stats.availablePermits());
            assertEquals(0, stats.queueLength());
            assertEquals(1000, stats.timeoutMs());
            assertEquals(500, stats.acquireTimeoutMs());
        }

        @Test
        @DisplayName("ExecutorStats toString 应包含关键信息")
        void statsToStringShouldWork() {
            String str = invocationExecutor.getStats().toString();

            assertTrue(str.contains("available=5"));
            assertTrue(str.contains("timeout=1000ms"));
        }
    }

    // ==================== 并发安全测试 ====================

    @Nested
    @DisplayName("并发安全")
    class ConcurrencyTests {

        @Test
        @DisplayName("并发执行应安全")
        void concurrentExecutionShouldBeSafe() throws Exception {
            PluginInstance instance = createMockInstance("1.0.0");
            TestService bean = new TestService();
            ServiceRegistry.InvokableService service = createService(bean, "hello", String.class);

            int threadCount = 10;  // 减少线程数，因为舱壁只有 5
            ExecutorService testExecutor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                testExecutor.submit(() -> {
                    try {
                        Object result = invocationExecutor.executeAsync(
                                instance, service, new Object[]{"User" + index},
                                "caller", "test:hello");
                        if (result != null && result.toString().startsWith("Hello")) {
                            successCount.incrementAndGet();
                        }
                    } catch (RejectedExecutionException e) {
                        // 舱壁限制，可接受
                        errorCount.incrementAndGet();
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
            testExecutor.shutdown();

            assertTrue(completed, "所有任务应该完成");
            // 至少应该有一些成功
            assertTrue(successCount.get() > 0, "应该有成功的执行");
            System.out.println("Success: " + successCount.get() + ", Errors: " + errorCount.get());
        }

        @Test
        @DisplayName("并发执行中许可应正确管理")
        void permitsShouldBeCorrectlyManagedUnderConcurrency() throws Exception {
            PluginInstance instance = createMockInstance("1.0.0");
            TestService bean = new TestService();
            ServiceRegistry.InvokableService service = createService(bean, "hello", String.class);

            int initialPermits = invocationExecutor.getAvailablePermits();

            int threadCount = 20;
            ExecutorService testExecutor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                testExecutor.submit(() -> {
                    try {
                        invocationExecutor.executeAsync(
                                instance, service, new Object[]{"User" + index},
                                "caller", "test:hello");
                    } catch (Exception ignored) {
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            doneLatch.await(10, TimeUnit.SECONDS);
            testExecutor.shutdown();
            testExecutor.awaitTermination(5, TimeUnit.SECONDS);

            // 等待一小段时间让所有异步操作完成
            Thread.sleep(200);

            // 所有许可应该释放回来
            assertEquals(initialPermits, invocationExecutor.getAvailablePermits(),
                    "所有许可应该释放");
        }
    }

    // ==================== 异常处理测试 ====================

    @Nested
    @DisplayName("异常处理")
    class ExceptionHandlingTests {

        @Test
        @DisplayName("业务异常应正确传播")
        void businessExceptionShouldPropagate() throws Exception {
            PluginInstance instance = createMockInstance("1.0.0");

            ServiceRegistry.InvokableService service = createService(
                    new Object() {
                        public String throwError() {
                            throw new IllegalArgumentException("Business error");
                        }
                    }, "throwError"
            );

            Exception thrown = assertThrows(IllegalArgumentException.class, () ->
                    invocationExecutor.executeAsync(
                            instance, service, new Object[]{}, "caller", "test:error"));

            assertEquals("Business error", thrown.getMessage());
        }
    }
}