package com.lingframe.starter.filter;

import com.lingframe.api.annotation.Auditable;
import com.lingframe.api.annotation.RequiresPermission;
import com.lingframe.api.context.PluginContextHolder;
import com.lingframe.api.security.AccessType;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.plugin.PluginManager;
import com.lingframe.core.plugin.PluginRuntime;
import com.lingframe.core.strategy.GovernanceStrategy;
import com.lingframe.starter.config.LingFrameProperties;
import com.lingframe.starter.web.WebInterfaceManager;
import com.lingframe.starter.web.WebInterfaceMetadata;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
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
 * 处理宿主和插件 Controller 的 HTTP 请求
 * 通过 GovernanceKernel.invoke() 统一执行权限检查、审计和追踪
 */
@Slf4j
@RequiredArgsConstructor
public class LingWebGovernanceFilter extends OncePerRequestFilter {

    private final GovernanceKernel governanceKernel;
    private final PluginManager pluginManager;
    private final WebInterfaceManager webInterfaceManager;
    private final LingFrameProperties properties;
    private final RequestMappingHandlerMapping requestMappingHandlerMapping;

    private static final String HOST_PLUGIN_ID = "host-app";

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

        // 2. 判断是插件请求还是宿主请求
        WebInterfaceMetadata pluginMeta = webInterfaceManager.getMetadata(handlerMethod);
        boolean isPluginRequest = (pluginMeta != null);

        // 宿主请求：检查是否启用宿主治理
        if (!isPluginRequest && !properties.getHostGovernance().isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        // 3. 确定 pluginId
        String pluginId = isPluginRequest ? pluginMeta.getPluginId() : HOST_PLUGIN_ID;

        // 4. ClassLoader 切换（仅插件请求）
        ClassLoader originalCL = null;
        if (isPluginRequest) {
            originalCL = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(pluginMeta.getClassLoader());
        }

        // 5. 设置插件上下文
        PluginContextHolder.set(pluginId);

        try {
            // 6. 构建治理上下文
            Method method = handlerMethod.getMethod();
            InvocationContext ctx = buildInvocationContext(request, method, pluginId, pluginMeta);

            // 7. 获取 PluginRuntime（插件请求时）
            PluginRuntime runtime = isPluginRequest ? pluginManager.getRuntime(pluginId) : null;

            // 8. 通过 GovernanceKernel.invoke() 统一执行
            governanceKernel.invoke(runtime, method, ctx, () -> {
                try {
                    filterChain.doFilter(request, response);
                    return null;
                } catch (IOException | ServletException e) {
                    throw new RuntimeException(e);
                }
            });

        } catch (RuntimeException e) {
            // 解包可能的 ServletException/IOException
            if (e.getCause() instanceof ServletException se) {
                throw se;
            }
            if (e.getCause() instanceof IOException ioe) {
                throw ioe;
            }
            throw e;
        } finally {
            // 恢复 ClassLoader
            if (originalCL != null) {
                Thread.currentThread().setContextClassLoader(originalCL);
            }
            // 清理插件上下文
            PluginContextHolder.clear();
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
            String pluginId, WebInterfaceMetadata meta) {
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

        return InvocationContext.builder()
                .traceId(request.getHeader("X-Trace-Id"))
                .pluginId(pluginId)
                .callerPluginId("http-gateway") // Web 请求来源标记
                .resourceType("HTTP")
                .resourceId(request.getMethod() + " " + request.getRequestURI())
                .operation(method.getName())
                .requiredPermission(permission)
                .accessType(accessType)
                .auditAction(auditAction)
                .shouldAudit(shouldAudit)
                .metadata(new HashMap<>())
                .labels(new HashMap<>())
                .build();
    }
}
