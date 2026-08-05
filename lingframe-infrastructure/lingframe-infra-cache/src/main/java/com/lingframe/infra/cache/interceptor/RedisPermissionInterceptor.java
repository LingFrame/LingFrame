package com.lingframe.infra.cache.interceptor;

import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.framework.ProxyFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

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

        // 获取调用方（当前灵元ID）
        String callerLingId = LingCallContext.getLingId();

        // 如果没有上下文（比如灵核启动时的自检），或者调用的是 Object 的基础方法（toString等）
        if (isObjectMethod(methodName)) {
            return invocation.proceed();
        }

        // 检查灵核治理开关
        if (callerLingId == null) {
            if (permissionService.isLingCoreGovernanceEnabled()) {
                log.error(
                        "Security Alert: Redis operation without LingContext (LINGCORE governance ENABLED). Method: {}",
                        methodName);
                throw new PermissionDeniedException(
                        "Access Denied: LINGCORE governance is enabled but no context provided for Redis operation: "
                                + methodName);
            }
            // 灵核治理关闭：默认放行
            return invocation.proceed();
        }

        // 简单的权限推导逻辑
        // 实际场景可能需要更细致的映射，比如 opsForValue() 应该返回代理对象
        // 这里主要拦截 RedisTemplate 自身的方法，如 delete, hasKey, expire 等
        AccessType accessType = inferAccessType(methodName);
        List<String> capabilities = resolveCapabilities(invocation);
        ResolvedCapability resolvedCapability = resolveCapability(callerLingId, accessType, capabilities);

        // 权限检查
        boolean allowed = resolvedCapability.allowed;

        // 审计日志 (异步)
        permissionService.audit(callerLingId, resolvedCapability.auditCapability, methodName, allowed);

        if (!allowed) {
            log.warn("Ling [{}] denied access to Redis: {}", callerLingId, methodName);
            throw new PermissionDeniedException(
                    "Ling [" + callerLingId + "] denied access to Redis operation: " + methodName);
        }

        // 执行原方法
        Object result = invocation.proceed();

        // 对 opsForXxx() 返回的子对象再套代理，防止通过子对象绕过权限拦截
        if (methodName.startsWith("opsFor") && result != null) {
            return wrapSubOperations(result);
        }
        return result;
    }

    /**
     * 对 RedisTemplate.opsForXxx() 返回的子对象再套代理，
     * 防止通过子对象直接操作 Redis 绕过权限拦截。
     * <p>
     * fail-closed：代理创建失败时拒绝暴露裸子对象，与 P0 治理原则一致。
     */
    private Object wrapSubOperations(Object subOperations) {
        try {
            ProxyFactory subProxy = new ProxyFactory(subOperations);
            subProxy.setProxyTargetClass(true);
            subProxy.addAdvice(this);
            return subProxy.getProxy();
        } catch (Exception e) {
            log.error("Failed to create governance proxy for Redis sub-operations, blocking access", e);
            throw new PermissionDeniedException(
                    "Cannot create governance proxy for Redis sub-operations: " + e.getMessage());
        }
    }

    /**
     * 精确匹配的原子读改写操作集合，按 WRITE 治理。
     * 这些方法语义上既读又写，且写语义更强，因此归到 WRITE。
     */
    private static final Set<String> WRITE_EXACT_METHODS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "getAndSet", "getAndDelete", "getAndIncrement", "getAndDecrement",
            "getAndAppend", "increment", "decrement", "append", "delete",
            "setIfPresent", "setIfAbsent")));

    /**
     * 精确匹配的纯读操作集合，按 READ 治理。
     */
    private static final Set<String> READ_EXACT_METHODS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "get", "getAll", "getAsString", "exists", "hasKey", "size")));

    /**
     * 推导操作类型。
     * <p>
     * 优先级：精确匹配 WRITE 原子读改写 > 精确匹配 READ 纯读 > 前缀匹配。
     * 关键点：{@code getAnd*} 开头的方法即使没命中精确集合，也不能按 READ 处理
     * （否则 getAndSet / getAndDelete 等会被误判为 READ）。
     */
    private AccessType inferAccessType(String methodName) {
        // 1. 精确匹配原子读改写操作优先 WRITE
        if (WRITE_EXACT_METHODS.contains(methodName)) {
            return AccessType.WRITE;
        }
        // 2. 精确匹配纯读操作
        if (READ_EXACT_METHODS.contains(methodName)) {
            return AccessType.READ;
        }
        // 3. getAnd* 开头的方法不视为 READ（已落到精确集合或不识别为 EXECUTE）
        if (methodName.startsWith("getAnd")) {
            return AccessType.EXECUTE;
        }
        // 4. 前缀匹配兜底
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

    private ResolvedCapability resolveCapability(String callerLingId, AccessType accessType, List<String> capabilities) {
        if (capabilities != null && !capabilities.isEmpty()) {
            String auditCapability = capabilities.size() == 1
                    ? capabilities.get(0)
                    : String.join(", ", capabilities);
            boolean allAllowed = true;
            for (String capability : capabilities) {
                if (!permissionService.isAllowed(callerLingId, capability, accessType)) {
                    allAllowed = false;
                    break;
                }
            }
            if (allAllowed) {
                return new ResolvedCapability(auditCapability, true);
            }
            // 细粒度规则已命中且判定失败：不回退通用 cache:redis 权限，直接拒绝。
            // 否则"细粒度显式拒绝"会被"通用允许"覆盖，造成越权。
            return new ResolvedCapability(auditCapability, false);
        }
        // 无细粒度规则（如无 key 参数可推断 pattern）：才回退到通用 cache:redis 权限
        return new ResolvedCapability("cache:redis",
                permissionService.isAllowed(callerLingId, "cache:redis", accessType));
    }

    private List<String> resolveCapabilities(MethodInvocation invocation) {
        List<String> keyPatterns = inferKeyPatterns(invocation.getArguments());
        List<String> capabilities = new ArrayList<>();
        for (String keyPattern : keyPatterns) {
            capabilities.add("cache:redis:" + keyPattern);
        }
        return capabilities;
    }

    private List<String> inferKeyPatterns(Object[] args) {
        Set<String> patterns = new LinkedHashSet<>();
        if (args == null || args.length == 0 || args[0] == null) {
            return new ArrayList<>(patterns);
        }
        Object candidate = args[0];
        if (candidate instanceof String) {
            addPattern(patterns, candidate);
            return new ArrayList<>(patterns);
        }
        if (candidate instanceof Object[]) {
            for (Object key : (Object[]) candidate) {
                addPattern(patterns, key);
            }
            return new ArrayList<>(patterns);
        }
        if (candidate instanceof Iterable) {
            Iterator<?> iterator = ((Iterable<?>) candidate).iterator();
            if (iterator.hasNext()) {
                do {
                    addPattern(patterns, iterator.next());
                } while (iterator.hasNext());
            }
        }
        return new ArrayList<>(patterns);
    }

    private void addPattern(Set<String> patterns, Object candidate) {
        if (!(candidate instanceof String)) {
            return;
        }
        String pattern = toPattern((String) candidate);
        if (pattern != null) {
            patterns.add(pattern);
        }
    }

    private static final class ResolvedCapability {
        private final String auditCapability;
        private final boolean allowed;

        private ResolvedCapability(String auditCapability, boolean allowed) {
            this.auditCapability = auditCapability;
            this.allowed = allowed;
        }
    }

    private String toPattern(String key) {
        if (key == null || key.trim().isEmpty()) {
            return null;
        }
        int separator = key.indexOf(':');
        if (separator <= 0) {
            return "key:" + key;
        }
        return "key:" + key.substring(0, separator) + ":*";
    }
}
