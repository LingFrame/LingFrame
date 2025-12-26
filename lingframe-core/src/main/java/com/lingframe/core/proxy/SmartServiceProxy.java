package com.lingframe.core.proxy;

import com.lingframe.api.annotation.Auditable;
import com.lingframe.api.annotation.RequiresPermission;
import com.lingframe.api.context.PluginContextHolder;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.plugin.PluginInstance;
import com.lingframe.core.plugin.PluginSlot;
import com.lingframe.core.strategy.GovernanceStrategy;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能动态代理
 * 特性：元数据缓存 + ThreadLocal 上下文复用 + 零GC开销（除第一次）
 */
@Slf4j
public class SmartServiceProxy implements InvocationHandler {

    private final String callerPluginId; // 谁在调用
    private final PluginSlot targetSlot; // 核心锚点
    private final Class<?> serviceInterface;
    private final GovernanceKernel governanceKernel;// 内核
    private final PermissionService permissionService; // 鉴权服务

    // ================= 性能优化：元数据缓存 =================
    private static final ConcurrentHashMap<Method, MethodMetadata> METADATA_CACHE = new ConcurrentHashMap<>();

    // ================= 性能优化：ThreadLocal 对象池 =================
    // 在同一线程内复用 InvocationContext，避免每次 new 造成的 GC 压力
    private static final ThreadLocal<InvocationContext> CTX_POOL = ThreadLocal.withInitial(() -> null);

    // ================= 内部类：元数据封装 =================
    private record MethodMetadata(String requiredPermission, AccessType accessType, boolean shouldAudit,
                                  String auditAction, String resourceId) {
    }

    // 🔥元数据缓存：避免每次调用都进行昂贵的跨ClassLoader反射
    // Key: 接口方法对象, Value: 审计注解 (如果没有则存 null)
    // 使用 WeakHashMap 解决 Method 导致的类加载器泄露
    private static final Map<Method, Auditable> AUDIT_CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    // 标记对象，用于缓存中表示"无注解"，防止穿透
    private static final Auditable NULL_ANNOTATION = new Auditable() {
        public Class<? extends java.lang.annotation.Annotation> annotationType() {
            return Auditable.class;
        }

        public String action() {
            return "";
        }

        public String resource() {
            return "";
        }
    };

    public SmartServiceProxy(String callerPluginId,
                             PluginSlot targetSlot, // 核心锚点,
                             Class<?> serviceInterface,
                             GovernanceKernel governanceKernel,
                             PermissionService permissionService) {
        this.callerPluginId = callerPluginId;
        this.targetSlot = targetSlot;
        this.serviceInterface = serviceInterface;
        this.governanceKernel = governanceKernel;
        this.permissionService = permissionService;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) return method.invoke(this, args);

        // 1. 获取方法元数据（第一次计算，后续直接读缓存）
        MethodMetadata meta = METADATA_CACHE.computeIfAbsent(method, m -> resolveMethodMetadata(m, serviceInterface));

        // 2. 从 ThreadLocal 获取/复用 InvocationContext
        InvocationContext ctx = CTX_POOL.get();
        if (ctx == null) {
            // 第一次使用，创建新对象并存入 ThreadLocal
            ctx = InvocationContext.builder().build();
            CTX_POOL.set(ctx);
        }

        try {
            // 3. 【关键】重置/填充上下文属性 (利用 @Data 生成的 setter)
            // Identity
            ctx.setTraceId(null); // 由 Kernel 处理
            ctx.setCallerPluginId(this.callerPluginId);
            ctx.setPluginId(targetSlot.getPluginId());

            // Resource
            ctx.setResourceType("RPC");
            // 优先使用元数据中预计算的 ResourceId
            ctx.setResourceId(meta.resourceId());
            ctx.setOperation(method.getName());

            // Governance Metadata (从缓存读)
            ctx.setRequiredPermission(meta.requiredPermission());
            ctx.setAccessType(meta.accessType());
            ctx.setShouldAudit(meta.shouldAudit());
            ctx.setAuditAction(meta.auditAction());

            // Runtime Data (每次请求必变)
            ctx.setArgs(args);

            // Labels
            Map<String, String> labels = PluginContextHolder.getLabels();
            ctx.setLabels(labels != null ? labels : Collections.emptyMap());

            // 清理上一次请求可能遗留的 metadata
            ctx.setMetadata(null);

            // 4. 委托内核执行
            InvocationContext finalCtx = ctx;
            return governanceKernel.invoke(ctx, () -> {
                PluginInstance instance = targetSlot.selectInstance(finalCtx);
                if (instance == null) throw new IllegalStateException("Service unavailable");

                instance.enter();
                PluginContextHolder.set(this.callerPluginId);
                Thread t = Thread.currentThread();
                ClassLoader oldCL = t.getContextClassLoader();
                t.setContextClassLoader(instance.getContainer().getClassLoader());
                try {
                    Object bean = instance.getContainer().getBean(serviceInterface);
                    try {
                        return method.invoke(bean, args);
                    } catch (IllegalAccessException | InvocationTargetException e) {
                        throw new RuntimeException(e);
                    }
                } finally {
                    t.setContextClassLoader(oldCL);
                    PluginContextHolder.clear();
                    instance.exit();
                }
            });
        } finally {
            // 5. 【核心】清理大对象引用，防止内存泄漏
            // args 可能很大（如上传文件），labels 可能有脏数据，必须清空
            // 注意：这里不要 remove()，目的是为了复用 ctx 对象本身
            ctx.setArgs(null);
            ctx.setLabels(null);
            ctx.setMetadata(null);
            // TraceId 不需要清空，会被下一次 setTraceId 覆盖
        }
    }

    /**
     * 解析方法元数据（仅执行一次）
     */
    private MethodMetadata resolveMethodMetadata(Method method, Class<?> serviceInterface) {
        // A. 权限推导
        String permission;
        RequiresPermission permAnn = method.getAnnotation(RequiresPermission.class);
        if (permAnn != null) {
            permission = permAnn.value();
        } else {
            permission = GovernanceStrategy.inferPermission(method);
        }

        // B. 审计推导
        boolean shouldAudit = false;
        String auditAction = method.getName();

        Auditable auditAnn = AUDIT_CACHE.get(method);
        if (auditAnn == null) {
            auditAnn = method.getAnnotation(Auditable.class);
            if (auditAnn == null) {
                auditAnn = findAnnotationOnImplementation(method);
            }
            AUDIT_CACHE.put(method, (auditAnn == null) ? NULL_ANNOTATION : auditAnn);
        }

        if (auditAnn != null && auditAnn != NULL_ANNOTATION) {
            shouldAudit = true;
            auditAction = auditAnn.action();
        } else {
            AccessType accessType = GovernanceStrategy.inferAccessType(method.getName());
            if (accessType == AccessType.WRITE || accessType == AccessType.EXECUTE) {
                shouldAudit = true;
                auditAction = GovernanceStrategy.inferAuditAction(method);
            }
        }

        String resourceId = serviceInterface.getName() + ":" + method.getName();

        return new MethodMetadata(
                permission,
                AccessType.EXECUTE,
                shouldAudit,
                auditAction,
                resourceId
        );
    }

    /**
     * 🔥【核心】跨 ClassLoader 查找实现类上的注解
     */
    private Auditable findAnnotationOnImplementation(Method interfaceMethod) {
        // 这里的逻辑必须通过 Slot 获取一个实例来辅助查找类信息
        PluginInstance instance = targetSlot.getDefaultInstance().get();
        if (instance == null) return NULL_ANNOTATION;

        // 必须切换到插件的 ClassLoader，否则我们看不见实现类，也无法反射获取它的 Method
        Thread t = Thread.currentThread();
        ClassLoader oldCL = t.getContextClassLoader();
        ClassLoader pluginCL = instance.getContainer().getClassLoader();

        t.setContextClassLoader(pluginCL);
        try {
            // 1. 获取目标 Bean (实现类对象)
            Object targetBean = instance.getContainer().getBean(serviceInterface);
            if (targetBean == null) return null;

            // 2. 获取实现类 Class
            Class<?> targetClass = targetBean.getClass(); // e.g., UserOrderService

            // 3. 反射获取对应的实现方法
            // 注意：这里需要精准匹配参数类型
            Method implMethod = targetClass.getMethod(interfaceMethod.getName(), interfaceMethod.getParameterTypes());

            // 4. 获取注解
            Auditable ann = implMethod.getAnnotation(Auditable.class);
            return (ann != null) ? ann : NULL_ANNOTATION;
        } catch (Exception e) {
            // 比如方法没找到，或者Bean没初始化好，忽略异常，视为无注解
            log.trace("Failed to find implementation annotation for {}", interfaceMethod.getName());
            return NULL_ANNOTATION;
        } finally {
            t.setContextClassLoader(oldCL);
        }
    }

}