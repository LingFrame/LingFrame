package com.lingframe.core.invoker;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationExecutionMode;
import com.lingframe.core.spi.LingContainer;
import com.lingframe.core.spi.RoutableTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 准入凭据与真实调用器的组合语义测试。 */
@DisplayName("实例请求准入凭据")
class InvocationAdmissionTest {
    private LingInstance instance;
    private InvocationContext ctx;

    @BeforeEach
    void setUp() {
        LingDefinition definition = new LingDefinition();
        definition.setId("a");
        definition.setVersion("v1");
        LingContainer container = mock(LingContainer.class);
        when(container.getClassLoader()).thenReturn(getClass().getClassLoader());
        instance = spy(new LingInstance(container, definition, null));
        doReturn(true).when(instance).isReady();
        ctx = InvocationContext.obtain();
        ctx.setServiceFQSID("a:execute");
        ctx.setMethodName("execute");
        ctx.routing().setTargetInstance(instance);
    }

    @AfterEach
    void tearDown() {
        ctx.recycle();
    }

    @Test
    @DisplayName("凭据登记调用快照且并发关闭只释放一次")
    void closeExactlyOnce() throws Exception {
        InvocationAdmission admission = InvocationAdmission.acquire(ctx);
        assertEquals(1, instance.getActiveRequestCount());
        assertEquals("execute", instance.snapshotActiveInvocations().get(0).getMethodName());
        instance.setAcceptNewRequests(false);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(admission::close);
            Future<?> second = executor.submit(admission::close);
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            admission.close();
            assertEquals(0, instance.getActiveRequestCount());
            assertTrue(instance.snapshotActiveInvocations().isEmpty());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("治理后被禁用的实例在真实业务开始前被拒绝")
    void rejectsDisabledTarget() {
        instance.setAcceptNewRequests(false);
        LingInvocationException error = assertThrows(LingInvocationException.class, () -> InvocationAdmission.acquire(ctx));
        assertEquals(LingInvocationException.ErrorKind.ROUTE_FAILURE, error.getKind());
        assertEquals(0, instance.getActiveRequestCount());
    }

    @Test
    @DisplayName("模拟及无具体运行时的借道治理不增加真实计数")
    void simulationAndUnresolvedCallsDoNotEnter() {
        instance.setAcceptNewRequests(false);
        ctx.execution().setMode(InvocationExecutionMode.SIMULATION);
        InvocationAdmission.acquire(ctx).close();
        assertEquals(0, instance.getActiveRequestCount());
        ctx.execution().setMode(InvocationExecutionMode.GOVERN_ONLY);
        ctx.routing().setTargetInstance(null);
        InvocationAdmission.acquire(ctx).close();
        assertThrows(NullPointerException.class, () -> InvocationAdmission.acquire(null));
    }

    @Test
    @DisplayName("灵核借道治理也校验其唯一实例的接流许可")
    void coreSingletonAdmission() {
        ctx.routing().setTargetInstance(null);
        RoutableTarget core = mock(RoutableTarget.class);
        when(core.getReadyInstances()).thenReturn(Collections.singletonList(instance));
        ctx.setRuntime(core);
        try (InvocationAdmission admission = InvocationAdmission.acquire(ctx)) {
            assertEquals(1, instance.getActiveRequestCount());
        }
        instance.setAcceptNewRequests(false);
        assertThrows(LingInvocationException.class, () -> InvocationAdmission.acquire(ctx));
    }

    @Test
    @DisplayName("异步业务终态只回调一次且成功结果回灌")
    void completionStageReportsSuccessOnce() {
        InvocationAdmission admission = InvocationAdmission.acquire(ctx);
        CompletableFuture<String> future = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InvocationAdmission.bindAsync(future, admission, error -> {
            calls.incrementAndGet();
            failure.set(error);
        });
        future.complete("ok");
        assertEquals(1, calls.get());
        assertNull(failure.get());
        assertEquals(0, instance.getActiveRequestCount());
    }

    @Test
    @DisplayName("异步业务异常回灌原始失败且准入释放")
    void completionStageReportsFailure() {
        InvocationAdmission admission = InvocationAdmission.acquire(ctx);
        CompletableFuture<String> future = new CompletableFuture<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InvocationAdmission.bindAsync(future, admission, failure::set);
        IllegalStateException expected = new IllegalStateException("downstream failure");
        future.completeExceptionally(expected);
        assertSame(expected, failure.get());
        assertEquals(0, instance.getActiveRequestCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "fast-reflection", "fast-handle"})
    @DisplayName("三个调用器入口均遵守禁用且正常调用结束释放计数")
    void allInvokersRespectAdmission(String mode) throws Throwable {
        Echo bean = new Echo();
        Method method = Echo.class.getMethod("echo");
        org.junit.jupiter.api.function.Executable call = () -> {
            if ("default".equals(mode)) {
                new DefaultLingServiceInvoker().invoke(instance, bean, method, new Object[0]);
            } else if ("fast-reflection".equals(mode)) {
                new FastLingServiceInvoker().invoke(instance, bean, method, new Object[0]);
            } else {
                new FastLingServiceInvoker().invokeFast(instance, MethodHandles.publicLookup().unreflect(method),
                        new Object[] {bean});
            }
        };
        instance.setAcceptNewRequests(false);
        assertThrows(LingInvocationException.class, call);
        assertEquals(0, bean.calls);
        instance.setAcceptNewRequests(true);
        call.execute();
        assertEquals(1, bean.calls);
        assertEquals(0, instance.getActiveRequestCount());
    }

    /** 供实际反射与方法句柄调用的业务对象。 */
    public static class Echo {
        private int calls;

        public String echo() {
            calls++;
            return "ok";
        }
    }
}
