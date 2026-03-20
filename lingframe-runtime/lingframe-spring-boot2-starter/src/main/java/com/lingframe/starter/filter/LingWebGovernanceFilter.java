package com.lingframe.starter.filter;

import com.lingframe.api.annotation.Auditable;
import com.lingframe.api.annotation.RequiresPermission;
import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.api.security.AccessType;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationExecutionMode;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.strategy.GovernanceStrategy;
import com.lingframe.starter.config.LingFrameProperties;
import com.lingframe.starter.web.WebInterfaceManager;
import com.lingframe.starter.web.WebInterfaceMetadata;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashMap;

@Slf4j
@RequiredArgsConstructor
public class LingWebGovernanceFilter extends OncePerRequestFilter {

    private final WebInterfaceManager webInterfaceManager;
    private final InvocationPipelineEngine pipelineEngine;
    private final LingFrameProperties properties;
    private final RequestMappingHandlerMapping requestMappingHandlerMapping;

    private static final String LING_CORE_ID = "lingcore-app";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        HandlerMethod handlerMethod = resolveHandlerMethod(request);
        if (handlerMethod == null) {
            filterChain.doFilter(request, response);
            return;
        }

        WebInterfaceMetadata lingMeta = webInterfaceManager.getMetadata(request, handlerMethod);
        boolean isLingRequest = (lingMeta != null);

        if (!isLingRequest && !properties.getLingCoreGovernance().isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String lingId = isLingRequest ? lingMeta.getLingId() : LING_CORE_ID;

        ClassLoader originalCL = null;
        if (isLingRequest) {
            originalCL = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(lingMeta.getClassLoader());
        }

        LingCallContext.setLingId(lingId);

        InvocationContext ctx = null;
        try {
            Method method = handlerMethod.getMethod();
            ctx = buildInvocationContext(request, method, lingId, lingMeta);

            // 【关键】GOVERN_ONLY 表示 Web 层只借道治理，不由 Pipeline 代替 Spring MVC 执行 Controller
            ctx.setExecutionMode(InvocationExecutionMode.GOVERN_ONLY);

            // 借道 Pipeline 执行全套治理
            try {
                pipelineEngine.invoke(ctx);
                LingInstance routed = ctx.routing().getTargetInstance();
                if (routed != null) {
                    request.setAttribute(WebInterfaceManager.REQUEST_TARGET_VERSION_KEY,
                            routed.getVersion());
                }
            } catch (LingInvocationException e) {
                // 治理拒绝：由管道层统一抛出的受控异常。针对卸载/停机期间降级为 info 避免日志风暴
                if (e.getKind() == LingInvocationException.ErrorKind.SECURITY_REJECTED) {
                    log.warn("[Governance] Security rejected (SB2): {} -> {}", ctx.getResourceId(), e.getMessage());
                } else {
                    log.info("[Governance] Request blocked (SB2): {} -> {}", ctx.getResourceId(), e.getMessage());
                }
                handleGovernanceFailure(response, e, ctx);
                return;
            }

            filterChain.doFilter(request, response);

        } finally {
            if (ctx != null) {
                ctx.recycle();
            }
            if (originalCL != null) {
                Thread.currentThread().setContextClassLoader(originalCL);
            }
            LingCallContext.clear();
        }
    }

    private void handleGovernanceFailure(HttpServletResponse response,
            LingInvocationException e,
            InvocationContext ctx) throws IOException {
        if (e.getKind() == LingInvocationException.ErrorKind.SECURITY_REJECTED) {
            response.sendError(403, "Permission Denied: " + ctx.getRequiredPermission());
        } else if (e.getKind() == LingInvocationException.ErrorKind.STATE_REJECTED ||
                e.getKind() == LingInvocationException.ErrorKind.ROUTE_FAILURE) {
            response.sendError(503, e.getMessage());
        } else {
            response.sendError(500, "Governance Error: " + e.getKind());
        }
    }

    private HandlerMethod resolveHandlerMethod(HttpServletRequest request) {
        try {
            HandlerExecutionChain chain = requestMappingHandlerMapping.getHandler(request);
            if (chain != null && chain.getHandler() instanceof HandlerMethod) {
                return (HandlerMethod) chain.getHandler();
            }
        } catch (Exception e) {
            log.debug("Failed to resolve handler for {}: {}", request.getRequestURI(), e.getMessage());
        }
        return null;
    }

    private InvocationContext buildInvocationContext(HttpServletRequest request, Method method,
            String lingId, WebInterfaceMetadata meta) {
        String permission;
        RequiresPermission permAnn = AnnotatedElementUtils.findMergedAnnotation(method, RequiresPermission.class);
        if (permAnn != null) {
            permission = permAnn.value();
        } else if (meta != null && meta.getRequiredPermission() != null) {
            permission = meta.getRequiredPermission();
        } else {
            permission = GovernanceStrategy.inferPermission(method);
        }

        boolean shouldAudit = false;
        String auditAction = request.getMethod() + " " + request.getRequestURI();
        Auditable auditAnn = AnnotatedElementUtils.findMergedAnnotation(method, Auditable.class);
        if (auditAnn != null) {
            shouldAudit = true;
            auditAction = auditAnn.action();
        } else if (meta != null) {
            shouldAudit = meta.isShouldAudit();
            if (meta.getAuditAction() != null) {
                auditAction = meta.getAuditAction();
            }
        } else if (!"GET".equals(request.getMethod())) {
            shouldAudit = true;
        }

        AccessType accessType;
        switch (request.getMethod()) {
            case "GET":
            case "HEAD":
            case "OPTIONS":
                accessType = AccessType.READ;
                break;
            case "POST":
            case "PUT":
            case "PATCH":
            case "DELETE":
                accessType = AccessType.WRITE;
                break;
            default:
                accessType = AccessType.EXECUTE;
                break;
        }

        InvocationContext ctx = InvocationContext.obtain();
        String traceId = request.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isEmpty()) {
            traceId = LingCallContext.startTrace();
        } else {
            LingCallContext.setTraceId(traceId);
        }
        ctx.setTraceId(traceId);
        ctx.setTargetLingId(lingId); // 这里写目标灵元标识，而不是旧字段语义上的 lingId
        ctx.setServiceFQSID(lingId + ":http");
        ctx.setCallerLingId("http-gateway");
        ctx.setResourceType("HTTP");
        ctx.setResourceId(request.getMethod() + " " + request.getRequestURI());
        ctx.setOperation(method.getName());
        ctx.setMethodName(method.getName());
        ctx.setParameterTypeNames(resolveParameterTypeNames(method));
        // Web 入口已经知道 HandlerMethod，对应解析信息直接写入 resolution 分区
        ctx.resolution().setTargetClassName(method.getDeclaringClass().getName());
        ctx.resolution().setResolvedParameterTypes(method.getParameterTypes());
        ctx.resolution().setTargetClassLoader(method.getDeclaringClass().getClassLoader());
        ctx.setRequiredPermission(permission);
        ctx.setAccessType(accessType);
        ctx.setAuditAction(auditAction);
        ctx.setShouldAudit(shouldAudit);
        ctx.setMetadata(new HashMap<>());
        ctx.setLabels(new HashMap<>());
        ctx.setRuleSource(null); // 这里尚未进入规则仲裁阶段，因此显式置空
        return ctx;
    }

    private String[] resolveParameterTypeNames(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes == null || parameterTypes.length == 0) {
            return new String[0];
        }
        String[] names = new String[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            names[i] = parameterTypes[i].getName();
        }
        return names;
    }
}
