package com.lingframe.core.ling;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.spi.LingContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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
@DisplayName("LingInstance 测试")
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
    @DisplayName("构造约束")
    class ConstructorTests {

        @Test
        @DisplayName("合法参数下应构造成功")
        void shouldConstructSuccessfully() {
            assertNotNull(instance);
            assertEquals("1.0.0", instance.getVersion());
            assertNotNull(instance.getContainer());
            assertNotNull(instance.getDefinition());
        }

        @Test
        @DisplayName("版本号为 null 时应抛出异常")
        void shouldThrowWhenVersionIsNull() {
            definition.setVersion(null);
            assertThrows(InvalidArgumentException.class,
                    () -> new LingInstance(container, definition, new EventBus()));
        }

        @Test
        @DisplayName("版本号为空白字符串时应抛出异常")
        void shouldThrowWhenVersionIsBlank() {
            definition.setVersion(" ");
            assertThrows(InvalidArgumentException.class,
                    () -> new LingInstance(container, definition, new EventBus()));
        }

        @Test
        @DisplayName("容器为 null 时应抛出异常")
        void shouldThrowWhenContainerIsNull() {
            assertThrows(NullPointerException.class,
                    () -> new LingInstance(null, definition, new EventBus()));
        }

        @Test
        @DisplayName("定义为 null 时应抛出异常")
        void shouldThrowWhenDefinitionIsNull() {
            assertThrows(NullPointerException.class,
                    () -> new LingInstance(container, null, new EventBus()));
        }
    }

    @Nested
    @DisplayName("状态管理")
    class StateManagementTests {

        @Test
        @DisplayName("新实例初始时不应处于就绪状态")
        void newInstanceShouldNotBeReady() {
            assertFalse(instance.isReady());
        }

        @Test
        @DisplayName("协调器标记就绪后应变为可用")
        void shouldBeReadyAfterCoordinatorMarksReady() {
            prepareReady(instance);
            assertTrue(instance.isReady());
        }

        @Test
        @DisplayName("容器失活后不应再判定为就绪")
        void shouldNotBeReadyWhenContainerInactive() {
            prepareReady(instance);
            assertTrue(instance.isReady());

            when(container.isActive()).thenReturn(false);
            assertFalse(instance.isReady());
        }

        @Test
        @DisplayName("停止后不应再判定为就绪")
        void shouldNotBeReadyWhenStopping() {
            prepareReady(instance);
            assertTrue(instance.isReady());

            stop(instance);
            assertFalse(instance.isReady());
            assertTrue(instance.isDying());
        }

        @Test
        @DisplayName("销毁后不应再判定为就绪")
        void shouldNotBeReadyAfterTearDown() {
            prepareReady(instance);
            destroy(instance);

            assertFalse(instance.isReady());
            assertTrue(instance.isDestroyed());
        }

        @Test
        @DisplayName("重复销毁应保持幂等")
        void tearDownShouldBeIdempotent() {
            prepareReady(instance);

            destroy(instance);
            destroy(instance);
            destroy(instance);

            verify(container, times(1)).stop();
        }
    }

    @Nested
    @DisplayName("引用计数")
    class ReferenceCountingTests {

        @Test
        @DisplayName("初始时应为空闲且无活跃请求")
        void shouldBeIdleInitially() {
            assertTrue(instance.isIdle());
            assertEquals(0, instance.getActiveRequestCount());
        }

        @Test
        @DisplayName("未就绪时 tryEnter 应失败")
        void tryEnterShouldFailWhenNotReady() {
            assertFalse(instance.tryEnter());
            assertEquals(0, instance.getActiveRequestCount());
        }

        @Test
        @DisplayName("就绪后 tryEnter 应成功并增加活跃计数")
        void tryEnterShouldSucceedWhenReady() {
            prepareReady(instance);

            assertTrue(instance.tryEnter());
            assertEquals(1, instance.getActiveRequestCount());
            assertFalse(instance.isIdle());
        }

        @Test
        @DisplayName("濒死状态下 tryEnter 应失败")
        void tryEnterShouldFailWhenDying() {
            prepareReady(instance);
            stop(instance);

            assertFalse(instance.tryEnter());
            assertEquals(0, instance.getActiveRequestCount());
        }

        @Test
        @DisplayName("exit 应递减活跃请求计数")
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
        @DisplayName("多次 exit 后活跃计数不应出现负数")
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
    @DisplayName("标签管理")
    class ActiveInvocationRegistryTests {

        @Test
        @DisplayName("beginInvocation 应登记快照并在 completeInvocation 时回收")
        void beginInvocationShouldTrackSnapshotUntilCompletion() {
            prepareReady(instance);
            ActiveInvocationSnapshot snapshot = new ActiveInvocationSnapshot(
                    "trace-1",
                    "test-ling:demo.Service",
                    "execute",
                    "caller-a",
                    "POST /demo",
                    instance.getVersion(),
                    100L,
                    11L,
                    "worker-1");

            long invocationId = instance.beginInvocation(snapshot);

            assertTrue(invocationId > 0);
            assertEquals(1, instance.getActiveRequestCount());
            List<ActiveInvocationSnapshot> active = instance.snapshotActiveInvocations();
            assertEquals(1, active.size());
            assertEquals("trace-1", active.get(0).getTraceId());
            assertEquals("test-ling:demo.Service", active.get(0).getServiceFQSID());
            assertEquals("execute", active.get(0).getMethodName());

            instance.completeInvocation(invocationId);

            assertEquals(0, instance.getActiveRequestCount());
            assertTrue(instance.snapshotActiveInvocations().isEmpty());
        }

        @Test
        @DisplayName("未就绪时 beginInvocation 不应登记快照")
        void beginInvocationShouldFailWhenInstanceIsNotReady() {
            ActiveInvocationSnapshot snapshot = new ActiveInvocationSnapshot(
                    "trace-x",
                    "test-ling:demo.Service",
                    "execute",
                    "caller-a",
                    "POST /demo",
                    instance.getVersion(),
                    100L,
                    11L,
                    "worker-1");

            long invocationId = instance.beginInvocation(snapshot);

            assertEquals(-1L, invocationId);
            assertEquals(0, instance.getActiveRequestCount());
            assertTrue(instance.snapshotActiveInvocations().isEmpty());
        }

        @Test
        @DisplayName("snapshot 为 null 时应提前返回 -1，不递增活跃计数器")
        void beginInvocationWithNullSnapshotShouldReturnFailWithoutIncrementing() {
            prepareReady(instance);

            long invocationId = instance.beginInvocation(null);

            assertEquals(-1L, invocationId);
            // 计数器不应被递增——否则调用方收到 -1 不会调 completeInvocation，导致永不归零
            assertEquals(0, instance.getActiveRequestCount());
            assertTrue(instance.snapshotActiveInvocations().isEmpty());
        }

        @Test
        @DisplayName("snapshot 为 null 后正常调用应仍能正确登记")
        void beginInvocationShouldWorkAfterNullSnapshotWasRejected() {
            prepareReady(instance);

            // 先传 null 被拒绝
            assertEquals(-1L, instance.beginInvocation(null));
            assertEquals(0, instance.getActiveRequestCount());

            // 再传正常快照应成功
            ActiveInvocationSnapshot snapshot = new ActiveInvocationSnapshot(
                    "trace-1", "test-ling:demo.Service", "execute",
                    "caller-a", "POST /demo", instance.getVersion(),
                    100L, 11L, "worker-1");
            long invocationId = instance.beginInvocation(snapshot);

            assertTrue(invocationId > 0);
            assertEquals(1, instance.getActiveRequestCount());

            instance.completeInvocation(invocationId);
            assertEquals(0, instance.getActiveRequestCount());
        }
    }

    @Nested
    @DisplayName("活跃调用登记")
    class LabelManagementTests {

        @Test
        @DisplayName("标签视图应为不可变集合")
        void getLabelsShouldReturnUnmodifiableView() {
            Map<String, String> labels = instance.getLabels();
            assertThrows(UnsupportedOperationException.class, () -> labels.put("key", "value"));
        }

        @Test
        @DisplayName("应支持逐个添加标签")
        void addLabelShouldWork() {
            instance.addLabel("env", "canary");
            instance.addLabel("tenant", "T1");

            Map<String, String> labels = instance.getLabels();
            assertEquals("canary", labels.get("env"));
            assertEquals("T1", labels.get("tenant"));
        }

        @Test
        @DisplayName("空标签键值应被安全忽略")
        void addLabelShouldIgnoreNulls() {
            instance.addLabel(null, "value");
            instance.addLabel("key", null);
            instance.addLabel(null, null);

            assertTrue(instance.getLabels().isEmpty());
        }

        @Test
        @DisplayName("应支持批量添加标签")
        void addLabelsShouldAddBatch() {
            HashMap<String, String> labels = new HashMap<>();
            labels.put("a", "1");
            labels.put("b", "2");
            instance.addLabels(labels);

            assertEquals(2, instance.getLabels().size());
        }
    }

    @Nested
    @DisplayName("并发安全")
    class ConcurrencyTests {

        @Test
        @DisplayName("并发进入与退出后状态应保持一致")
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
        @DisplayName("停止后应阻止新的进入请求")
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

    @Nested
    @DisplayName("服务方法注册与查询")
    class ServiceMethodTests {

        @Test
        @DisplayName("注册后应能查到对应方法")
        void shouldFindRegisteredMethod() {
            instance.registerServiceMethod("ling-a:orderService", "createOrder", new String[]{"java.lang.String", "int"});

            assertTrue(instance.hasServiceMethod("ling-a:orderService", "createOrder",
                    Arrays.asList("java.lang.String", "int")));
        }

        @Test
        @DisplayName("参数类型不同应视为不同方法")
        void differentParameterTypesShouldBeDifferent() {
            instance.registerServiceMethod("ling-a:orderService", "createOrder", new String[]{"java.lang.String"});

            assertFalse(instance.hasServiceMethod("ling-a:orderService", "createOrder",
                    Arrays.asList("java.lang.String", "int")));
        }

        @Test
        @DisplayName("未注册的服务应返回 false")
        void unregisteredServiceShouldReturnFalse() {
            assertFalse(instance.hasServiceMethod("ling-a:unknownService", "anyMethod",
                    Arrays.asList()));
        }

        @Test
        @DisplayName("同一服务可注册多个方法")
        void multipleMethodsUnderSameService() {
            instance.registerServiceMethod("ling-a:orderService", "createOrder", new String[]{"java.lang.String"});
            instance.registerServiceMethod("ling-a:orderService", "cancelOrder", new String[]{"java.lang.String"});

            assertTrue(instance.hasServiceMethod("ling-a:orderService", "createOrder",
                    Arrays.asList("java.lang.String")));
            assertTrue(instance.hasServiceMethod("ling-a:orderService", "cancelOrder",
                    Arrays.asList("java.lang.String")));
        }

        @Test
        @DisplayName("clearDetachedState 后所有服务方法应被清空")
        void clearDetachedStateShouldEvictAllServiceMethods() {
            instance.registerServiceMethod("ling-a:orderService", "createOrder", new String[]{"java.lang.String"});
            instance.clearDetachedState();

            assertFalse(instance.hasServiceMethod("ling-a:orderService", "createOrder",
                    Arrays.asList("java.lang.String")));
        }

        @Test
        @DisplayName("fqsid 或 methodName 为 null 时应安全忽略")
        void nullArgumentsShouldBeIgnored() {
            instance.registerServiceMethod(null, "createOrder", new String[]{"java.lang.String"});
            instance.registerServiceMethod("ling-a:orderService", null, new String[]{"java.lang.String"});

            assertFalse(instance.hasServiceMethod(null, "createOrder", Arrays.asList("java.lang.String")));
            assertFalse(instance.hasServiceMethod("ling-a:orderService", null, Arrays.asList("java.lang.String")));
        }
    }

    @Nested
    @DisplayName("字符串表示")
    class ToStringTests {

        @Test
        @DisplayName("toString 应包含关键状态信息")
        void toStringShouldContainKeyInfo() {
            prepareReady(instance);
            instance.tryEnter();

            String value = instance.toString();

            assertTrue(value.contains("1.0.0"));
            assertTrue(value.contains("state=READY"));
            assertTrue(value.contains("activeRequests=1"));
        }
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
