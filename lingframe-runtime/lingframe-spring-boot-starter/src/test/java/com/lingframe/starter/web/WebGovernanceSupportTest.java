package com.lingframe.starter.web;

import com.lingframe.api.annotation.Auditable;
import com.lingframe.api.annotation.RequiresPermission;
import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.AuditMetadataKeys;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.starter.governance.EntryInvocationGovernanceResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.security.Principal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("WebGovernanceSupport 测试")
class WebGovernanceSupportTest {

    @AfterEach
    void tearDown() {
        LingCallContext.clear();
        InvocationContext.detach(null);
    }

    public static class TestController {
        @RequiresPermission("test:perm")
        @Auditable(action = "test:audit")
        public void governedMethod() {}

        @RequiresPermission("test:perm2")
        public void governedMethodNoAudit() {}

        public void methodWithoutAnnotation() {}
    }

    @Test
    @DisplayName("resolveGovernedMethod 分支测试")
    void testResolveGovernedMethod() throws Exception {
        WebGovernanceSupport support = new WebGovernanceSupport();
        Method expectedMethod = TestController.class.getMethod("governedMethod");
        HandlerMethod handlerMethod = mock(HandlerMethod.class);
        when(handlerMethod.getMethod()).thenReturn(expectedMethod);

        // 场景 1：非 ling 请求，直接返回 handlerMethod 的方法
        assertEquals(expectedMethod, support.resolveGovernedMethod(false, null, handlerMethod, "ling-id"));

        // 场景 2：ling 请求，lingMeta 包含 targetMethod
        WebInterfaceMetadata meta = WebInterfaceMetadata.builder().targetMethod(expectedMethod).build();
        assertEquals(expectedMethod, support.resolveGovernedMethod(true, meta, handlerMethod, "ling-id"));

        // 场景 3：ling 请求，但无法解析出 targetMethod，抛出 LingInvocationException
        assertThrows(LingInvocationException.class, () -> 
            support.resolveGovernedMethod(true, null, handlerMethod, "ling-id")
        );
    }

    @Test
    @DisplayName("preResolveLingTarget 分支测试")
    void testPreResolveLingTarget() {
        WebGovernanceSupport support = new WebGovernanceSupport();
        InvocationContext ctx = InvocationContext.obtain();
        ctx.setServiceFQSID("service-fqsid");

        WebRouteResolution route = mock(WebRouteResolution.class);
        LingRuntime runtime = mock(LingRuntime.class);
        LingInstance instance = mock(LingInstance.class);
        when(route.getRuntime()).thenReturn(runtime);
        when(route.getTargetInstance()).thenReturn(instance);
        when(instance.getVersion()).thenReturn("1.0.0");

        // 场景 1：正常预解析
        support.preResolveLingTarget(ctx, route);
        assertEquals(runtime, ctx.getRuntime());
        assertEquals(instance, ctx.routing().getTargetInstance());
        assertTrue(ctx.routing().isPreResolved());
        assertEquals("1.0.0", ctx.getTargetVersion());

        // 场景 2：无可用目标实例，抛出 LingInvocationException
        InvocationContext ctx2 = InvocationContext.obtain();
        ctx2.setServiceFQSID("service-fqsid");
        WebRouteResolution badRoute = mock(WebRouteResolution.class);
        WebInterfaceMetadata meta = WebInterfaceMetadata.builder().lingId("ling").version("1.0").build();
        when(badRoute.getMetadata()).thenReturn(meta);
        when(badRoute.getTargetInstance()).thenReturn(null);

        assertThrows(LingInvocationException.class, () -> support.preResolveLingTarget(ctx2, badRoute));
    }

    @Test
    @DisplayName("buildInvocationContext 分支与注解解析测试")
    void testBuildInvocationContext() throws Exception {
        WebGovernanceSupport support = new WebGovernanceSupport();
        WebRequestFacade request = mock(WebRequestFacade.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/test");
        when(request.getHeader("X-Trace-Id")).thenReturn("trace-123");

        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn("admin");
        when(request.getUserPrincipal()).thenReturn(principal);

        Method method = TestController.class.getMethod("governedMethod");
        EntryInvocationGovernanceResolver resolver = mock(EntryInvocationGovernanceResolver.class);

        WebInterfaceMetadata meta = WebInterfaceMetadata.builder()
                .lingId("ling-id")
                .requiredPermission("meta:perm")
                .shouldAudit(true)
                .auditAction("meta:audit")
                .build();

        // 场景 1：带有注解且有 trace id 的请求
        InvocationContext ctx = support.buildInvocationContext(request, method, "ling-id", meta, resolver);

        assertEquals("trace-123", ctx.getTraceId());
        assertEquals("ling-id", ctx.getTargetLingId());
        assertEquals("ling-id:http", ctx.getServiceFQSID());
        assertEquals("HTTP", ctx.getResourceType());
        assertEquals("POST /api/test", ctx.getResourceId());
        assertEquals("test:perm", ctx.governance().getRequiredPermission()); // 注解优先于 meta
        assertEquals(AccessType.WRITE, ctx.governance().getAccessType());    // POST 映射为 WRITE
        assertEquals("test:audit", ctx.governance().getAuditAction());        // 注解优先于 meta
        assertTrue(ctx.governance().isShouldAudit());
        assertEquals("admin", ctx.getMetadata().get(AuditMetadataKeys.PRINCIPAL));

        verify(resolver).applyTo(ctx, "ling-id");

        // 场景 2：GET 请求且无 Trace ID 的环境
        WebRequestFacade getRequest = mock(WebRequestFacade.class);
        when(getRequest.getMethod()).thenReturn("GET");
        when(getRequest.getRequestURI()).thenReturn("/api/test");
        when(getRequest.getRemoteUser()).thenReturn("guest");

        Method methodNoAudit = TestController.class.getMethod("governedMethodNoAudit");
        InvocationContext ctxGet = support.buildInvocationContext(getRequest, methodNoAudit, "ling-id", null, null);

        assertNotNull(ctxGet.getTraceId()); // 自动生成 traceId
        assertEquals(AccessType.READ, ctxGet.governance().getAccessType()); // GET 映射为 READ
        assertFalse(ctxGet.governance().isShouldAudit());                  // GET 默认不审计
        assertEquals("guest", ctxGet.getMetadata().get(AuditMetadataKeys.PRINCIPAL));

        // 场景 3：无注解的方法，从 meta 解析或默认行为
        Method defaultMethod = TestController.class.getMethod("methodWithoutAnnotation");
        InvocationContext ctxDefault = support.buildInvocationContext(getRequest, defaultMethod, "ling-id", meta, null);
        assertEquals("meta:perm", ctxDefault.governance().getRequiredPermission());
        assertEquals("meta:audit", ctxDefault.governance().getAuditAction());
    }

    @Test
    @DisplayName("resolveGovernanceResourceId 测试")
    void testResolveGovernanceResourceId() {
        WebGovernanceSupport support = new WebGovernanceSupport();
        WebRequestFacade request = mock(WebRequestFacade.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/res");

        // 场景 1：ctx 不为 null 且有 resourceId
        InvocationContext ctx = InvocationContext.obtain();
        ctx.setResourceId("custom-resource");
        assertEquals("custom-resource", support.resolveGovernanceResourceId(ctx, request));

        // 场景 2：ctx 为 null
        assertEquals("GET /api/res", support.resolveGovernanceResourceId(null, request));
    }
}
