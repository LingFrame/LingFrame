package com.lingframe.core.invoker;

import com.lingframe.api.exception.ServiceUnavailableException;
import com.lingframe.core.ling.ActiveInvocationSnapshot;
import com.lingframe.core.ling.LingInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("FastLingServiceInvoker 测试")
class FastLingServiceInvokerTest {

    private FastLingServiceInvoker invoker;
    private LingInstance instance;
    private Object bean;

    @BeforeEach
    void setUp() throws Exception {
        invoker = new FastLingServiceInvoker();
        instance = mock(LingInstance.class);
        bean = new TestService();

        when(instance.beginInvocation(any(ActiveInvocationSnapshot.class))).thenReturn(1L);
        when(instance.getClassLoader()).thenReturn(Thread.currentThread().getContextClassLoader());
    }

    @Test
    @DisplayName("反射调用返回正确结果")
    void shouldInvokeViaReflection() throws Exception {
        Method method = TestService.class.getMethod("echo", String.class);
        Object result = invoker.invoke(instance, bean, method, new Object[]{"hello"});

        assertEquals("hello", result);
        verify(instance).completeInvocation(1L);
    }

    @Test
    @DisplayName("实例不可用时抛出 ServiceUnavailableException")
    void shouldThrowWhenInstanceNotReady() throws Exception {
        when(instance.beginInvocation(any(ActiveInvocationSnapshot.class))).thenReturn(-1L);
        Method method = TestService.class.getMethod("echo", String.class);

        assertThrows(ServiceUnavailableException.class, () ->
                invoker.invoke(instance, bean, method, new Object[]{"hello"}));
    }

    @Test
    @DisplayName("MethodHandle 调用返回正确结果")
    void shouldInvokeFastViaMethodHandle() throws Throwable {
        MethodHandle mh = MethodHandles.lookup().findVirtual(
                TestService.class, "echo", MethodType.methodType(String.class, String.class));

        // invokeWithArguments 需要展开参数：bean + 方法参数
        Object result = invoker.invokeFast(instance, mh, new Object[]{bean, "fast-hello"});

        assertEquals("fast-hello", result);
        verify(instance).completeInvocation(1L);
    }

    @Test
    @DisplayName("MethodHandle 调用时实例不可用抛出异常")
    void shouldThrowOnMethodHandleWhenNotReady() throws Throwable {
        when(instance.beginInvocation(any(ActiveInvocationSnapshot.class))).thenReturn(-1L);
        MethodHandle mh = MethodHandles.lookup().findVirtual(
                TestService.class, "echo", MethodType.methodType(String.class, String.class));

        assertThrows(ServiceUnavailableException.class, () ->
                invoker.invokeFast(instance, mh, new Object[]{bean, "test"}));
    }

    @Test
    @DisplayName("MethodHandle 调用后恢复上下文类加载器")
    void shouldRestoreClassLoaderAfterMethodHandle() throws Throwable {
        ClassLoader originalCL = Thread.currentThread().getContextClassLoader();
        when(instance.getClassLoader()).thenReturn(mock(ClassLoader.class));

        MethodHandle mh = MethodHandles.lookup().findVirtual(
                TestService.class, "echo", MethodType.methodType(String.class, String.class));

        invoker.invokeFast(instance, mh, new Object[]{bean, "test"});

        assertSame(originalCL, Thread.currentThread().getContextClassLoader());
    }

    @Test
    @DisplayName("MethodHandle 异常时也应恢复上下文类加载器")
    void shouldRestoreClassLoaderOnMethodHandleException() throws Throwable {
        ClassLoader originalCL = Thread.currentThread().getContextClassLoader();
        when(instance.getClassLoader()).thenReturn(mock(ClassLoader.class));

        MethodHandle mh = MethodHandles.lookup().findVirtual(
                TestService.class, "fail", MethodType.methodType(void.class));

        try {
            invoker.invokeFast(instance, mh, new Object[]{bean});
        } catch (RuntimeException ignored) {
        }

        assertSame(originalCL, Thread.currentThread().getContextClassLoader());
    }

    public static class TestService {
        public String echo(String msg) {
            return msg;
        }

        public void fail() {
            throw new RuntimeException("intentional failure");
        }
    }
}
