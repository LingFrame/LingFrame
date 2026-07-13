package com.lingframe.core.context;

import com.lingframe.api.context.LingContext;
import com.lingframe.api.event.LingEvent;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationExecutionMode;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.proxy.GlobalServiceRoutingProxy;
import com.lingframe.core.ling.LingInstance;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DefaultLingContext implements LingContext {

    private final LingInstance instance;
    private final String lingId;

    private final LingRepository lingRepository;
    private final LingServiceRegistry lingServiceRegistry;
    private final InvocationPipelineEngine pipelineEngine;
    private final PermissionService permissionService;
    private final EventBus eventBus;

    // 灵核上下文构造函数：灵核本身无 LingInstance，instance=null 表示不参与实例级服务方法注册
    public DefaultLingContext(String lingId, LingRepository lingRepository,
            LingServiceRegistry lingServiceRegistry, InvocationPipelineEngine pipelineEngine,
            PermissionService permissionService, EventBus eventBus) {
        this.instance = null;
        this.lingId = lingId;
        this.lingRepository = lingRepository;
        this.lingServiceRegistry = lingServiceRegistry;
        this.pipelineEngine = pipelineEngine;
        this.permissionService = permissionService;
        this.eventBus = eventBus;
    }

    // 灵元部署构造函数：绑定实例以支持实例级服务方法精准注册
    public DefaultLingContext(LingInstance instance, LingRepository lingRepository,
            LingServiceRegistry lingServiceRegistry, InvocationPipelineEngine pipelineEngine,
            PermissionService permissionService, EventBus eventBus) {
        this.instance = instance;
        this.lingId = instance != null ? instance.getLingId() : null;
        this.lingRepository = lingRepository;
        this.lingServiceRegistry = lingServiceRegistry;
        this.pipelineEngine = pipelineEngine;
        this.permissionService = permissionService;
        this.eventBus = eventBus;
    }

    @Override
    public String getLingId() {
        return lingId;
    }

    /**
     * 暴露 LingServiceRegistry 供适配层构造统注册器。
     * 边界：core 内部不通过此 getter 自用——适配层（Spring/Native）需要拿注册器
     * 委派给 LingServiceRegistrar，避免注册逻辑重复散在适配层。
     */
    public LingServiceRegistry getLingServiceRegistry() {
        return lingServiceRegistry;
    }

    @Override
    public Optional<String> getProperty(String key) {
        // 实际应从 Core 的配置中心获取受控配置
        return Optional.ofNullable(System.getProperty(key));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getService(Class<T> serviceClass) {
        try {
            T service = (T) Proxy.newProxyInstance(
                    serviceClass.getClassLoader(),
                    new Class[] { serviceClass },
                    new GlobalServiceRoutingProxy(lingId, serviceClass.getName(), null,
                            lingRepository, pipelineEngine, lingServiceRegistry));
            return Optional.ofNullable(service);
        } catch (Exception e) {
            log.warn("Service get failed.", e);
            return Optional.empty();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> invoke(String serviceId, Object... args) {
        if (serviceId == null || serviceId.isEmpty()) {
            throw new InvalidArgumentException("serviceId", "Service ID cannot be empty.");
        }

        List<String> methods = lingServiceRegistry.getProviderMethods(serviceId);
        if (methods == null || methods.isEmpty()) {
            log.warn("Cannot find metadata for invokeService: {}", serviceId);
            return Optional.empty();
        }

        String firstMethodSig = methods.get(0);
        String extractedMethodName = firstMethodSig.substring(0, firstMethodSig.indexOf('('));
        String[] paramTypeNames = parseParamTypeNames(firstMethodSig);

        InvocationContext ctx = InvocationContext.obtain();
        ctx.setServiceFQSID(serviceId);
        ctx.setCallerLingId(lingId);
        ctx.setTargetLingId(extractLingId(serviceId));
        ctx.setMethodName(extractedMethodName);
        ctx.setParameterTypeNames(paramTypeNames);
        ctx.setArgs(args);

        // targetClassName 由 ContextIsolationFilter 从 FQSID 提取接口名填充，
        // 不再从注册表预填，避免多版本下实现类名错配
        ctx.execution().setMode(InvocationExecutionMode.NORMAL);

        try {
            Object result = pipelineEngine.invoke(ctx);
            return (Optional<T>) Optional.ofNullable(result);
        } catch (PermissionDeniedException e) {
            throw e; // 权限异常直接抛出
        } catch (Exception e) {
            log.error("Service invocation failed for [{}]: {}", serviceId, e.getMessage(), e);
            throw new LingInvocationException(serviceId, LingInvocationException.ErrorKind.INTERNAL_ERROR, e);
        } finally {
            // 这里至少要彻底清空，防止跨调用残留；若后续统一收口对象池策略，可再整体切到 recycle()
            ctx.reset();
        }
    }

    @Override
    public <T> T invokeOrDefault(String serviceId, T defaultValue, Object... args) {
        try {
            Optional<T> result = this.invoke(serviceId, args);
            return result.orElse(defaultValue);
        } catch (LingInvocationException e) {
            log.warn("[Fallback] Invoke {} failed (code: {}). Returning default value.", serviceId, e.getKind());
            return defaultValue;
        }
    }

    @Override
    public <T> T invokeOrElse(String serviceId, Supplier<T> fallbackSupplier, Object... args) {
        try {
            Optional<T> result = this.invoke(serviceId, args);
            return result.orElseGet(fallbackSupplier);
        } catch (LingInvocationException e) {
            log.warn("[Fallback] Invoke {} failed (code: {}). Executing fallback supplier.", serviceId, e.getKind());
            return fallbackSupplier.get();
        }
    }

    public void registerProtocolService(String fqsid, Object bean, Method method) {
        String methodName = method.getName();
        Class<?>[] paramTypes = method.getParameterTypes();
        String[] paramNames = new String[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            paramNames[i] = paramTypes[i].getName();
        }
        String returnTypeName = method.getReturnType().getName();
        lingServiceRegistry.registerServiceMetadata(fqsid, methodName, paramNames, returnTypeName);

        // 注册实现类全限定名：对显式注解服务是 Pipeline 解析的唯一类名来源，
        // 对接口服务是冗余但幂等的注册（接口名本身可直接 Class.forName）。
        lingServiceRegistry.registerImplementationClassName(fqsid, bean.getClass().getName());

        // 实例级注册：如果绑定了具体实例，则在实例上精准记录该服务方法的归属
        if (instance != null) {
            instance.registerServiceMethod(fqsid, methodName, paramNames);
        }
    }

    private String[] parseParamTypeNames(String signature) {
        if (signature == null) {
            return new String[0];
        }
        int start = signature.indexOf('(');
        int end = signature.indexOf(')');
        if (start < 0 || end < 0 || end <= start + 1) {
            return new String[0];
        }
        String inside = signature.substring(start + 1, end).trim();
        if (inside.isEmpty()) {
            return new String[0];
        }
        return inside.split("\\s*,\\s*");
    }

    private String extractLingId(String serviceId) {
        if (serviceId == null) {
            return null;
        }
        int idx = serviceId.indexOf(':');
        if (idx > 0) {
            return serviceId.substring(0, idx);
        }
        return null;
    }

    @Override
    public PermissionService getPermissionService() {
        return permissionService;
    }

    @Override
    public void publishEvent(LingEvent event) {
        log.info("Event published from {}: {}", lingId, event);
        eventBus.publish(event);
    }

    // ════════════════════════════════════════════════════════════════════
    // 边界：以下是 LingContext 新加 default 方法的真实覆写。
    // 灵元上下文（instance != null）下 expose 走 registerProtocolService，
    // 灵核上下文（instance == null）下 expose 暂走 lingServiceRegistry 直注。
    // ════════════════════════════════════════════════════════════════════

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getService(Class<T> serviceClass, String lingIdAnchor, String serviceIdAnchor) {
        // 锚点为空时退化为默认 getService(Class)
        if ((lingIdAnchor == null || lingIdAnchor.isEmpty())
                && (serviceIdAnchor == null || serviceIdAnchor.isEmpty())) {
            return getService(serviceClass);
        }
        try {
            // serviceId 含 ':' 视为完整 FQSID，lingIdAnchor 忽略；
            // 否则按 [lingIdAnchor 或当前灵元]:[serviceIdAnchor 或接口名] 拼 FQSID
            String fqsid;
            String routingInterfaceName; // 透给 GlobalServiceRoutingProxy 的 interfaceName
            if (serviceIdAnchor != null && serviceIdAnchor.indexOf(':') > 0) {
                fqsid = serviceIdAnchor;
                // FQSID 分支：透 FQSID 的 contract 部分作 interfaceName，
                // 这样 SmartServiceProxy 下游拼 serviceFQSID = targetLingId + ":" + contractPart
                // 与 Registrar 注册的 FQSID（如 lingcore-app:authService）匹配。
                // 用 serviceClass.getName() 会拼成 lingcore-app:com.example.AuthService，不匹配。
                routingInterfaceName = serviceIdAnchor.substring(serviceIdAnchor.indexOf(':') + 1);
            } else {
                String ling = (lingIdAnchor != null && !lingIdAnchor.isEmpty()) ? lingIdAnchor : this.lingId;
                // 非 FQSID 分支：serviceIdAnchor 是短 ID 或空，透接口 FQCN 让隐式接口注册命中
                String svc = (serviceIdAnchor != null && !serviceIdAnchor.isEmpty())
                        ? serviceIdAnchor : serviceClass.getName();
                fqsid = ling + ":" + svc;
                // routingInterfaceName 必须与注册时的 FQSID 后半部保持一致：
                // - serviceIdAnchor 非空（短 ID 锚点）：透短 ID 本身，下游 SmartServiceProxy 拼 targetLingId:svc
                //   与 Registrar 注册的 lingId:svc 命中
                // - serviceIdAnchor 空（隐式按类型路由）：透接口 FQCN，与隐式接口注册键 lingId:interfaceFQCN 命中
                routingInterfaceName = svc;
            }
            T service = (T) Proxy.newProxyInstance(
                    serviceClass.getClassLoader(),
                    new Class[] { serviceClass },
                    new GlobalServiceRoutingProxy(this.lingId, routingInterfaceName,
                            extractLingId(fqsid), lingRepository, pipelineEngine, lingServiceRegistry));
            return Optional.ofNullable(service);
        } catch (Exception e) {
            log.warn("Service get with anchor failed.", e);
            return Optional.empty();
        }
    }

    @Override
    public void expose(String serviceId, Object handler) {
        if (serviceId == null || serviceId.isEmpty() || handler == null) {
            return;
        }
        String fqsid = this.lingId + ":" + serviceId;
        // 边界：expose() 是程序化 API，handler 由调用方主动传入，调用方对暴露的方法集负责。
        // 与 LingServiceRegistrar 的「框架自动扫描 Bean」不同——后者需要 BusinessInterfaceFilter
        // 过滤框架接口；前者信任调用方，仅跳过 Object 基础方法。
        for (Method method : handler.getClass().getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            registerProtocolService(fqsid, handler, method);
        }
        log.info("[Context] 程序化暴露服务: ling=[{}], serviceId=[{}], handler=[{}]",
                lingId, serviceId, handler.getClass().getName());
    }
}
