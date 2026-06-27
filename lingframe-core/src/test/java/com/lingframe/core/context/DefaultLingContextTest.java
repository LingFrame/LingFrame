package com.lingframe.core.context;

import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("DefaultLingContext 测试")
class DefaultLingContextTest {

    private DefaultLingContext context;
    private LingRepository lingRepository;
    private LingServiceRegistry registry;
    private InvocationPipelineEngine pipelineEngine;
    private EventBus eventBus;
    private PermissionService permissionService;

    @BeforeEach
    void setUp() {
        lingRepository = mock(LingRepository.class);
        registry = mock(LingServiceRegistry.class);
        pipelineEngine = mock(InvocationPipelineEngine.class);
        eventBus = mock(EventBus.class);
        permissionService = mock(PermissionService.class);

        context = new DefaultLingContext("test-ling", lingRepository, registry, pipelineEngine, permissionService, eventBus);
    }

    @Test
    @DisplayName("getLingId 返回正确的 ID")
    void shouldReturnLingId() {
        assertEquals("test-ling", context.getLingId());
    }

    @Test
    @DisplayName("invoke 空服务ID应抛出异常")
    void shouldThrowOnNullInvokeServiceId() {
        assertThrows(InvalidArgumentException.class, () -> context.invoke(null));
        assertThrows(InvalidArgumentException.class, () -> context.invoke(""));
    }

    @Test
    @DisplayName("invoke 注册表中无对应服务返回空")
    void shouldReturnEmptyWhenServiceNotRegistered() {
        when(registry.getProviderMethods("ling-a:service.echo")).thenReturn(null);

        assertFalse(context.invoke("ling-a:service.echo").isPresent());
    }

    @Test
    @DisplayName("invoke 无提供方法返回空")
    void shouldReturnEmptyWhenNoProviderMethods() {
        when(registry.getProviderMethods("ling-a:service.echo")).thenReturn(Collections.emptyList());

        assertFalse(context.invoke("ling-a:service.echo").isPresent());
    }

    @Test
    @DisplayName("invokeOrDefault 返回默认值当调用失败")
    void shouldReturnDefaultWhenInvokeFails() {
        when(registry.getProviderMethods("ling-a:service.echo")).thenReturn(null);

        String result = context.invokeOrDefault("ling-a:service.echo", "fallback");
        assertEquals("fallback", result);
    }

    @Test
    @DisplayName("invokeOrElse 执行 fallback 当调用失败")
    void shouldExecuteFallbackWhenInvokeFails() {
        when(registry.getProviderMethods("ling-a:service.echo")).thenReturn(null);

        String result = context.invokeOrElse("ling-a:service.echo", () -> "fallback-supplier");
        assertEquals("fallback-supplier", result);
    }

    @Test
    @DisplayName("getPermissionService 返回正确的服务")
    void shouldReturnPermissionService() {
        assertSame(permissionService, context.getPermissionService());
    }

    @Test
    @DisplayName("publishEvent 发布事件到 EventBus")
    void shouldPublishEvent() {
        com.lingframe.api.event.LingEvent event = mock(com.lingframe.api.event.LingEvent.class);
        context.publishEvent(event);
        verify(eventBus).publish(event);
    }

    @Test
    @DisplayName("registerProtocolService 注册服务元数据")
    void shouldRegisterProtocolService() throws Exception {
        Method method = TestService.class.getMethod("echo", String.class);

        context.registerProtocolService("ling-a:service.echo", new TestService(), method);

        verify(registry).registerServiceMetadata(
                eq("ling-a:service.echo"),
                eq("echo"),
                any(String[].class),
                eq("java.lang.String"));
    }

    @Test
    @DisplayName("getService 返回代理对象")
    void shouldReturnServiceProxy() {
        Runnable proxy = context.getService(Runnable.class).orElse(null);
        assertNotNull(proxy);
    }

    public static class TestService {
        public String echo(String msg) {
            return msg;
        }
    }
}
