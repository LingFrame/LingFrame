package com.lingframe.core.context;

import com.lingframe.api.event.LingEvent;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import java.lang.reflect.Method;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.*;
import org.mockito.Mockito;
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
        LingEvent event = mock(LingEvent.class);
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

    @Test
    @DisplayName("getService 带锚点重载：无锚点退化为默认实现")
    void shouldFallbackToDefaultGetServiceWhenAnchorAbsent() {
        Runnable proxy = context.getService(Runnable.class, null, null).orElse(null);
        assertNotNull(proxy);
    }

    @Test
    @DisplayName("getService 带完整 FQSID 锚点：serviceId 含冒号时忽略 lingId")
    void getServiceWithFqsidAnchor() {
        Runnable proxy = context.getService(Runnable.class, "ignored-ling", "target-ling:java.lang.Runnable").orElse(null);
        // 代理对象应能创建；路由命中由调用时 resolveTargetLingId 处理
        assertNotNull(proxy);
    }

    @Test
    @DisplayName("getService 带灵元锚点：lingId 非空时拼 [lingId]:[接口名]")
    void getServiceWithLingIdAnchor() {
        Runnable proxy = context.getService(Runnable.class, "user-ling", null).orElse(null);
        assertNotNull(proxy);
    }

    @Test
    @DisplayName("expose 程序化暴露：注册 handler 所有 public 方法")
    void exposeRegistersHandlerMethods() {
        TestService handler = new TestService();
        context.expose("custom-svc", handler);
        // registerProtocolService 内部会调 registry.registerServiceMetadata
        // 用至少一次注册即可证明 expose 走了注册路径
        verify(registry, Mockito.atLeastOnce()).registerServiceMetadata(
                eq("test-ling:custom-svc"), eq("echo"), any(String[].class), eq("java.lang.String"));
    }

    @Test
    @DisplayName("expose 空参数不注册")
    void exposeNullArgsNoop() {
        context.expose(null, new TestService());
        context.expose("", new TestService());
        context.expose("x", null);
        verify(registry, Mockito.never()).registerServiceMetadata(
                anyString(), anyString(), any(String[].class), anyString());
    }

    @Test
    @DisplayName("getService 带锚点重载应能创建代理")
    void shouldGetServiceWithAnchor() {
        Runnable ref = context.getService(Runnable.class, "user-ling", null).orElse(null);
        assertNotNull(ref);
    }

    @Test
    @DisplayName("getService 短 ID 锚点 + 隐式接口注册交叉：proxy 应能创建")
    void shouldRouteByShortIdAnchorWithImplicitInterfaceRegistration() {
        // 场景：灵元 Bean 实现了 Runnable（隐式接口注册键 ling-a:java.lang.Runnable），
        // 但调用方用短 ID 锚点 getService(Runnable.class, "ling-a", "customRun") 路由。
        // 路由键应拼为 ling-a:customRun（短 ID 锚点），透给 GlobalServiceRoutingProxy 的
        // interfaceName 应为 customRun（短 ID 本身），而非 java.lang.Runnable（接口 FQCN）。
        // resolveTargetLingId 在 proxy.invoke() 时才调，创建 proxy 阶段不触发——
        // 此处只验 proxy 能创建，路由命中由调用时处理。
        Runnable proxy = context.getService(Runnable.class, "ling-a", "customRun").orElse(null);

        // proxy 应能创建（路由命中由调用时 resolveTargetLingId 处理）
        assertNotNull(proxy);
    }

    public static class TestService {
        public String echo(String msg) {
            return msg;
        }
    }
}
