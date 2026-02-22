package com.lingframe.core.proxy;

import com.lingframe.api.context.LingContextHolder;
import com.lingframe.api.security.AccessType;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.ling.LingRuntime;
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

    private final String callerLingId; // 谁在调用
    private final LingRuntime targetRuntime; // 核心锚点
    private final Class<?> serviceInterface;
    private final GovernanceKernel governanceKernel;// 内核

    // ================= 性能优化：ThreadLocal 对象池 =================
    // 在同一线程内复用 InvocationContext，避免每次 new 造成的 GC 压力
    // ⚠️警告：为了极致性能，这里故意没有调用 remove()。这意味着它会与线程（如 HTTP 工作线程）同寿命。
    // 必须确保 InvocationContext 不要被随意塞入由 LingClassLoader 加载的类实例，否则会引发严重泄漏。
    // 每次 invoke 结束时，必须执行清理（最终的 finally 块），切断大对象和强引用！
    private static final ThreadLocal<InvocationContext> CTX_POOL = ThreadLocal.withInitial(() -> null);

    // 缓存静态元数据 (如 ResourceId)，不再缓存动态权限
    // 🔥 使用实例级缓存而非 static，避免 Method Key 持有 Class → ClassLoader 引用导致泄漏
    private final Map<Method, String> resourceIdCache = new ConcurrentHashMap<>();

    public SmartServiceProxy(String callerLingId,
            LingRuntime targetRuntime, // 核心锚点,
            Class<?> serviceInterface,
            GovernanceKernel governanceKernel) {
        this.callerLingId = callerLingId;
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
            finalCtx.setCallerLingId(this.callerLingId);
            finalCtx.setLingId(targetRuntime.getLingId());
            finalCtx.setOperation(method.getName());
            // Runtime Data (每次请求必变)
            finalCtx.setArgs(args);
            // Resource
            finalCtx.setResourceType("RPC");
            // Labels
            Map<String, String> labels = LingContextHolder.getLabels();
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
                    return targetRuntime.invoke(finalCtx);
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

        @Override
        public synchronized Throwable fillInStackTrace() {
            // 优化：禁用异常栈收集。这个异常仅仅是作为穿透 Callable/Lambda 的载体，
            // 收集当前代理层的栈没有业务意义，禁用以获得极致性能。
            return this;
        }
    }

}