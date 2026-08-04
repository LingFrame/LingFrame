package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.invoker.FastLingServiceInvoker;
import com.lingframe.core.ling.InvokableMethodCache;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.spi.LingContainer;
import com.lingframe.core.spi.LingServiceInvoker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        LingServiceInvoker flakyInvoker = new FlakyInvoker(attempts);

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
        LingServiceInvoker failingInvoker = new FailingInvoker(attempts);

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

    @Test
    @DisplayName("裸 contractId（无冒号）场景下 getServiceBean 不应抛数组越界")
    void shouldNotThrowArrayIndexOutOfBoundsForBareContractId() throws Throwable {
        TestService service = new TestService();
        LingContainer container = buildContainer(service);
        LingInstance instance = buildReadyInstance(container);

        TerminalInvokerFilter filter = new TerminalInvokerFilter(new InvokableMethodCache(), new FastLingServiceInvoker());
        InvocationContext context = InvocationContext.obtain();
        // 裸 contractId：无冒号分隔，触发 getServiceBean 兜底分支
        context.setServiceFQSID(TestService.class.getName());
        context.setMethodName("ping");
        context.setArgs(new Object[0]);
        context.routing().setTargetInstance(instance);
        // 显式不设置 targetClassName，强制走 getServiceBean 的 serviceName 兜底路径
        context.resolution().setResolvedParameterTypes(new Class<?>[0]);
        context.governance().setRetryCount(0);

        try {
            Object result = filter.doFilter(context, null);
            assertEquals("pong", result);
        } finally {
            context.recycle();
        }
    }

    @Test
    @DisplayName("TIMEOUT 类型化异常不应重试且应返回 fallbackValue")
    void shouldNotRetryTimeoutAndReturnFallback() throws Throwable {
        TestService service = new TestService();
        LingContainer container = buildContainer(service);
        LingInstance instance = buildReadyInstance(container);

        AtomicInteger attempts = new AtomicInteger(0);
        LingServiceInvoker timeoutInvoker = (inv, bean, method, args) -> {
            attempts.incrementAndGet();
            throw new LingInvocationException("demo-ling:" + TestService.class.getName(),
                    LingInvocationException.ErrorKind.TIMEOUT);
        };

        TerminalInvokerFilter filter = new TerminalInvokerFilter(new InvokableMethodCache(), timeoutInvoker);
        // retryCount=5 也必须守住 TIMEOUT 不重试，直接回退
        InvocationContext context = buildInvocationContext(instance, 5, "timeout-fallback");

        try {
            Object result = filter.doFilter(context, null);
            assertEquals("timeout-fallback", result);
            assertEquals(1, attempts.get());
        } finally {
            context.recycle();
        }
    }

    @Test
    @DisplayName("无 fallback 时 TIMEOUT 应原样抛出不吞没")
    void shouldPropagateTimeoutWithoutFallback() throws Throwable {
        TestService service = new TestService();
        LingContainer container = buildContainer(service);
        LingInstance instance = buildReadyInstance(container);

        LingServiceInvoker timeoutInvoker = (inv, bean, method, args) -> {
            throw new LingInvocationException("demo-ling:" + TestService.class.getName(),
                    LingInvocationException.ErrorKind.TIMEOUT);
        };

        TerminalInvokerFilter filter = new TerminalInvokerFilter(new InvokableMethodCache(), timeoutInvoker);
        InvocationContext context = buildInvocationContext(instance, 3, null);

        try {
            LingInvocationException failure = assertThrows(LingInvocationException.class,
                    () -> filter.doFilter(context, null));
            assertEquals(LingInvocationException.ErrorKind.TIMEOUT, failure.getKind());
        } finally {
            context.recycle();
        }
    }

    @Test
    @DisplayName("fallback 字符串应按返回类型转换为 int")
    void shouldConvertFallbackToIntReturnType() throws Throwable {
        IntService service = new IntService();
        LingContainer container = mock(LingContainer.class);
        when(container.getClassLoader()).thenReturn(IntService.class.getClassLoader());
        when(container.getBean(IntService.class)).thenReturn(service);
        LingInstance instance = buildReadyInstance(container);

        LingServiceInvoker failingInvoker = (inv, bean, method, args) -> {
            throw new IllegalStateException("boom");
        };
        TerminalInvokerFilter filter = new TerminalInvokerFilter(new InvokableMethodCache(), failingInvoker);
        InvocationContext context = InvocationContext.obtain();
        context.setServiceFQSID("demo-ling:" + IntService.class.getName());
        context.setMethodName("count");
        context.setArgs(new Object[0]);
        context.routing().setTargetInstance(instance);
        context.resolution().setTargetClassName(IntService.class.getName());
        context.resolution().setResolvedParameterTypes(new Class<?>[0]);
        context.governance().setRetryCount(0);
        context.governance().setFallbackValue("42");

        try {
            Object result = filter.doFilter(context, null);
            assertEquals(42, result);
            assertEquals(Integer.class, result.getClass());
        } finally {
            context.recycle();
        }
    }

    @Test
    @DisplayName("fallback 无法转换为目标返回类型时应拒绝而非返回 String")
    void shouldRejectUnconvertibleFallback() throws Throwable {
        IntService service = new IntService();
        LingContainer container = mock(LingContainer.class);
        when(container.getClassLoader()).thenReturn(IntService.class.getClassLoader());
        when(container.getBean(IntService.class)).thenReturn(service);
        LingInstance instance = buildReadyInstance(container);

        LingServiceInvoker failingInvoker = (inv, bean, method, args) -> {
            throw new IllegalStateException("boom");
        };
        TerminalInvokerFilter filter = new TerminalInvokerFilter(new InvokableMethodCache(), failingInvoker);
        InvocationContext context = InvocationContext.obtain();
        context.setServiceFQSID("demo-ling:" + IntService.class.getName());
        context.setMethodName("count");
        context.setArgs(new Object[0]);
        context.routing().setTargetInstance(instance);
        context.resolution().setTargetClassName(IntService.class.getName());
        context.resolution().setResolvedParameterTypes(new Class<?>[0]);
        context.governance().setRetryCount(0);
        context.governance().setFallbackValue("not-a-number");

        try {
            assertThrows(LingInvocationException.class, () -> filter.doFilter(context, null));
        } finally {
            context.recycle();
        }
    }

    @Test
    @DisplayName("boolean fallback 合法值应转换为 Boolean")
    void shouldConvertFallbackToBooleanReturnType() throws Throwable {
        BoolService service = new BoolService();
        LingContainer container = mock(LingContainer.class);
        when(container.getClassLoader()).thenReturn(BoolService.class.getClassLoader());
        when(container.getBean(BoolService.class)).thenReturn(service);
        LingInstance instance = buildReadyInstance(container);

        LingServiceInvoker failingInvoker = (inv, bean, method, args) -> {
            throw new IllegalStateException("boom");
        };
        TerminalInvokerFilter filter = new TerminalInvokerFilter(new InvokableMethodCache(), failingInvoker);
        InvocationContext context = InvocationContext.obtain();
        context.setServiceFQSID("demo-ling:" + BoolService.class.getName());
        context.setMethodName("flag");
        context.setArgs(new Object[0]);
        context.routing().setTargetInstance(instance);
        context.resolution().setTargetClassName(BoolService.class.getName());
        context.resolution().setResolvedParameterTypes(new Class<?>[0]);
        context.governance().setRetryCount(0);
        context.governance().setFallbackValue("true");

        try {
            Object result = filter.doFilter(context, null);
            assertEquals(Boolean.TRUE, result);
        } finally {
            context.recycle();
        }
    }

    @Test
    @DisplayName("boolean fallback 非 true/false 值应拒绝而非静默降级为 false")
    void shouldRejectInvalidBooleanFallback() throws Throwable {
        BoolService service = new BoolService();
        LingContainer container = mock(LingContainer.class);
        when(container.getClassLoader()).thenReturn(BoolService.class.getClassLoader());
        when(container.getBean(BoolService.class)).thenReturn(service);
        LingInstance instance = buildReadyInstance(container);

        LingServiceInvoker failingInvoker = (inv, bean, method, args) -> {
            throw new IllegalStateException("boom");
        };
        TerminalInvokerFilter filter = new TerminalInvokerFilter(new InvokableMethodCache(), failingInvoker);
        InvocationContext context = InvocationContext.obtain();
        context.setServiceFQSID("demo-ling:" + BoolService.class.getName());
        context.setMethodName("flag");
        context.setArgs(new Object[0]);
        context.routing().setTargetInstance(instance);
        context.resolution().setTargetClassName(BoolService.class.getName());
        context.resolution().setResolvedParameterTypes(new Class<?>[0]);
        context.governance().setRetryCount(0);
        // 拼错的布尔值：parseBoolean 会静默返回 false，必须显式拒绝
        context.governance().setFallbackValue("ture");

        try {
            assertThrows(LingInvocationException.class, () -> filter.doFilter(context, null));
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

    private static class FlakyInvoker implements LingServiceInvoker {
        private final AtomicInteger attempts;
        public FlakyInvoker(AtomicInteger attempts) {
            this.attempts = attempts;
        }
        @Override
        public Object invoke(LingInstance instance, Object bean, Method method, Object[] args) throws Exception {
            if (attempts.getAndIncrement() < 2) {
                throw new IllegalStateException("transient");
            }
            return method.invoke(bean, args);
        }
    }

    private static class FailingInvoker implements LingServiceInvoker {
        private final AtomicInteger attempts;
        public FailingInvoker(AtomicInteger attempts) {
            this.attempts = attempts;
        }
        @Override
        public Object invoke(LingInstance instance, Object bean, Method method, Object[] args) throws Exception {
            attempts.incrementAndGet();
            throw new IllegalStateException("still failing");
        }
    }

    public static class TestService {
        public String ping() {
            return "pong";
        }
    }

    public static class IntService {
        public int count() {
            return 1;
        }
    }

    public static class BoolService {
        public boolean flag() {
            return false;
        }
    }
}
