package com.lingframe.core.proxy;

import com.lingframe.api.annotation.Auditable;
import com.lingframe.api.annotation.RequiresPermission;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.audit.AuditManager;
import com.lingframe.core.monitor.TraceContext;
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
    private final AtomicReference<PluginInstance> activeInstanceRef;
    private final Class<?> serviceInterface;
    private final PermissionService permissionService; // 鉴权服务

    public SmartServiceProxy(String callerPluginId,
                             AtomicReference<PluginInstance> activeInstanceRef,
                             Class<?> serviceInterface,
                             PermissionService permissionService) {
        this.callerPluginId = callerPluginId;
        this.activeInstanceRef = activeInstanceRef;
        this.serviceInterface = serviceInterface;
        this.permissionService = permissionService;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) return method.invoke(this, args);

        // =========================================================
        // 1. [Monitor] 开启调用链监控
        // =========================================================
        boolean isRootTrace = (TraceContext.get() == null);
        String traceId = TraceContext.start();
        long startTime = System.nanoTime();

        try {

            // =========================================================
            // 2. [Security] 智能权限检查
            // =========================================================
            checkPermissionSmartly(method);

            // =========================================================
            // 3. [Routing] 获取活跃实例与 TCCL 劫持
            // =========================================================
            PluginInstance instance = activeInstanceRef.get();
            if (instance == null || !instance.getContainer().isActive()) {
                throw new IllegalStateException("Service unavailable: " + serviceInterface.getName());
            }

            instance.enter();
            Thread currentThread = Thread.currentThread();
            ClassLoader originalClassLoader = currentThread.getContextClassLoader();
            currentThread.setContextClassLoader(instance.getContainer().getClassLoader());

            Object result = null;
            try {
                Object realBean = instance.getContainer().getBean(serviceInterface);
                result = method.invoke(realBean, args);
                return result;
            } finally {
                currentThread.setContextClassLoader(originalClassLoader);
                instance.exit();

                // =========================================================
                // 4. [Audit] 智能审计 (异步)
                // =========================================================
                long cost = System.nanoTime() - startTime;
                recordAuditSmartly(traceId, method, args, result, cost);
            }
        } catch (Exception e) {
            log.error("[LingFrame] Service invocation failed. TraceId={}, Caller={}", traceId, callerPluginId, e);
            throw e;
        } finally {
            // =========================================================
            // 5. [Monitor] 关闭调用链
            // =========================================================
            if (isRootTrace) TraceContext.clear();
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