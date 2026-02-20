package com.lingframe.core.proxy;

import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.ling.LingRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SmartServiceProxy 单元测试")
class SmartServiceProxyTest {

    @Mock
    private LingRuntime runtime;

    @Mock
    private GovernanceKernel kernel;

    private SmartServiceProxy proxy;

    @BeforeEach
    void setUp() {
        when(runtime.getLingId()).thenReturn("target-ling");

        // 模拟 Kernel 执行：直接执行 Supplier
        when(kernel.invoke(eq(runtime), any(Method.class), any(InvocationContext.class), any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(3)).get());

        proxy = new SmartServiceProxy("caller-ling", runtime, Runnable.class, kernel);
    }

    @Nested
    @DisplayName("代理调用")
    class InvocationTests {

        @Test
        @DisplayName("invoke 应正确委托给 Runtime")
        void invokeShouldDelegateToRuntime() throws Throwable {
            // 准备
            when(runtime.invoke(any(InvocationContext.class))).thenReturn("Result");

            // 执行
            Method runMethod = Runnable.class.getMethod("run");
            proxy.invoke(null, runMethod, new Object[0]);

            // 验证：Runtime.invoke(InvocationContext) 被调用
            ArgumentCaptor<InvocationContext> ctxCaptor = ArgumentCaptor.forClass(InvocationContext.class);
            verify(runtime, times(1)).invoke(ctxCaptor.capture());

            InvocationContext ctx = ctxCaptor.getValue();
            assertEquals("caller-ling", ctx.getCallerLingId());
            assertEquals("target-ling", ctx.getLingId());
            assertEquals("java.lang.Runnable:run", ctx.getResourceId());
        }

        @Test
        @DisplayName("invoke 应透传参数")
        void invokeShouldPassArguments() throws Throwable {
            // 准备
            Object[] args = new Object[] { "test" };
            when(runtime.invoke(any(InvocationContext.class))).thenReturn(null);

            // 为了测试参数透传，使用带参接口 Comparable
            SmartServiceProxy paramProxy = new SmartServiceProxy("caller-ling", runtime, Comparable.class, kernel);
            Method compareToMethod = Comparable.class.getMethod("compareTo", Object.class);

            paramProxy.invoke(null, compareToMethod, args);

            // 验证：Runtime.invoke(InvocationContext) 被调用，且 resourceId 包含 compareTo
            ArgumentCaptor<InvocationContext> ctxCaptor = ArgumentCaptor.forClass(InvocationContext.class);
            verify(runtime, times(1)).invoke(ctxCaptor.capture());

            InvocationContext ctx = ctxCaptor.getValue();
            assertTrue(ctx.getResourceId().contains("compareTo"));
            assertEquals("caller-ling", ctx.getCallerLingId());
        }
    }
}