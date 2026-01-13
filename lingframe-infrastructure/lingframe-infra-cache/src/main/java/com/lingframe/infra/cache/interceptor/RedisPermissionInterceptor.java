package com.lingframe.infra.cache.interceptor;

import com.lingframe.api.context.PluginContextHolder;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

import java.lang.reflect.Method;

/**
 * Redis 操作权限拦截器
 * 拦截 RedisTemplate 的方法调用，进行权限检查和审计
 */
@Slf4j
public class RedisPermissionInterceptor implements MethodInterceptor {

    private final PermissionService permissionService;

    public RedisPermissionInterceptor(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        String methodName = method.getName();

        // 获取调用方（当前插件ID）
        String callerPluginId = PluginContextHolder.get();

        // 如果没有上下文（比如宿主启动时的自检），或者调用的是 Object 的基础方法（toString等）
        if (isObjectMethod(methodName)) {
            return invocation.proceed();
        }

        // 检查宿主治理开关
        if (callerPluginId == null) {
            if (permissionService.isHostGovernanceEnabled()) {
                log.error("Security Alert: Redis operation without PluginContext (Host governance ENABLED). Method: {}",
                        methodName);
                throw new PermissionDeniedException(
                        "Access Denied: Host governance is enabled but no context provided for Redis operation: "
                                + methodName);
            }
            // 宿主治理关闭：默认放行
            return invocation.proceed();
        }

        // 简单的权限推导逻辑
        // 实际场景可能需要更细致的映射，比如 opsForValue() 应该返回代理对象
        // 这里主要拦截 RedisTemplate 自身的方法，如 delete, hasKey, expire 等
        AccessType accessType = inferAccessType(methodName);
        String capability = "cache:redis";

        // 权限检查
        boolean allowed = permissionService.isAllowed(callerPluginId, capability, accessType);

        // 审计日志 (异步)
        // 记录具体的 Key 通常作为参数 0
        String resource = "redis";
        if (invocation.getArguments().length > 0 && invocation.getArguments()[0] != null) {
            resource = invocation.getArguments()[0].toString();
        }

        permissionService.audit(callerPluginId, capability, methodName, allowed);

        if (!allowed) {
            log.warn("Plugin [{}] denied access to Redis: {}", callerPluginId, methodName);
            throw new PermissionDeniedException(
                    "Plugin [" + callerPluginId + "] denied access to Redis operation: " + methodName);
        }

        // 执行原方法
        return invocation.proceed();
    }

    /**
     * 推导操作类型
     */
    private AccessType inferAccessType(String methodName) {
        if (methodName.startsWith("get") || methodName.startsWith("has") || methodName.startsWith("keys")) {
            return AccessType.READ;
        }
        if (methodName.startsWith("set") || methodName.startsWith("delete") ||
                methodName.startsWith("expire") || methodName.startsWith("convertAndSend")) {
            return AccessType.WRITE;
        }
        return AccessType.EXECUTE;
    }

    private boolean isObjectMethod(String name) {
        return "toString".equals(name) || "hashCode".equals(name) || "equals".equals(name) || "getClass".equals(name);
    }
}