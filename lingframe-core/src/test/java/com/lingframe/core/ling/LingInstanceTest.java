package com.lingframe.core.ling;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.InstanceStatus;
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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // 🔥 设置宽松模式
@DisplayName("LingInstance 灵元测试")
public class LingInstanceTest {

    @Mock
    private LingContainer container;

    private LingDefinition definition;

    private LingInstance instance;

    @BeforeEach
    void setUp() {
        definition = createDefinition();

        when(container.isActive()).thenReturn(true);

        instance = new LingInstance(container, definition, new EventBus());
    }

    /**
     * 创建测试用的 LingDefinition
     */
    private LingDefinition createDefinition() {
        LingDefinition def = new LingDefinition();
        def.setId("test-ling");
        def.setVersion("1.0.0");
        return def;
    }

    // ==================== 构造函数测试 ====================

    @Nested
    @DisplayName("构造函数")
    class ConstructorTests {

        @Test
        @DisplayName("正常构造应成功")
        void shouldConstructSuccessfully() {
            assertNotNull(instance);
            assertEquals("1.0.0", instance.getVersion());
            assertNotNull(instance.getContainer());
            assertNotNull(instance.getDefinition());
        }

        @Test
        @DisplayName("version 为 null 应抛出异常")
        void shouldThrowWhenVersionIsNull() {
            definition.setVersion(null);
            assertThrows(InvalidArgumentException.class,
                    () -> new LingInstance(container, definition, new EventBus()));
        }

        @Test
        @DisplayName("version 为空白应抛出异常")
        void shouldThrowWhenVersionIsBlank() {
            definition.setVersion(" ");
            assertThrows(InvalidArgumentException.class,
                    () -> new LingInstance(container, definition, new EventBus()));
        }

        @Test
        @DisplayName("container 为 null 应抛出异常")
        void shouldThrowWhenContainerIsNull() {
            assertThrows(NullPointerException.class,
                    () -> new LingInstance(null, definition, new EventBus()));
        }

        @Test
        @DisplayName("definition 为 null 应抛出异常")
        void shouldThrowWhenDefinitionIsNull() {
            assertThrows(NullPointerException.class,
                    () -> new LingInstance(container, null, new EventBus()));
        }
    }

    // ==================== 状态管理测试 ====================

    @Nested
    @DisplayName("状态管理")
    class StateManagementTests {

        @Test
        @DisplayName("新实例应该是未就绪状态")
        void newInstanceShouldNotBeReady() {
            assertFalse(instance.isReady());
        }

        @Test
        @DisplayName("markReady 后应该变为就绪状态")
        void shouldBeReadyAfterMarkReady() {
            // 🔥 在需要时设置 mock 行为
            when(container.isActive()).thenReturn(true);

            instance.getStateMachine().transition(InstanceStatus.LOADING);
            instance.getStateMachine().transition(InstanceStatus.STARTING);
            instance.markReady();
            assertTrue(instance.isReady());
        }

        @Test
        @DisplayName("容器不活跃时 isReady 应返回 false")
        void shouldNotBeReadyWhenContainerInactive() {
            when(container.isActive()).thenReturn(true);
            instance.getStateMachine().transition(InstanceStatus.LOADING);
            instance.getStateMachine().transition(InstanceStatus.STARTING);
            instance.markReady();
            assertTrue(instance.isReady());

            // 切换为不活跃
            when(container.isActive()).thenReturn(false);
            assertFalse(instance.isReady());
        }

        @Test
        @DisplayName("标记为 dying 后 isReady 应返回 false")
        void shouldNotBeReadyWhenDying() {
            when(container.isActive()).thenReturn(true);

            instance.getStateMachine().transition(InstanceStatus.LOADING);
            instance.getStateMachine().transition(InstanceStatus.STARTING);
            instance.markReady();
            assertTrue(instance.isReady());

            instance.markDying();
            assertFalse(instance.isReady());
            assertTrue(instance.isDying());
        }

        @Test
        @DisplayName("销毁后 isReady 应返回 false")
        void shouldNotBeReadyAfterDestroy() {
            when(container.isActive()).thenReturn(true);

            instance.getStateMachine().transition(InstanceStatus.LOADING);
            instance.getStateMachine().transition(InstanceStatus.STARTING);
            instance.markReady();
            instance.destroy();

            assertFalse(instance.isReady());
            assertTrue(instance.isDestroyed());
        }

        @Test
        @DisplayName("销毁应该是幂等的")
        void destroyShouldBeIdempotent() {
            when(container.isActive()).thenReturn(true);

            instance.getStateMachine().transition(InstanceStatus.LOADING);
            instance.getStateMachine().transition(InstanceStatus.STARTING);
            instance.markReady();

            instance.destroy();
            instance.destroy();
            instance.destroy();

            // 只应该调用一次 container.stop()
            verify(container, times(1)).stop();
        }
    }

    // ==================== 引用计数测试 ====================

    @Nested
    @DisplayName("引用计数")
    class ReferenceCountingTests {

        @Test
        @DisplayName("初始状态应该是闲置的")
        void shouldBeIdleInitially() {
            assertTrue(instance.isIdle());
            assertEquals(0, instance.getActiveRequestCount());
        }

        @Test
        @DisplayName("tryEnter 在未就绪时应失败")
        void tryEnterShouldFailWhenNotReady() {
            assertFalse(instance.tryEnter());
            assertEquals(0, instance.getActiveRequestCount());
        }

        @Test
        @DisplayName("tryEnter 在就绪时应成功")
        void tryEnterShouldSucceedWhenReady() {
            when(container.isActive()).thenReturn(true);
            instance.getStateMachine().transition(InstanceStatus.LOADING);
            instance.getStateMachine().transition(InstanceStatus.STARTING);
            instance.markReady();

            assertTrue(instance.tryEnter());
            assertEquals(1, instance.getActiveRequestCount());
            assertFalse(instance.isIdle());
        }

        @Test
        @DisplayName("tryEnter 在 dying 状态应失败")
        void tryEnterShouldFailWhenDying() {
            when(container.isActive()).thenReturn(true);
            instance.getStateMachine().transition(InstanceStatus.LOADING);
            instance.getStateMachine().transition(InstanceStatus.STARTING);
            instance.markReady();
            instance.markDying();

            assertFalse(instance.tryEnter());
            assertEquals(0, instance.getActiveRequestCount());
        }

        @Test
        @DisplayName("exit 后计数应减少")
        void exitShouldDecrementCount() {
            when(container.isActive()).thenReturn(true);
            instance.getStateMachine().transition(InstanceStatus.LOADING);
            instance.getStateMachine().transition(InstanceStatus.STARTING);
            instance.markReady();

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
        @DisplayName("多次 exit 不应导致计数为负")
        void exitShouldNotGoNegative() {
            when(container.isActive()).thenReturn(true);
            instance.getStateMachine().transition(InstanceStatus.LOADING);
            instance.getStateMachine().transition(InstanceStatus.STARTING);
            instance.markReady();
            instance.tryEnter();

            instance.exit();
            instance.exit(); // 多余的 exit
            instance.exit(); // 多余的 exit

            // 计数应该被修正为 0，不能为负
            assertTrue(instance.getActiveRequestCount() >= 0);
        }
    }

    // ==================== 标签管理测试 ====================

    @Nested
    @DisplayName("标签管理")
    class LabelManagementTests {

        @Test
        @DisplayName("getLabels 应返回不可变视图")
        void getLabelsShouldReturnUnmodifiableView() {
            Map<String, String> labels = instance.getLabels();

            assertThrows(UnsupportedOperationException.class, () -> labels.put("key", "value"));
        }

        @Test
        @DisplayName("addLabel 应正确添加标签")
        void addLabelShouldWork() {
            instance.addLabel("env", "canary");
            instance.addLabel("tenant", "T1");

            Map<String, String> labels = instance.getLabels();
            assertEquals("canary", labels.get("env"));
            assertEquals("T1", labels.get("tenant"));
        }

        @Test
        @DisplayName("addLabel 应忽略 null 值")
        void addLabelShouldIgnoreNulls() {
            instance.addLabel(null, "value");
            instance.addLabel("key", null);
            instance.addLabel(null, null);

            assertTrue(instance.getLabels().isEmpty());
        }

        @Test
        @DisplayName("addLabels 应批量添加")
        void addLabelsShouldAddBatch() {
            HashMap<String, String> labels = new HashMap<>();
            labels.put("a", "1");
            labels.put("b", "2");
            instance.addLabels(labels);

            assertEquals(2, instance.getLabels().size());
        }
    }

    // ==================== 并发安全测试 ====================

    @Nested
    @DisplayName("并发安全")
    class ConcurrencyTests {

        @Test
        @DisplayName("并发 tryEnter/exit 应保持计数一致")
        void concurrentEnterExitShouldBeConsistent() throws InterruptedException {
            when(container.isActive()).thenReturn(true);
            instance.getStateMachine().transition(InstanceStatus.LOADING);
            instance.getStateMachine().transition(InstanceStatus.STARTING);
            instance.markReady();

            int threadCount = 100;
            int operationsPerThread = 1000;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            AtomicInteger successfulEnters = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await(); // 等待统一开始
                        for (int j = 0; j < operationsPerThread; j++) {
                            if (instance.tryEnter()) {
                                successfulEnters.incrementAndGet();
                                // 模拟一点处理时间
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

            startLatch.countDown(); // 开始！
            boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertTrue(completed, "测试应在30秒内完成");

            // 所有操作完成后，计数应该归零
            assertEquals(0, instance.getActiveRequestCount());
            assertTrue(instance.isIdle());

            // 应该有成功的进入操作
            assertTrue(successfulEnters.get() > 0);
        }

        @Test
        @DisplayName("并发 markDying 应阻止新的 tryEnter")
        void markDyingShouldBlockNewEnters() throws InterruptedException {
            when(container.isActive()).thenReturn(true);
            instance.getStateMachine().transition(InstanceStatus.LOADING);
            instance.getStateMachine().transition(InstanceStatus.STARTING);
            instance.markReady();

            int threadCount = 50;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            AtomicInteger successAfterDying = new AtomicInteger(0);

            // 先让一些请求进入
            for (int i = 0; i < 10; i++) {
                assertTrue(instance.tryEnter());
            }

            // 标记为 dying
            instance.markDying();

            // 启动并发请求
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        if (instance.tryEnter()) {
                            successAfterDying.incrementAndGet();
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

            assertTrue(completed, "测试应在10秒内完成");

            // dying 后不应该有新的进入成功
            assertEquals(0, successAfterDying.get());

            // 之前的 10 个请求应该还在
            assertEquals(10, instance.getActiveRequestCount());
        }
    }

    // ==================== toString 测试 ====================

    @Test
    @DisplayName("toString 应包含关键信息")
    void toStringShouldContainKeyInfo() {
        when(container.isActive()).thenReturn(true);
        instance.getStateMachine().transition(InstanceStatus.LOADING);
        instance.getStateMachine().transition(InstanceStatus.STARTING);
        instance.markReady();
        instance.tryEnter();

        String str = instance.toString();

        assertTrue(str.contains("1.0.0"));
        assertTrue(str.contains("state=READY"));
        assertTrue(str.contains("activeRequests=1"));
    }
}