package com.lingframe.core.context;

import com.lingframe.api.context.LingContext;
import com.lingframe.api.event.LingEvent;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.event.EventBus;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.core.exception.InvocationException;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.proxy.GlobalServiceRoutingProxy;
import com.lingframe.core.kernel.InvocationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.lang.reflect.Proxy;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class CoreLingContext implements LingContext {

    private final String lingId;

    private final LingRepository lingRepository;
    private final LingServiceRegistry lingServiceRegistry;
    private final InvocationPipelineEngine pipelineEngine;
    private final PermissionService permissionService;
    private final EventBus eventBus;

    @Override
    public String getLingId() {
        return lingId;
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
                            lingRepository, pipelineEngine));
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

        String className = lingServiceRegistry.getServiceClassName(serviceId);
        if (className == null) {
            log.warn("Cannot find metadata for invokeService: {}", serviceId);
            return Optional.empty();
        }

        List<String> methods = lingServiceRegistry.getProviderMethods(serviceId);
        if (methods == null || methods.isEmpty()) {
            return Optional.empty();
        }

        String firstMethodSig = methods.get(0);
        String extractedMethodName = firstMethodSig.substring(0, firstMethodSig.indexOf('('));

        InvocationContext ctx = InvocationContext.obtain();
        ctx.setServiceFQSID(serviceId);
        ctx.setMethodName(extractedMethodName);
        ctx.setArgs(args);

        ctx.getAttachments().put("ling.target.className", className);
        ctx.getAttachments().put("ling.caller.id", lingId);

        try {
            Object result = pipelineEngine.invoke(ctx);
            return (Optional<T>) Optional.ofNullable(result);
        } catch (PermissionDeniedException e) {
            throw e; // 权限异常直接抛出
        } catch (Exception e) {
            log.error("Service invocation failed for [{}]: {}", serviceId, e.getMessage(), e);
            throw new InvocationException("Service invoke failed: " + e.getMessage(), e);
        } finally {
            ctx.reset();
        }
    }

    public void registerProtocolService(String fqsid, Object bean, Method method) {
        String methodName = method.getName();
        Class<?>[] paramTypes = method.getParameterTypes();
        String[] paramNames = new String[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            paramNames[i] = paramTypes[i].getName();
        }
        lingServiceRegistry.registerServiceMetadata(fqsid, method.getDeclaringClass().getName(), methodName,
                paramNames);
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
}