package com.lingframe.starter.interceptor;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.spi.LingContainer;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** AOP 治理完成后的最终准入及释放测试。 */
@DisplayName("灵核 Bean 入口的最终准入")
class LingCoreBeanGovernanceInterceptorTest {
    private InvocationPipelineEngine engine;
    private MethodInvocation invocation;
    private LingInstance instance;
    private LingCoreBeanGovernanceInterceptor interceptor;

    @BeforeEach
    void setUp() throws Exception {
        engine = mock(InvocationPipelineEngine.class);
        invocation = mock(MethodInvocation.class);
        when(invocation.getMethod()).thenReturn(Business.class.getMethod("execute"));
        when(invocation.getArguments()).thenReturn(new Object[0]);
        LingDefinition definition = new LingDefinition();
        definition.setId("lingcore-app");
        definition.setVersion("v1");
        LingContainer container = mock(LingContainer.class);
        instance = spy(new LingInstance(container, definition, null));
        doReturn(true).when(instance).isReady();
        interceptor = new LingCoreBeanGovernanceInterceptor(engine, true, true, null);
        LingCallContext.setLingId("caller");
    }

    @AfterEach
    void clearContext() {
        LingCallContext.clear();
    }

    @Test
    @DisplayName("治理通过后禁用也不能进入真实业务")
    void disabledAfterGovernance() throws Throwable {
        when(engine.invoke(any())).thenAnswer(call -> {
            InvocationContext ctx = call.getArgument(0);
            ctx.routing().setTargetInstance(instance);
            instance.setAcceptNewRequests(false);
            return null;
        });
        assertThrows(LingInvocationException.class, () -> interceptor.invoke(invocation));
        verify(invocation, never()).proceed();
        assertEquals(0, instance.getActiveRequestCount());
    }

    @Test
    @DisplayName("已准入业务可继续且异常退出释放计数")
    void tracksBusinessUntilExit() throws Throwable {
        when(engine.invoke(any())).thenAnswer(call -> {
            InvocationContext ctx = call.getArgument(0);
            ctx.routing().setTargetInstance(instance);
            return null;
        });
        when(invocation.proceed()).thenAnswer(call -> {
            assertEquals(1, instance.getActiveRequestCount());
            instance.setAcceptNewRequests(false);
            assertEquals(1, instance.getActiveRequestCount());
            throw new IllegalStateException("business failure");
        });
        assertThrows(IllegalStateException.class, () -> interceptor.invoke(invocation));
        assertEquals(0, instance.getActiveRequestCount());
        assertTrue(instance.snapshotActiveInvocations().isEmpty());
    }

    @Test
    @DisplayName("CompletionStage 完成前保持实例在途，完成后释放")
    void tracksCompletionStageUntilTerminal() throws Throwable {
        when(engine.invoke(any())).thenAnswer(call -> {
            InvocationContext ctx = call.getArgument(0);
            ctx.routing().setTargetInstance(instance);
            return null;
        });
        CompletableFuture<String> future = new CompletableFuture<>();
        when(invocation.proceed()).thenReturn(future);
        assertSame(future, interceptor.invoke(invocation));
        assertEquals(1, instance.getActiveRequestCount());
        future.complete("ok");
        assertEquals(0, instance.getActiveRequestCount());
    }

    @Test
    @DisplayName("Future 取消后释放准入且保留 Future 接口")
    void tracksFutureCancellation() throws Throwable {
        when(engine.invoke(any())).thenAnswer(call -> {
            InvocationContext ctx = call.getArgument(0);
            ctx.routing().setTargetInstance(instance);
            return null;
        });
        Future<String> future = new CompletableFuture<>();
        when(invocation.proceed()).thenReturn(future);
        Object result = interceptor.invoke(invocation);
        assertTrue(result instanceof Future);
        assertEquals(1, instance.getActiveRequestCount());
        assertTrue(((Future<?>) result).cancel(true));
        assertEquals(0, instance.getActiveRequestCount());
    }

    @Test
    @DisplayName("普通 Future 未被调用方观察时也在终态释放准入")
    void tracksGenericFutureWithoutConsumerObservation() throws Throwable {
        when(engine.invoke(any())).thenAnswer(call -> {
            InvocationContext ctx = call.getArgument(0);
            ctx.routing().setTargetInstance(instance);
            return null;
        });
        FutureTask<String> future = new FutureTask<>(() -> "ok");
        when(invocation.proceed()).thenReturn(future);
        Object result = interceptor.invoke(invocation);
        assertTrue(result instanceof Future);
        assertEquals(1, instance.getActiveRequestCount());
        future.run();
        for (int i = 0; i < 20 && instance.getActiveRequestCount() != 0; i++) {
            TimeUnit.MILLISECONDS.sleep(25);
        }
        assertEquals(0, instance.getActiveRequestCount());
    }

    /** 用于入口方法元数据解析的业务类型。 */
    public static class Business {
        public void execute() {
        }
    }
}
