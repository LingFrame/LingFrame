package com.lingframe.starter.processor;

import com.lingframe.api.annotation.LingReference;
import com.lingframe.api.context.LingContext;
import com.lingframe.api.exception.LingInvocationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Proxy;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link LingReferenceInjector} 补充测试。
 * <p>
 * 重点覆盖 @LingReference 字段注入流程：LingContext 未就绪跳过、
 * 正常注入、字段已有值跳过、fallback 代理创建与降级触发。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LingReferenceInjector 补充测试")
class LingReferenceInjectorSupplementTest {

    /** 测试用服务接口 */
    interface TestService {
        String hello(String name);
    }

    /** 测试用 fallback 实现 */
    public static class FallbackService implements TestService {
        @Override
        public String hello(String name) {
            return "fallback:" + name;
        }
    }

    /** 携带 @LingReference 字段的测试 Bean */
    static class TargetBean {
        @LingReference
        TestService service;

        @LingReference(fallback = FallbackService.class)
        TestService serviceWithFallback;
    }

    @Mock
    private LingContext lingContext;
    @Mock
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("LingContext 未就绪时 postProcessBeforeInitialization 应跳过注入")
    void shouldSkipWhenLingContextNotReady() {
        // 未调用 setApplicationContext，lingContext 为 null
        LingReferenceInjector injector = new LingReferenceInjector("ling-a");

        TargetBean bean = new TargetBean();

        Object result = injector.postProcessBeforeInitialization(bean, "targetBean");

        assertSame(bean, result);
        assertNull(bean.service);
    }

    @Test
    @DisplayName("postProcessBeforeInitialization 应注入 @LingReference 字段")
    void shouldInjectLingReferenceField() {
        TestService mockService = org.mockito.Mockito.mock(TestService.class);
        when(lingContext.getService(TestService.class)).thenReturn(Optional.of(mockService));

        LingReferenceInjector injector = new LingReferenceInjector("ling-a", lingContext);

        TargetBean bean = new TargetBean();
        injector.postProcessBeforeInitialization(bean, "targetBean");

        // 两个 @LingReference 字段都应被注入
        assertNotNull(bean.service);
        assertNotNull(bean.serviceWithFallback);
        // service 无 fallback，直接注入原始代理
        assertSame(mockService, bean.service);
    }

    @Test
    @DisplayName("字段已有值时应跳过注入")
    void shouldSkipWhenFieldAlreadyInjected() {
        TestService existing = org.mockito.Mockito.mock(TestService.class);
        when(lingContext.getService(TestService.class)).thenReturn(Optional.of(existing));

        LingReferenceInjector injector = new LingReferenceInjector("ling-a", lingContext);

        TargetBean bean = new TargetBean();
        // 预先设置字段值
        TestService preSet = org.mockito.Mockito.mock(TestService.class);
        bean.service = preSet;

        injector.postProcessBeforeInitialization(bean, "targetBean");

        // service 字段应保持预先设置的值
        assertSame(preSet, bean.service);
        // serviceWithFallback 仍应被注入（因为它原本为 null）
        assertNotNull(bean.serviceWithFallback);
    }

    @Test
    @DisplayName("@LingReference 带 fallback 时应创建 JDK 代理包装")
    void shouldCreateFallbackProxyWhenFallbackSpecified() {
        TestService mockService = org.mockito.Mockito.mock(TestService.class);
        when(lingContext.getService(TestService.class)).thenReturn(Optional.of(mockService));

        LingReferenceInjector injector = new LingReferenceInjector("ling-a", lingContext);
        injector.setApplicationContext(applicationContext);

        TargetBean bean = new TargetBean();
        injector.postProcessBeforeInitialization(bean, "targetBean");

        // serviceWithFallback 应被注入代理对象（非原始 mockService）
        assertNotNull(bean.serviceWithFallback);
        assertTrue(Proxy.isProxyClass(bean.serviceWithFallback.getClass()));
        assertNotSame(mockService, bean.serviceWithFallback);
    }

    @Test
    @DisplayName("fallback 代理在底层抛 LingInvocationException 时应降级到 fallback bean")
    void shouldFallbackWhenLingInvocationExceptionThrown() {
        // 准备原始 service mock，调用 hello 时抛 LingInvocationException
        TestService mockService = org.mockito.Mockito.mock(TestService.class);
        when(mockService.hello("world")).thenThrow(
                new LingInvocationException("ling-a:TestService",
                        LingInvocationException.ErrorKind.CIRCUIT_OPEN));
        when(lingContext.getService(TestService.class)).thenReturn(Optional.of(mockService));

        // 准备 fallback bean
        FallbackService fallback = new FallbackService();
        when(applicationContext.getBean(FallbackService.class)).thenReturn(fallback);

        LingReferenceInjector injector = new LingReferenceInjector("ling-a", lingContext);
        injector.setApplicationContext(applicationContext);

        TargetBean bean = new TargetBean();
        injector.postProcessBeforeInitialization(bean, "targetBean");

        // 通过代理调用 hello，底层抛异常应触发 fallback
        String result = bean.serviceWithFallback.hello("world");
        assertEquals("fallback:world", result);
    }

    @Test
    @DisplayName("postProcessAfterInitialization 应原样返回 bean")
    void shouldReturnBeanUnchangedInAfterInit() {
        LingReferenceInjector injector = new LingReferenceInjector("ling-a", lingContext);

        Object bean = new Object();
        Object result = injector.postProcessAfterInitialization(bean, "testBean");

        assertSame(bean, result);
    }
}
