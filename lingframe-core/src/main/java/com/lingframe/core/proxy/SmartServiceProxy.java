package com.lingframe.core.proxy;

import com.lingframe.api.annotation.Auditable;
import com.lingframe.api.annotation.RequiresPermission;
import com.lingframe.api.context.PluginContextHolder;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.audit.AuditManager;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.plugin.PluginInstance;
import com.lingframe.core.strategy.GovernanceStrategy;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 智能动态代理：动态路由 + TCCL劫持 + 权限治理 + 链路监控 + 审计
 * 负责在运行时将流量路由到最新的 PluginInstance
 */
@Slf4j
public class SmartServiceProxy implements InvocationHandler {

    private final String callerPluginId; // 谁在调用
    private final String targetPluginId; // 🔥【新增】目标插件ID
    private final AtomicReference<PluginInstance> activeInstanceRef;
    private final Class<?> serviceInterface;
    private final GovernanceKernel governanceKernel;// 内核
    private final PermissionService permissionService; // 鉴权服务

    public SmartServiceProxy(String callerPluginId, String targetPluginId,
                             AtomicReference<PluginInstance> activeInstanceRef,
                             Class<?> serviceInterface, GovernanceKernel governanceKernel,
                             PermissionService permissionService) {
        this.callerPluginId = callerPluginId;
        this.targetPluginId = targetPluginId;
        this.activeInstanceRef = activeInstanceRef;
        this.serviceInterface = serviceInterface;
        this.governanceKernel = governanceKernel;
        this.permissionService = permissionService;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) return method.invoke(this, args);

        // === 1. 智能推导阶段 (Strategy Layer) ===

        // A. 权限推导
        String permission;
        RequiresPermission permAnn = method.getAnnotation(RequiresPermission.class);
        if (permAnn != null) {
            permission = permAnn.value();
        } else {
            // 根据方法名推测权限 (如 saveUser -> user:write)
            permission = GovernanceStrategy.inferPermission(method);
        }

        // B. 审计推导
        boolean shouldAudit = false;
        String auditAction = method.getName();
        Auditable auditAnn = method.getAnnotation(Auditable.class);

        if (auditAnn != null) {
            shouldAudit = true;
            auditAction = auditAnn.action();
        } else {
            // 🔥 复活智能审计：如果是写操作，自动审计
            AccessType accessType = GovernanceStrategy.inferAccessType(method.getName());
            if (accessType == AccessType.WRITE || accessType == AccessType.EXECUTE) {
                shouldAudit = true;
                auditAction = GovernanceStrategy.inferAuditAction(method);
            }
        }

        // === 2. 构建上下文 ===
        InvocationContext ctx = InvocationContext.builder()
                .traceId(null) // Kernel 自动处理
                .callerPluginId(callerPluginId)
                .pluginId(targetPluginId)
                .resourceType("RPC")
                .resourceId(serviceInterface.getName() + ":" + method.getName())
                .operation(method.getName())
                .args(args)
                // 填入推导结果
                .requiredPermission(permission)
                .accessType(AccessType.EXECUTE) // RPC 调用通常视为执行
                .shouldAudit(shouldAudit)
                .auditAction(auditAction)
                .build();

        // === 3. 委托内核 ===
        return governanceKernel.invoke(ctx, () -> {
            try {
                return doInvoke(method, args);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        });
    }

    private Object doInvoke(Method method, Object[] args) throws Throwable {
        PluginContextHolder.set(callerPluginId);
        PluginInstance instance = activeInstanceRef.get();
        if (instance == null || !instance.getContainer().isActive()) {
            throw new IllegalStateException("Service unavailable");
        }
        instance.enter();
        Thread t = Thread.currentThread();
        ClassLoader old = t.getContextClassLoader();
        t.setContextClassLoader(instance.getContainer().getClassLoader());
        try {
            Object bean = instance.getContainer().getBean(serviceInterface);
            return method.invoke(bean, args);
        } finally {
            t.setContextClassLoader(old);
            instance.exit();
            PluginContextHolder.clear();
        }
    }

    private void checkPermissionSmartly(Method method) {
        String capability;

        // 策略 1: 显式注解 (方法 > 类)
        RequiresPermission annotation = method.getAnnotation(RequiresPermission.class);
        if (annotation == null) {
            annotation = method.getDeclaringClass().getAnnotation(RequiresPermission.class);
        }

        if (annotation != null) {
            capability = annotation.value();
        } else {
            // 策略 2: 智能推导
            capability = GovernanceStrategy.inferPermission(method);
        }

        if (!permissionService.isAllowed(callerPluginId, capability, AccessType.EXECUTE)) {
            throw new PermissionDeniedException(
                    String.format("Access Denied: Plugin [%s] cannot access [%s]", callerPluginId, capability)
            );
        }
    }

    private void recordAuditSmartly(String traceId, Method method, Object[] args, Object result, long cost) {
        boolean shouldAudit = false;
        String action = "";
        String resource = "";

        // 策略 1: 显式注解
        if (method.isAnnotationPresent(Auditable.class)) {
            shouldAudit = true;
            Auditable ann = method.getAnnotation(Auditable.class);
            action = ann.action();
            resource = ann.resource();
        }
        // 策略 2: 智能推导 (默认审计写操作)
        else {
            AccessType type = GovernanceStrategy.inferAccessType(method.getName());
            if (type == AccessType.WRITE || type == AccessType.EXECUTE) {
                shouldAudit = true;
                action = GovernanceStrategy.inferAuditAction(method);
                resource = "Auto-Inferred";
            }
        }

        if (shouldAudit) {
            AuditManager.asyncRecord(traceId, callerPluginId, action, resource, args, result, cost);
        }
    }
}