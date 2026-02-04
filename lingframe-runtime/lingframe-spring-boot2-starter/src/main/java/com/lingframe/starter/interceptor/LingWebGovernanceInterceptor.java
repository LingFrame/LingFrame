package com.lingframe.starter.interceptor;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashMap;

@Slf4j
@RequiredArgsConstructor
public class LingWebGovernanceInterceptor implements HandlerInterceptor {

    private final GovernanceKernel governanceKernel;
    private final PluginManager pluginManager;
    private final WebInterfaceManager webInterfaceManager;
    private final LingFrameProperties properties;

    private static final String HOST_PLUGIN_ID = "host-app";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 检查是否为 HandlerMethod (排除静态资源等)
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        HandlerMethod handlerMethod = (HandlerMethod) handler;

        // 2. 判断是插件请求还是宿主请求
        WebInterfaceMetadata pluginMeta = webInterfaceManager.getMetadata(handlerMethod);
        boolean isPluginRequest = (pluginMeta != null);

        // 宿主请求：检查是否启用宿主治理
        if (!isPluginRequest && !properties.getHostGovernance().isEnabled()) {
            return true;
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
            // 6. 构建治理上下文 (复用你的 buildInvocationContext 方法)
            Method method = handlerMethod.getMethod();
            InvocationContext ctx = buildInvocationContext(request, method, pluginId, pluginMeta);

            // 7. 获取 PluginRuntime（插件请求时）
            PluginRuntime runtime = isPluginRequest ? pluginManager.getRuntime(pluginId) : null;

            // 8. 通过 GovernanceKernel.invoke() 统一执行
            // 注意：这里不能直接调用 filterChain.doFilter
            // 拦截器的返回值 true 表示继续执行 Controller，false 表示中断
            // 这里的逻辑需要适配一下，GovernanceKernel 应该返回 boolean 或者抛异常来决定是否放行

            // 假设 GovernanceKernel.invoke 内部如果不抛异常就算通过，
            // 我们可以稍微调整 invoke 的 lambda，让它不负责调用链，只负责逻辑校验
            governanceKernel.invoke(runtime, method, ctx, () -> null);

            return true; // 校验通过，继续执行 Controller

        } catch (RuntimeException e) {
            // 处理异常，决定是否中断
            if (e.getCause() instanceof ServletException) {
                ServletException se = (ServletException) e.getCause();
                throw se;
            }
            if (e.getCause() instanceof IOException) {
                IOException ioe = (IOException) e.getCause();
                throw ioe;
            }
            throw e; // 中断请求
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
