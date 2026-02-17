package com.lingframe.core.plugin;

import com.lingframe.core.plugin.event.RuntimeEvent;
import com.lingframe.core.plugin.event.RuntimeEventBus;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.core.exception.ServiceNotFoundException;
import com.lingframe.core.exception.InvocationException;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 服务注册表
 * 职责：管理服务注册、代理缓存、方法句柄优化
 */
@Slf4j
public class ServiceRegistry {

    private final String pluginId;

    // FQSID -> InvokableService 缓存
    private final Map<String, InvokableService> serviceCache = new ConcurrentHashMap<>();

    // 接口类 -> 代理对象 缓存
    private final Map<Class<?>, Object> proxyCache = new ConcurrentHashMap<>();

    // MethodHandles.Lookup 实例（复用）
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    public ServiceRegistry(String pluginId) {
        this.pluginId = pluginId;
    }

    /**
     * 注册事件监听（由 PluginRuntime 调用）
     */
    public void registerEventHandlers(RuntimeEventBus eventBus) {
        // 升级时清理代理缓存（服务实现可能变了）
        eventBus.subscribe(RuntimeEvent.InstanceUpgrading.class, this::onInstanceUpgrading);

        // 关闭时清理所有缓存
        eventBus.subscribe(RuntimeEvent.RuntimeShuttingDown.class, this::onRuntimeShuttingDown);

        log.debug("[{}] ServiceRegistry event handlers registered", pluginId);
    }

    private void onInstanceUpgrading(RuntimeEvent.InstanceUpgrading event) {
        log.debug("[{}] Instance upgrading, clearing proxy cache", pluginId);
        clearProxyCache();
    }

    private void onRuntimeShuttingDown(RuntimeEvent.RuntimeShuttingDown event) {
        log.debug("[{}] Runtime shutting down, clearing all caches", pluginId);
        clear();
    }

    // ==================== 服务注册 ====================

    /**
     * 注册服务
     *
     * @param fqsid  全路径服务ID (Fully Qualified Service ID)
     * @param bean   服务实例
     * @param method 服务方法
     * @return 是否为新注册（false 表示覆盖）
     */
    public boolean registerService(String fqsid, Object bean, Method method) {
        if (fqsid == null || fqsid.isBlank()) {
            throw new InvalidArgumentException("fqsid", "FQSID cannot be null or blank");
        }
        if (bean == null) {
            throw new InvalidArgumentException("bean", "Bean cannot be null");
        }
        if (method == null) {
            throw new InvalidArgumentException("method", "Method cannot be null");
        }

        try {
            // 解除权限检查，提升性能
            method.setAccessible(true);

            // 转换为 MethodHandle（比反射快约 2-4 倍）
            MethodHandle methodHandle = LOOKUP.unreflect(method).bindTo(bean);

            InvokableService service = new InvokableService(bean, method, methodHandle);
            InvokableService old = serviceCache.put(fqsid, service);

            if (old != null) {
                log.warn("[{}] Service {} was overwritten", pluginId, fqsid);
                return false;
            }

            log.debug("[{}] Registered service: {}", pluginId, fqsid);
            return true;

        } catch (IllegalAccessException e) {
            throw new InvocationException("Failed to create MethodHandle for " + fqsid, e);
        }
    }

    /**
     * 批量注册服务
     */
    public int registerServices(Map<String, ServiceDefinition> services) {
        int count = 0;
        for (Map.Entry<String, ServiceDefinition> entry : services.entrySet()) {
            ServiceDefinition def = entry.getValue();
            if (registerService(entry.getKey(), def.bean(), def.method())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 注销服务
     */
    public boolean unregisterService(String fqsid) {
        InvokableService removed = serviceCache.remove(fqsid);
        if (removed != null) {
            log.debug("[{}] Unregistered service: {}", pluginId, fqsid);
            return true;
        }
        return false;
    }

    // ==================== 服务查询 ====================

    /**
     * 获取服务
     */
    public InvokableService getService(String fqsid) {
        return serviceCache.get(fqsid);
    }

    /**
     * 获取服务（必须存在）
     */
    public InvokableService getServiceRequired(String fqsid) {
        InvokableService service = serviceCache.get(fqsid);
        if (service == null) {
            log.error("[{}] Service not found: {}", pluginId, fqsid);
            throw new ServiceNotFoundException(fqsid);
        }
        return service;
    }

    /**
     * 检查服务是否存在
     */
    public boolean hasService(String fqsid) {
        return serviceCache.containsKey(fqsid);
    }

    /**
     * 获取所有注册的 FQSID
     */
    public Set<String> getAllServiceIds() {
        return Set.copyOf(serviceCache.keySet());
    }

    /**
     * 获取服务数量
     */
    public int getServiceCount() {
        return serviceCache.size();
    }

    // ==================== 代理管理 ====================

    /**
     * 获取或创建代理
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrCreateProxy(Class<T> interfaceClass, Function<Class<?>, Object> proxyFactory) {
        return (T) proxyCache.computeIfAbsent(interfaceClass, proxyFactory);
    }

    /**
     * 获取缓存的代理
     */
    @SuppressWarnings("unchecked")
    public <T> T getCachedProxy(Class<T> interfaceClass) {
        return (T) proxyCache.get(interfaceClass);
    }

    /**
     * 检查是否有缓存的代理
     */
    public boolean hasProxy(Class<?> interfaceClass) {
        return proxyCache.containsKey(interfaceClass);
    }

    /**
     * 移除缓存的代理
     */
    public void removeProxy(Class<?> interfaceClass) {
        proxyCache.remove(interfaceClass);
    }

    /**
     * 获取代理缓存数量
     */
    public int getProxyCacheSize() {
        return proxyCache.size();
    }

    // ==================== 生命周期 ====================

    /**
     * 清空所有缓存，并主动断开 InvokableService 的引用链
     */
    public void clear() {
        int serviceCount = serviceCache.size();
        int proxyCount = proxyCache.size();

        // 🔥 主动 nullify 每个 InvokableService，切断 bean/method → Class → ClassLoader 引用
        for (InvokableService service : serviceCache.values()) {
            service.nullify();
        }
        serviceCache.clear();
        proxyCache.clear();

        log.debug("[{}] Cleared registry: {} services, {} proxies",
                pluginId, serviceCount, proxyCount);
    }

    /**
     * 仅清空代理缓存（热更新时使用）
     */
    public void clearProxyCache() {
        int proxyCount = proxyCache.size();
        proxyCache.clear();
        log.debug("[{}] Cleared proxy cache: {} proxies", pluginId, proxyCount);
    }

    /**
     * 获取统计信息
     */
    public RegistryStats getStats() {
        return new RegistryStats(serviceCache.size(), proxyCache.size());
    }

    // ==================== 内部类 ====================

    /**
     * 可调用的服务（包含优化后的 MethodHandle）
     * <p>
     * 非 record：支持 {@link #nullify()} 主动断开引用链，
     * 避免 bean/method/methodHandle → Class → ClassLoader 残留。
     * </p>
     */
    public static class InvokableService {

        private volatile Object bean;
        private volatile Method method;
        private volatile MethodHandle methodHandle;

        public InvokableService(Object bean, Method method, MethodHandle methodHandle) {
            this.bean = bean;
            this.method = method;
            this.methodHandle = methodHandle;
        }

        /**
         * 使用 MethodHandle 快速调用
         */
        public Object invokeFast(Object... args) throws Throwable {
            MethodHandle mh = this.methodHandle;
            if (mh == null)
                throw new IllegalStateException("Service has been nullified");
            return mh.invokeWithArguments(args);
        }

        /**
         * 使用反射调用（兼容模式）
         */
        public Object invokeReflect(Object... args) throws Exception {
            Method m = this.method;
            Object b = this.bean;
            if (m == null || b == null)
                throw new IllegalStateException("Service has been nullified");
            return m.invoke(b, args);
        }

        /**
         * 获取方法签名
         */
        public String getSignature() {
            Method m = this.method;
            if (m == null)
                return "<nullified>";
            return m.getDeclaringClass().getSimpleName() + "." + m.getName();
        }

        /**
         * 主动断开所有引用，释放 bean → Class → ClassLoader 链
         */
        public void nullify() {
            this.bean = null;
            this.method = null;
            this.methodHandle = null;
        }

        public Object bean() {
            return bean;
        }

        public Method method() {
            return method;
        }

        public MethodHandle methodHandle() {
            return methodHandle;
        }
    }

    /**
     * 服务定义（用于批量注册）
     */
    public record ServiceDefinition(Object bean, Method method) {
    }

    /**
     * 注册表统计信息
     */
    public record RegistryStats(int serviceCount, int proxyCacheSize) {
        @Override
        @NonNull
        public String toString() {
            return String.format("RegistryStats{services=%d, proxies=%d}",
                    serviceCount, proxyCacheSize);
        }
    }
}