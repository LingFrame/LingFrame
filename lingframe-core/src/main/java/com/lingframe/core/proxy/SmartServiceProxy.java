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
import com.lingframe.core.plugin.PluginSlot;
import com.lingframe.core.strategy.GovernanceStrategy;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 智能动态代理：动态路由 + TCCL劫持 + 权限治理 + 链路监控 + 审计
 * 负责在运行时将流量路由到最新的 PluginInstance
 */
@Slf4j
public class SmartServiceProxy implements InvocationHandler {

    private final String callerPluginId; // 谁在调用
    private final PluginSlot targetSlot; // 核心锚点
    private final Class<?> serviceInterface;
    private final GovernanceKernel governanceKernel;// 内核
    private final PermissionService permissionService; // 鉴权服务

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

        // 步骤 A: 先从缓存拿
        Auditable auditAnn = AUDIT_CACHE.get(method);

        // 步骤 B: 缓存未命中，开始查找
        if (auditAnn == null) {
            // B1. 查接口 (优先)
            auditAnn = method.getAnnotation(Auditable.class);

            // B2. 查实现类 (如果接口没有)
            if (auditAnn == null) {
                auditAnn = findAnnotationOnImplementation(method);
            }

            // B3. 写入缓存
            AUDIT_CACHE.put(method, (auditAnn == null) ? NULL_ANNOTATION : auditAnn);
        }

        if (auditAnn != null && auditAnn != NULL_ANNOTATION) {
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
                .pluginId(targetSlot.getPluginId())
                .resourceType("RPC")
                .resourceId(serviceInterface.getName() + ":" + method.getName())
                .operation(method.getName())
                .args(args)
                // 填入推导结果
                .requiredPermission(permission)
                .accessType(AccessType.EXECUTE) // RPC 调用通常视为执行
                .shouldAudit(shouldAudit)
                .auditAction(auditAction)
                .labels(new HashMap<>())// 实际从线程上下文获取染色标签
                .build();

        // === 3. 委托内核 (内存安全闭环) ===
        return governanceKernel.invoke(ctx, () -> {
            PluginInstance instance = targetSlot.selectInstance(ctx);
            if (instance == null) throw new IllegalStateException("Service unavailable");

            instance.enter();
            PluginContextHolder.set(callerPluginId);
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
                instance.exit(); // 防御 ClassLoader 泄漏
            }
        });
    }

    /**
     * 🔥【核心】跨 ClassLoader 查找实现类上的注解
     */
    private Auditable findAnnotationOnImplementation(Method interfaceMethod) {
        // 这里的逻辑必须通过 Slot 获取一个实例来辅助查找类信息
        PluginInstance instance = targetSlot.selectInstance(InvocationContext.builder().build());
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