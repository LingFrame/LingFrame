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
    @DisplayName("buildInvocationContext：灵元路径以 metadata 为真源，忽略 Method 上注解差异")
    void testBuildInvocationContext_lingPathPrefersMetadata() throws Exception {
        WebGovernanceSupport support = new WebGovernanceSupport();
        WebRequestFacade request = mock(WebRequestFacade.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/test");
        when(request.getHeader("X-Trace-Id")).thenReturn("trace-123");

        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn("admin");
        when(request.getUserPrincipal()).thenReturn(principal);

        // Method 上注解为 test:perm / test:audit，与 meta 故意不一致
        Method method = TestController.class.getMethod("governedMethod");
        EntryInvocationGovernanceResolver resolver = mock(EntryInvocationGovernanceResolver.class);

        WebInterfaceMetadata meta = WebInterfaceMetadata.builder()
                .lingId("ling-id")
                .requiredPermission("meta:perm")
                .shouldAudit(true)
                .auditAction("meta:audit")
                .build();

        InvocationContext ctx = support.buildInvocationContext(request, method, "ling-id", meta, resolver);

        assertEquals("trace-123", ctx.getTraceId());
        assertEquals("ling-id", ctx.getTargetLingId());
        assertEquals("ling-id:http", ctx.getServiceFQSID());
        assertEquals("HTTP", ctx.getResourceType());
        assertEquals("POST /api/test", ctx.getResourceId());
        // 灵元路径：metadata 优先，不得再被 Method 注解覆盖
        assertEquals("meta:perm", ctx.governance().getRequiredPermission());
        assertEquals(AccessType.WRITE, ctx.governance().getAccessType());
        assertEquals("meta:audit", ctx.governance().getAuditAction());
        assertTrue(ctx.governance().isShouldAudit());
        assertEquals("admin", ctx.getMetadata().get(AuditMetadataKeys.PRINCIPAL));

        verify(resolver).applyTo(ctx, "ling-id");
    }

    @Test
    @DisplayName("buildInvocationContext：灵核路径 meta 为空时仍可读 Method 注解")
    void testBuildInvocationContext_corePathReadsAnnotations() throws Exception {
        WebGovernanceSupport support = new WebGovernanceSupport();
        WebRequestFacade getRequest = mock(WebRequestFacade.class);
        when(getRequest.getMethod()).thenReturn("GET");
        when(getRequest.getRequestURI()).thenReturn("/api/test");
        when(getRequest.getRemoteUser()).thenReturn("guest");

        Method methodNoAudit = TestController.class.getMethod("governedMethodNoAudit");
        InvocationContext ctxGet = support.buildInvocationContext(getRequest, methodNoAudit, "ling-core", null, null);

        assertNotNull(ctxGet.getTraceId());
        assertEquals(AccessType.READ, ctxGet.governance().getAccessType());
        assertEquals("test:perm2", ctxGet.governance().getRequiredPermission());
        assertFalse(ctxGet.governance().isShouldAudit());
        assertEquals("guest", ctxGet.getMetadata().get(AuditMetadataKeys.PRINCIPAL));
    }

    @Test
    @DisplayName("buildInvocationContext：灵元 metadata 缺权限字段时按方法名推断，不读注解")
    void testBuildInvocationContext_lingPathInfersWhenMetadataPermissionMissing() throws Exception {
        WebGovernanceSupport support = new WebGovernanceSupport();
        WebRequestFacade getRequest = mock(WebRequestFacade.class);
        when(getRequest.getMethod()).thenReturn("GET");
        when(getRequest.getRequestURI()).thenReturn("/api/test");

        Method annotatedMethod = TestController.class.getMethod("governedMethod");
        // shouldAudit=false 已在注册时固化；permission 为空时推断，不得回落到注解 test:perm
        WebInterfaceMetadata meta = WebInterfaceMetadata.builder()
                .lingId("ling-id")
                .shouldAudit(false)
                .auditAction("GET /api/test")
                .build();

        InvocationContext ctx = support.buildInvocationContext(getRequest, annotatedMethod, "ling-id", meta, null);
        assertEquals("TestController:EXECUTE", ctx.governance().getRequiredPermission());
        assertFalse(ctx.governance().isShouldAudit());
        assertEquals("GET /api/test", ctx.governance().getAuditAction());
        assertNotEquals("test:perm", ctx.governance().getRequiredPermission());
    }

    @Test
    @DisplayName("buildInvocationContext：灵元 metadata 完整时即使 Method 无注解也使用 meta")
    void testBuildInvocationContext_lingPathUsesMetaWithoutMethodAnnotations() throws Exception {
        WebGovernanceSupport support = new WebGovernanceSupport();
        WebRequestFacade getRequest = mock(WebRequestFacade.class);
        when(getRequest.getMethod()).thenReturn("GET");
        when(getRequest.getRequestURI()).thenReturn("/api/test");

        Method defaultMethod = TestController.class.getMethod("methodWithoutAnnotation");
        WebInterfaceMetadata meta = WebInterfaceMetadata.builder()
                .lingId("ling-id")
                .requiredPermission("meta:perm")
                .shouldAudit(true)
                .auditAction("meta:audit")
                .build();
        InvocationContext ctxDefault = support.buildInvocationContext(getRequest, defaultMethod, "ling-id", meta, null);
        assertEquals("meta:perm", ctxDefault.governance().getRequiredPermission());
        assertEquals("meta:audit", ctxDefault.governance().getAuditAction());
        assertTrue(ctxDefault.governance().isShouldAudit());
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
