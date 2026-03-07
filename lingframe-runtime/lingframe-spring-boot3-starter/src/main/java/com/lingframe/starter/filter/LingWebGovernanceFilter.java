package com.lingframe.starter.filter;

import com.lingframe.api.annotation.Auditable;
import com.lingframe.api.annotation.RequiresPermission;
import com.lingframe.api.context.LingContextHolder;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.strategy.GovernanceStrategy;
import com.lingframe.starter.config.LingFrameProperties;
import com.lingframe.starter.web.WebInterfaceManager;
import com.lingframe.starter.web.WebInterfaceMetadata;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.NonNull;
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

/**
 * 统一 Web 层治理过滤器
 * 处理灵核和灵元 Controller 的 HTTP 请求
 * 通过 GovernanceKernel.invoke() 统一执行权限检查、审计和追踪
 */
@Slf4j
@RequiredArgsConstructor
public class LingWebGovernanceFilter extends OncePerRequestFilter {

    private final PermissionService permissionService;
    private final WebInterfaceManager webInterfaceManager;
    private final LingFrameProperties properties;
    private final RequestMappingHandlerMapping requestMappingHandlerMapping;

    private static final String HOST_Ling_ID = "lingcore-app";

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        // 1. 尝试获取 HandlerMethod
        HandlerMethod handlerMethod = resolveHandlerMethod(request);
        if (handlerMethod == null) {
            // 非 Controller 请求，直接放行
            filterChain.doFilter(request, response);
            return;
        }

        // 2. 判断是灵元请求还是灵核请求
        WebInterfaceMetadata lingMeta = webInterfaceManager.getMetadata(handlerMethod);
        boolean isLingRequest = (lingMeta != null);

        // 灵核请求：检查是否启用灵核治理
        if (!isLingRequest && !properties.getLingCoreGovernance().isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        // 3. 确定 lingId
        String lingId = isLingRequest ? lingMeta.getLingId() : HOST_Ling_ID;

        // 4. ClassLoader 切换（仅灵元请求）
        ClassLoader originalCL = null;
        if (isLingRequest) {
            originalCL = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(lingMeta.getClassLoader());
        }

        // 5. 设置灵元上下文
        LingContextHolder.set(lingId);

        try {
            // 6. 构建治理上下文
            Method method = handlerMethod.getMethod();
            InvocationContext ctx = buildInvocationContext(request, method, lingId, lingMeta);

            // 7. 获取 LingRuntime（灵元请求时）
            if (ctx.getRequiredPermission() != null && !ctx.getRequiredPermission().isEmpty()) {
                boolean allowed = permissionService.isAllowed(
                        "http-gateway",
                        ctx.getRequiredPermission(),
                        ctx.getAccessType());
                if (!allowed) {
                    throw new PermissionDeniedException(
                            "http-gateway",
                            ctx.getRequiredPermission(),
                            ctx.getAccessType());
                }
            }

            filterChain.doFilter(request, response);

            if (ctx.isShouldAudit()) {
                permissionService.audit("http-gateway", ctx.getResourceId(), ctx.getAuditAction(), true);
            }

        } finally {
            // 恢复 ClassLoader
            if (originalCL != null) {
                Thread.currentThread().setContextClassLoader(originalCL);
            }
            // 清理灵元上下文
            LingContextHolder.clear();
        }
    }

    /**
     * 解析请求对应的 HandlerMethod
     */
    private HandlerMethod resolveHandlerMethod(HttpServletRequest request) {
        try {
            HandlerExecutionChain chain = requestMappingHandlerMapping.getHandler(request);
            if (chain != null && chain.getHandler() instanceof HandlerMethod hm) {
                return hm;
            }
        } catch (Exception e) {
            log.debug("Failed to resolve handler for {}: {}", request.getRequestURI(), e.getMessage());
        }
        return null;
    }

    /**
     * 构建治理上下文
     */
    private InvocationContext buildInvocationContext(HttpServletRequest request, Method method,
            String lingId, WebInterfaceMetadata meta) {
        // 智能权限推导
        String permission;
        RequiresPermission permAnn = AnnotatedElementUtils.findMergedAnnotation(method, RequiresPermission.class);
        if (permAnn != null) {
            permission = permAnn.value();
        } else if (meta != null && meta.getRequiredPermission() != null) {
            permission = meta.getRequiredPermission();
        } else {
            permission = GovernanceStrategy.inferPermission(method);
        }

        // 智能审计推导
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
            // 非 GET 请求默认审计
            shouldAudit = true;
        }

        // 推导访问类型
        AccessType accessType = switch (request.getMethod()) {
            case "GET", "HEAD", "OPTIONS" -> AccessType.READ;
            case "POST", "PUT", "PATCH", "DELETE" -> AccessType.WRITE;
            default -> AccessType.EXECUTE;
        };

        InvocationContext ctx = InvocationContext.obtain();
        ctx.setTraceId(request.getHeader("X-Trace-Id"));
        ctx.setTargetLingId(lingId); // Set targetLingId instead of lingId
        ctx.setCallerLingId("http-gateway"); // Web 请求来源标记
        ctx.setResourceType("HTTP");
        ctx.setResourceId(request.getMethod() + " " + request.getRequestURI());
        ctx.setOperation(method.getName());
        ctx.setRequiredPermission(permission);
        ctx.setAccessType(accessType);
        ctx.setAuditAction(auditAction);
        ctx.setShouldAudit(shouldAudit);
        ctx.setMetadata(new HashMap<>());
        ctx.setLabels(new HashMap<>());
        ctx.setRuleSource(null); // Explicitly set to null as it's not resolved here
        return ctx;
    }
}
