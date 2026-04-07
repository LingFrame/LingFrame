package com.lingframe.core.pipeline;

import com.lingframe.core.ling.InvokableMethodCache;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.spi.LingContainer;
import com.lingframe.core.spi.LingServiceInvoker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("TerminalInvokerFilter 测试")
class TerminalInvokerFilterTest {

    @Test
    @DisplayName("配置 retryCount 后应真正重试到成功")
    void shouldRetryUntilSuccess() throws Throwable {
        TestService service = new TestService();
        LingContainer container = buildContainer(service);
        LingInstance instance = buildReadyInstance(container);

        AtomicInteger attempts = new AtomicInteger(0);
        LingServiceInvoker flakyInvoker = new LingServiceInvoker() {
            @Override
            public Object invoke(LingInstance ignored, Object bean, Method method, Object[] args) throws Exception {
                if (attempts.getAndIncrement() < 2) {
                    throw new IllegalStateException("transient");
                }
                return method.invoke(bean, args);
            }
        };

        TerminalInvokerFilter filter = new TerminalInvokerFilter(new InvokableMethodCache(), flakyInvoker);
        InvocationContext context = buildInvocationContext(instance, 2, null);

        try {
            Object result = filter.doFilter(context, null);
            assertEquals("pong", result);
            assertEquals(3, attempts.get());
        } finally {
            context.recycle();
        }
    }

    @Test
    @DisplayName("重试耗尽后应返回 fallbackValue")
    void shouldReturnFallbackWhenRetriesExhausted() throws Throwable {
        TestService service = new TestService();
        LingContainer container = buildContainer(service);
        LingInstance instance = buildReadyInstance(container);

        AtomicInteger attempts = new AtomicInteger(0);
        LingServiceInvoker failingInvoker = new LingServiceInvoker() {
            @Override
            public Object invoke(LingInstance ignored, Object bean, Method method, Object[] args) {
                attempts.incrementAndGet();
                throw new IllegalStateException("still failing");
            }
        };

        TerminalInvokerFilter filter = new TerminalInvokerFilter(new InvokableMethodCache(), failingInvoker);
        InvocationContext context = buildInvocationContext(instance, 1, "fallback-value");

        try {
            Object result = filter.doFilter(context, null);
            assertEquals("fallback-value", result);
            assertEquals(2, attempts.get());
        } finally {
            context.recycle();
        }
    }

    private LingContainer buildContainer(TestService service) {
        LingContainer container = mock(LingContainer.class);
        when(container.getClassLoader()).thenReturn(TestService.class.getClassLoader());
        when(container.getBean(TestService.class)).thenReturn(service);
        return container;
    }

    private LingInstance buildReadyInstance(LingContainer container) {
        LingInstance instance = mock(LingInstance.class);
        when(instance.getContainer()).thenReturn(container);
        when(instance.getClassLoader()).thenReturn(TestService.class.getClassLoader());
        when(instance.getLingId()).thenReturn("demo-ling");
        when(instance.getVersion()).thenReturn("1.0.0");
        return instance;
    }

    private InvocationContext buildInvocationContext(LingInstance instance, int retryCount, String fallbackValue) {
        InvocationContext context = InvocationContext.obtain();
        context.setServiceFQSID("demo-ling:" + TestService.class.getName());
        context.setMethodName("ping");
        context.setArgs(new Object[0]);
        context.routing().setTargetInstance(instance);
        context.resolution().setTargetClassName(TestService.class.getName());
        context.resolution().setResolvedParameterTypes(new Class<?>[0]);
        context.governance().setRetryCount(retryCount);
        context.governance().setFallbackValue(fallbackValue);
        return context;
    }

    public static class TestService {
        public String ping() {
            return "pong";
        }
    }
}
