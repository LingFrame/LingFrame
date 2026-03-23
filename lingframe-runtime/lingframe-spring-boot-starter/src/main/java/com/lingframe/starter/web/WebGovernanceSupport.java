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
import com.lingframe.core.strategy.GovernanceStrategy;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.security.Principal;
import java.util.HashMap;

/**
 * 供 Boot 2 / Boot 3 Servlet Filter 共享的治理辅助工具。
 */
public final class WebGovernanceSupport {

    public Method resolveGovernedMethod(boolean isLingRequest,
                                        WebInterfaceMetadata lingMeta,
                                        HandlerMethod handlerMethod,
                                        String lingId) {
        if (!isLingRequest) {
            return handlerMethod.getMethod();
        }
        Method method = lingMeta != null ? lingMeta.getTargetMethod() : null;
        if (method != null) {
            return method;
        }
        throw new LingInvocationException(lingId + ":http",
                LingInvocationException.ErrorKind.ROUTE_FAILURE,
                "Ling route metadata no longer resolves target method: " + describeLingRoute(lingMeta));
    }

    public void preResolveLingTarget(InvocationContext ctx, WebRouteResolution route) {
        WebInterfaceMetadata meta = route.getMetadata();
        LingRuntime runtime = route.getRuntime();
        if (runtime != null) {
            ctx.setRuntime(runtime);
        }

        LingInstance target = route.getTargetInstance();
        if (target == null) {
            throw new LingInvocationException(ctx.getServiceFQSID(),
                    LingInvocationException.ErrorKind.ROUTE_FAILURE,
                    "No ready target instance for route metadata: " + meta.getLingId() + "@" + meta.getVersion());
        }

        ctx.routing().setTargetInstance(target);
        ctx.routing().setPreResolved(true);
        ctx.setTargetVersion(target.getVersion());
    }

    public InvocationContext buildInvocationContext(WebRequestFacade request,
                                                    Method method,
                                                    String lingId,
                                                    WebInterfaceMetadata meta) {
        String permission = resolvePermission(method, meta);
        boolean shouldAudit = resolveShouldAudit(request, method, meta);
        String auditAction = resolveAuditAction(request, method, meta);

        InvocationContext ctx = InvocationContext.obtain();
        String traceId = request.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isEmpty()) {
            traceId = LingCallContext.startTrace();
        } else {
            LingCallContext.setTraceId(traceId);
        }
        ctx.setTraceId(traceId);
        ctx.setTargetLingId(lingId);
        ctx.setServiceFQSID(lingId + ":http");
        ctx.setCallerLingId("http-gateway");
        ctx.setResourceType("HTTP");
        ctx.setResourceId(request.getMethod() + " " + request.getRequestURI());
        ctx.setOperation(method.getName());
        ctx.setMethodName(method.getName());
        ctx.setParameterTypeNames(resolveParameterTypeNames(method));
        ctx.resolution().setTargetClassName(method.getDeclaringClass().getName());
        ctx.resolution().setResolvedMethod(method);
        ctx.resolution().setResolvedParameterTypes(method.getParameterTypes());
        ctx.resolution().setTargetClassLoader(method.getDeclaringClass().getClassLoader());
        ctx.setRequiredPermission(permission);
        ctx.setAccessType(resolveAccessType(request.getMethod()));
        ctx.setAuditAction(auditAction);
        ctx.setShouldAudit(shouldAudit);
        ctx.setMetadata(resolveMetadata(request));
        ctx.setLabels(new HashMap<String, String>());
        ctx.setRuleSource(null);
        return ctx;
    }

    public String resolveGovernanceResourceId(InvocationContext ctx, WebRequestFacade request) {
        if (ctx != null && ctx.getResourceId() != null) {
            return ctx.getResourceId();
        }
        return request.getMethod() + " " + request.getRequestURI();
    }

    private String resolvePermission(Method method, WebInterfaceMetadata meta) {
        RequiresPermission permAnn = AnnotatedElementUtils.findMergedAnnotation(method, RequiresPermission.class);
        if (permAnn != null) {
            return permAnn.value();
        }
        if (meta != null && meta.getRequiredPermission() != null) {
            return meta.getRequiredPermission();
        }
        return GovernanceStrategy.inferPermission(method);
    }

    private boolean resolveShouldAudit(WebRequestFacade request, Method method, WebInterfaceMetadata meta) {
        Auditable auditAnn = AnnotatedElementUtils.findMergedAnnotation(method, Auditable.class);
        if (auditAnn != null) {
            return true;
        }
        if (meta != null) {
            return meta.isShouldAudit();
        }
        return !"GET".equals(request.getMethod());
    }

    private String resolveAuditAction(WebRequestFacade request, Method method, WebInterfaceMetadata meta) {
        String auditAction = request.getMethod() + " " + request.getRequestURI();
        Auditable auditAnn = AnnotatedElementUtils.findMergedAnnotation(method, Auditable.class);
        if (auditAnn != null) {
            return auditAnn.action();
        }
        if (meta != null && meta.getAuditAction() != null) {
            return meta.getAuditAction();
        }
        return auditAction;
    }

    private HashMap<String, Object> resolveMetadata(WebRequestFacade request) {
        HashMap<String, Object> metadata = new HashMap<>();
        String principal = resolvePrincipal(request);
        if (principal != null) {
            metadata.put(AuditMetadataKeys.PRINCIPAL, principal);
        }
        return metadata;
    }

    private String resolvePrincipal(WebRequestFacade request) {
        Principal principal = request.getUserPrincipal();
        if (principal != null && principal.getName() != null && !principal.getName().trim().isEmpty()) {
            return principal.getName();
        }
        String remoteUser = request.getRemoteUser();
        return remoteUser != null && !remoteUser.trim().isEmpty() ? remoteUser : null;
    }

    private AccessType resolveAccessType(String requestMethod) {
        if ("GET".equals(requestMethod) || "HEAD".equals(requestMethod) || "OPTIONS".equals(requestMethod)) {
            return AccessType.READ;
        }
        if ("POST".equals(requestMethod)
                || "PUT".equals(requestMethod)
                || "PATCH".equals(requestMethod)
                || "DELETE".equals(requestMethod)) {
            return AccessType.WRITE;
        }
        return AccessType.EXECUTE;
    }

    private String[] resolveParameterTypeNames(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length == 0) {
            return new String[0];
        }
        String[] names = new String[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            names[i] = parameterTypes[i].getName();
        }
        return names;
    }

    private String describeLingRoute(WebInterfaceMetadata lingMeta) {
        if (lingMeta == null) {
            return "unknown";
        }
        return lingMeta.getLingId() + "@"
                + lingMeta.getVersion() + " "
                + lingMeta.getHttpMethod() + " "
                + lingMeta.getUrlPattern();
    }
}
