package com.lingframe.core.proxy;

import com.lingframe.api.context.PluginContextHolder;
import com.lingframe.api.security.AccessType;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.plugin.PluginRuntime;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能动态代理
 * 特性：元数据缓存 + ThreadLocal 上下文复用 + 零GC开销（除第一次）
 */
@Slf4j
public class SmartServiceProxy implements InvocationHandler {

    private final String callerPluginId; // 谁在调用
    private final PluginRuntime targetRuntime; // 核心锚点
    private final Class<?> serviceInterface;
    private final GovernanceKernel governanceKernel;// 内核

    // ================= 性能优化：ThreadLocal 对象池 =================
    // 在同一线程内复用 InvocationContext，避免每次 new 造成的 GC 压力
    private static final ThreadLocal<InvocationContext> CTX_POOL = ThreadLocal.withInitial(() -> null);

    // 缓存静态元数据 (如 ResourceId)，不再缓存动态权限
    // 🔥 使用实例级缓存而非 static，避免 Method Key 持有 Class → ClassLoader 引用导致泄漏
    private final Map<Method, String> resourceIdCache = new ConcurrentHashMap<>();

    public SmartServiceProxy(String callerPluginId,
            PluginRuntime targetRuntime, // 核心锚点,
            Class<?> serviceInterface,
            GovernanceKernel governanceKernel) {
        this.callerPluginId = callerPluginId;
        this.targetRuntime = targetRuntime;
        this.serviceInterface = serviceInterface;
        this.governanceKernel = governanceKernel;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class)
            return method.invoke(this, args);

        // 从 ThreadLocal 获取/复用 InvocationContext
        InvocationContext ctx = CTX_POOL.get();
        if (ctx == null) {
            // 第一次使用，创建新对象并存入 ThreadLocal
            ctx = InvocationContext.builder().build();
            CTX_POOL.set(ctx);
        }
        final InvocationContext finalCtx = ctx;

        try {
            // 【关键】重置/填充上下文属性
            // Identity
            finalCtx.setTraceId(null); // 由 Kernel 处理
            finalCtx.setCallerPluginId(this.callerPluginId);
            finalCtx.setPluginId(targetRuntime.getPluginId());
            finalCtx.setOperation(method.getName());
            // Runtime Data (每次请求必变)
            finalCtx.setArgs(args);
            // Resource
            finalCtx.setResourceType("RPC");
            // Labels
            Map<String, String> labels = PluginContextHolder.getLabels();
            finalCtx.setLabels(labels != null ? labels : Collections.emptyMap());

            String resourceId = resourceIdCache.computeIfAbsent(method,
                    m -> serviceInterface.getName() + ":" + m.getName());
            finalCtx.setResourceId(resourceId);

            finalCtx.setAccessType(AccessType.EXECUTE); // 简化处理
            finalCtx.setAuditAction(resourceId);

            // 清理上一次请求可能遗留的 metadata
            finalCtx.setMetadata(null);

            String fqsid = finalCtx.getResourceId(); // ResourceId 格式正是 Interface:Method
            // 委托内核执行
            return governanceKernel.invoke(targetRuntime, method, finalCtx, () -> {
                try {
                    // 🔥 修正：调用 Runtime 的标准入口，确保走路由、统计和隔离
                    // args 在这里是安全的，因为 Kernel 没有修改它
                    return targetRuntime.invoke(finalCtx.getCallerPluginId(), fqsid, finalCtx.getArgs());
                } catch (Exception e) {
                    throw new ProxyExecutionException(e);
                }
            });
        } catch (ProxyExecutionException e) {
            // 解包并抛出原始异常，对调用者透明
            throw e.getCause();
        } finally {
            // 【核心】清理大对象引用，防止内存泄漏
            // args 可能很大（如上传文件），labels 可能有脏数据，必须清空
            // 注意：这里不要 remove()，目的是为了复用 ctx 对象本身
            finalCtx.setArgs(null);
            finalCtx.setLabels(null);
            finalCtx.setMetadata(null);
            // TraceId 不需要清空，会被下一次 setTraceId 覆盖
        }
    }

    /**
     * 内部异常包装器 (用于穿透 Lambda，Kernel 捕获后会透传回来)
     */
    private static class ProxyExecutionException extends RuntimeException {
        public ProxyExecutionException(Throwable cause) {
            super(cause);
        }
    }

}