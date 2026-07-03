package com.lingframe.core.invoker;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.ling.ActiveInvocationSnapshot;
import com.lingframe.core.ling.LingInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("DefaultLingServiceInvoker 测试")
class DefaultLingServiceInvokerTest {

    private DefaultLingServiceInvoker invoker;
    private LingInstance instance;
    private Object bean;
    private Method method;

    @BeforeEach
    void setUp() throws Exception {
        invoker = new DefaultLingServiceInvoker();
        instance = mock(LingInstance.class);
        bean = new TestService();
        method = TestService.class.getMethod("echo", String.class);

        when(instance.beginInvocation(any(ActiveInvocationSnapshot.class))).thenReturn(1L);
        when(instance.getClassLoader()).thenReturn(Thread.currentThread().getContextClassLoader());
    }

    @Test
    @DisplayName("正常调用返回正确结果")
    void shouldInvokeSuccessfully() throws Exception {
        Object result = invoker.invoke(instance, bean, method, new Object[]{"hello"});

        assertEquals("hello", result);
        verify(instance).completeInvocation(1L);
    }

    @Test
    @DisplayName("实例不可用时抛出 LingInvocationException")
    void shouldThrowWhenInstanceNotReady() {
        when(instance.beginInvocation(any(ActiveInvocationSnapshot.class))).thenReturn(-1L);

        assertThrows(LingInvocationException.class, () ->
                invoker.invoke(instance, bean, method, new Object[]{"hello"}));
    }

    @Test
    @DisplayName("调用后恢复上下文类加载器")
    void shouldRestoreContextClassLoader() throws Exception {
        ClassLoader originalCL = Thread.currentThread().getContextClassLoader();
        ClassLoader mockCL = mock(ClassLoader.class);
        when(instance.getClassLoader()).thenReturn(mockCL);

        invoker.invoke(instance, bean, method, new Object[]{"hello"});

        assertSame(originalCL, Thread.currentThread().getContextClassLoader());
    }

    @Test
    @DisplayName("异常时也应恢复上下文类加载器")
    void shouldRestoreClassLoaderOnException() throws Exception {
        ClassLoader originalCL = Thread.currentThread().getContextClassLoader();
        Method badMethod = TestService.class.getMethod("fail");
        when(instance.getClassLoader()).thenReturn(mock(ClassLoader.class));

        assertThrows(Exception.class, () ->
                invoker.invoke(instance, bean, badMethod, new Object[0]));
        assertSame(originalCL, Thread.currentThread().getContextClassLoader());
    }

    @Test
    @DisplayName("参数数量不匹配时抛出 InvalidArgumentException")
    void shouldThrowOnArgumentCountMismatch() throws Exception {
        // 传入 null 参数数组
        assertThrows(Exception.class, () ->
                invoker.invoke(instance, bean, method, null));
    }

    @Test
    @DisplayName("调用后完成 invocation")
    void shouldCompleteInvocationAfterSuccess() throws Exception {
        invoker.invoke(instance, bean, method, new Object[]{"test"});

        verify(instance).completeInvocation(1L);
    }

    @Test
    @DisplayName("异常时也应完成 invocation")
    void shouldCompleteInvocationOnFailure() throws Exception {
        Method failMethod = TestService.class.getMethod("fail");

        try {
            invoker.invoke(instance, bean, failMethod, new Object[0]);
        } catch (Exception ignored) {
        }

        verify(instance).completeInvocation(1L);
    }

    @Test
    @DisplayName("参数类型不匹配时抛出 InvalidArgumentException")
    void shouldThrowOnArgumentTypeMismatch() throws Exception {
        Method intMethod = TestService.class.getMethod("takeInt", int.class);
        // 传入 String 给 int 参数
        assertThrows(Exception.class, () ->
                invoker.invoke(instance, bean, intMethod, new Object[]{"not-an-int"}));
    }

    @Test
    @DisplayName("null 参数传给基本类型时抛出 InvalidArgumentException")
    void shouldThrowOnNullForPrimitive() throws Exception {
        Method intMethod = TestService.class.getMethod("takeInt", int.class);
        assertThrows(Exception.class, () ->
                invoker.invoke(instance, bean, intMethod, new Object[]{null}));
    }

    @Test
    @DisplayName("InvocationTargetException 透传业务异常")
    void shouldPropagateBusinessException() throws Exception {
        Method failMethod = TestService.class.getMethod("fail");

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                invoker.invoke(instance, bean, failMethod, new Object[0]));
        assertEquals("intentional failure", ex.getMessage());
    }

    // 测试用服务类
    public static class TestService {
        public String echo(String msg) {
            return msg;
        }

        public void fail() {
            throw new RuntimeException("intentional failure");
        }

        public int takeInt(int value) {
            return value;
        }
    }
}
