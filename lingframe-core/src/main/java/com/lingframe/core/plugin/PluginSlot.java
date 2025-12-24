package com.lingframe.core.plugin;

import com.lingframe.api.context.PluginContext;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.proxy.SmartServiceProxy;
import com.lingframe.core.spi.PluginContainer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 插件槽位：管理蓝绿发布与自然消亡
 */
@Slf4j
public class PluginSlot {

    // OOM 防御：最多保留5个历史快照
    private static final int MAX_HISTORY_SNAPSHOTS = 5;

    @Getter
    private final String pluginId;

    // 实例池：支持多版本并存 [核心演进]
    private final CopyOnWriteArrayList<PluginInstance> activePool = new CopyOnWriteArrayList<>();
    // 默认实例引用 (用于保底路由和兼容旧逻辑)
    private final AtomicReference<PluginInstance> defaultInstance = new AtomicReference<>();

    // 死亡队列：存放待销毁的旧版本
    private final ConcurrentLinkedQueue<PluginInstance> dyingInstances = new ConcurrentLinkedQueue<>();

    // 代理缓存：Map<InterfaceClass, ProxyObject>
    private final Map<Class<?>, Object> proxyCache = new ConcurrentHashMap<>();

    // 【新增】FQSID -> InvokableService 缓存 (用于协议服务)
    // 缓存 FQSID 对应的可执行方法和 Bean 实例
    private final Map<String, InvokableService> serviceMethodCache = new ConcurrentHashMap<>();

    private final PermissionService permissionService;

    private final GovernanceKernel governanceKernel;

    // 🔥【关键】这个引用是动态的，指向当前 Active 的插件实例
    // Proxy 持有这个引用的对象(Object Reference)，所以当 Slot 内部通过 set() 切换版本时，
    // Proxy 也能立即感知到变化。
//    @Getter
//    private final AtomicReference<PluginInstance> activeInstanceRef = new AtomicReference<>();

    public PluginSlot(String pluginId, ScheduledExecutorService sharedScheduler, PermissionService permissionService, GovernanceKernel governanceKernel) {
        this.pluginId = pluginId;
        this.permissionService = permissionService;
        this.governanceKernel = governanceKernel;
        // 清理任务调度器：共享的全局线程池
        // 每 5 秒检查一次是否有可以回收的旧实例
        sharedScheduler.scheduleAtFixedRate(this::checkAndKill, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * 核心路由：支持标签匹配
     */
    public PluginInstance selectInstance(InvocationContext ctx) {
        Map<String, String> requestLabels = ctx.getLabels();
        if (requestLabels == null || requestLabels.isEmpty()) return defaultInstance.get();

        return activePool.stream()
                .map(inst -> new ScoredInstance(inst, calculateScore(inst.getLabels(), requestLabels)))
                .filter(si -> si.score >= 0)
                .max(Comparator.comparingInt(si -> si.score))
                .map(si -> si.instance)
                .orElseGet(defaultInstance::get);
    }

    private int calculateScore(Map<String, String> instLabels, Map<String, String> reqLabels) {
        int score = 0;
        for (Map.Entry<String, String> entry : reqLabels.entrySet()) {
            String val = instLabels.get(entry.getKey());
            if (Objects.equals(val, entry.getValue())) score += 10;
            else if (val != null) return -1;
        }
        return score;
    }

    public synchronized void addInstance(PluginInstance newInstance, PluginContext pluginContext, boolean isDefault) {
        // 1. 背压保护：如果历史版本积压过多，拒绝发布
        if (dyingInstances.size() >= MAX_HISTORY_SNAPSHOTS) {
            log.error("[{}] Too many dying instances. System busy.", pluginId);
            return;
        }

        // 先清理缓存再加载新容器
        clearCaches();
        log.info("[{}] Service method cache cleared and ready for new version.", pluginId);

        // 启动新版本容器
        log.info("[{}] Starting new version: {}", pluginId, newInstance.getVersion());
        PluginContainer container = newInstance.getContainer();
        if (container == null) {
            log.error("[{}] PluginContainer is null", pluginId);
            return;
        }
        container.start(pluginContext);

        activePool.add(newInstance);
        if (isDefault) {
            PluginInstance old = defaultInstance.getAndSet(newInstance);
            if (old != null) {
                moveToDying(old);
            }
        }
        log.info("[{}] Instance {} added (Default={})", pluginId, newInstance.getVersion(), isDefault);
    }

    private synchronized void moveToDying(PluginInstance inst) {
        inst.markDying();
        activePool.remove(inst);
        dyingInstances.add(inst);
    }

    /**
     * 获取服务的动态代理
     * 注意：返回的永远是同一个 Proxy 对象，但内部指向会变
     */
    @SuppressWarnings("unchecked")
    public <T> T getService(String callerPluginId, Class<T> interfaceClass) {
        return (T) proxyCache.computeIfAbsent(interfaceClass, k ->
                Proxy.newProxyInstance(
                        this.getClass().getClassLoader(), // 使用 Core 的 ClassLoader
                        new Class<?>[]{interfaceClass},
                        new SmartServiceProxy(
                                callerPluginId,// 谁在调
                                this,// 调谁 (就是当前 Slot) 🔥
                                interfaceClass,
                                governanceKernel,
                                permissionService
                        )
                ));
    }

    /**
     * 【新增】注册真实的可执行服务 (由 PluginManager 调用)
     */
    public void registerService(String fqsid, Object bean, Method method) {
        // method.setAccessible(true); // 如果是 private 方法可能需要
        serviceMethodCache.put(fqsid, new InvokableService(bean, method));
    }

    /**
     * 协议服务调用入口 (由 PluginManager.invokeExtension 调用)
     * 职责：TCCL劫持 + 查找 Bean + 反射调用 + 引用计数
     */
    public Object invokeService(String callerPluginId, String fqsid, Object[] args) throws Exception {
        // 协议调用暂走默认实例，或根据 ThreadLocal 标签路由
        PluginInstance instance = defaultInstance.get();
        if (instance == null || !instance.getContainer().isActive()) {
            throw new IllegalStateException("Service unavailable for FQSID: " + fqsid);
        }

        instance.enter(); // 引用计数 +1
        Thread currentThread = Thread.currentThread();
        ClassLoader originalClassLoader = currentThread.getContextClassLoader();

        try {
            // 1. TCCL 劫持：确保在正确的类加载器中执行代码
            currentThread.setContextClassLoader(instance.getContainer().getClassLoader());

            // 2. FQSID 查找实际方法和 Bean
            // 查找缓存中已注册的可执行服务
            InvokableService invokable = getInvokableService(fqsid, instance.getContainer());

            if (invokable == null) {
                throw new NoSuchMethodException("FQSID not found in slot: " + fqsid);
            }

            // 3. 执行调用
            return invokable.method().invoke(invokable.bean(), args);

        } catch (Exception e) {
            log.error("[LingFrame] Protocol service invocation failed. FQSID={}, Caller={}", fqsid, callerPluginId, e);
            // 统一包装异常，向上抛出
            throw new RuntimeException("Protocol service invocation error: " + e.getMessage(), e);
        } finally {
            // 4. TCCL 恢复与引用计数递减
            currentThread.setContextClassLoader(originalClassLoader);
            instance.exit(); // 引用计数 -1
        }
    }

    /**
     * 【内部方法】模拟从 PluginContainer 查找可执行服务
     */
    private InvokableService getInvokableService(String fqsid, PluginContainer container) {
        // 由于真正的类扫描和MethodHandle注册在当前文件外，这里是生产环境的简化占位逻辑。
        return serviceMethodCache.computeIfAbsent(fqsid, k -> {
            try {
                // 模拟根据 FQSID 找到目标 Bean 和 Method
                // 实际应根据 FQSID 逆向解析出 BeanName 和 MethodSignature
                log.warn("LingFrame 警告：协议服务查找逻辑正在使用模拟数据，FQSID: {}", fqsid);

                // 假设 FQSID 是 "user-service:query_by_name"，我们找到一个 ExportFacade Bean
                String beanName = fqsid.split(":")[0] + "ExportFacade";
                Object bean = container.getBean(beanName);

                if (bean == null) return null;

                // 假设 MethodHandle 已经通过扫描找到并存入
                // 这里手动查找一个方法作为演示，生产环境应避免 this.getClass()... 查找
                Method[] methods = bean.getClass().getDeclaredMethods();
                for (Method m : methods) {
                    if (m.getName().toLowerCase().contains("query")) { // 找到第一个包含 query 的方法
                        m.setAccessible(true);
                        return new InvokableService(bean, m);
                    }
                }
                return null;

            } catch (Exception e) {
                log.error("Failed to mock find invokable service for FQSID: {}", fqsid, e);
                return null;
            }
        });
    }

    /**
     * 定时任务：检查并物理销毁旧实例
     */
    private void checkAndKill() {
        dyingInstances.removeIf(instance -> {
            // 只有引用计数归零，才真正销毁
            if (instance.isIdle()) {
                log.info("[{}] Garbage Collecting version: {}", pluginId, instance.getVersion());
                try {
                    instance.destroy();
                } catch (Exception e) {
                    log.error("Error destroying plugin instance", e);
                }
                return true; // 从队列移除
            }
            return false; // 还有流量，暂不销毁
        });
    }

    /**
     * 获取当前活跃版本号
     */
    public String getVersion() {
        PluginInstance instance = defaultInstance.get();
        return (instance != null) ? instance.getVersion() : null;
    }

    /**
     * 卸载整个槽位
     * 逻辑：
     * 1. 将 Active 实例置空 (切断新流量)
     * 2. 将原 Active 实例移入死亡队列 (处理剩余流量)
     * 3. 触发一次清理检查
     */
    public void uninstall() {
        activePool.forEach(this::moveToDying);
        defaultInstance.set(null);
        clearCaches();
        // 尝试立即清理一次 (如果正好引用计数为0，直接销毁)
        checkAndKill();

        // 添加超时检查，防止长时间阻塞
        scheduleForceCleanupIfNecessary();
    }

    /**
     * 如果插件实例长时间未能正常销毁，则强制清理
     */
    private void scheduleForceCleanupIfNecessary() {
        // 在单独的线程中检查是否需要强制清理
        Thread forceCleanupThread = new Thread(() -> {
            try {
                Thread.sleep(30000); // 等待30秒
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            dyingInstances.removeIf(instance -> {
                log.warn("[{}] Force cleaning plugin instance after 30 seconds: {}", pluginId, instance.getVersion());
                try {
                    instance.destroy();
                } catch (Exception e) {
                    log.error("Error force destroying plugin instance", e);
                }
                return true;
            });
        });
        forceCleanupThread.setDaemon(true);
        forceCleanupThread.setName("lingframe-force-cleanup-" + pluginId);
        forceCleanupThread.start();
    }

    private void clearCaches() {
        serviceMethodCache.clear();
        proxyCache.clear();
    }

    // 【新增内部类】用于缓存可执行的服务对象和方法
    private record InvokableService(Object bean, Method method) {
    }

    private record ScoredInstance(PluginInstance instance, int score) {
    }

    public boolean hasBean(Class<?> type) {
        try {
            PluginInstance instance = defaultInstance.get();
            if (instance == null || !instance.getContainer().isActive()) return false;

            // 需要在 PluginContainer 接口增加 containsBean(Class) 或者复用 getBean
            Object bean = instance.getContainer().getBean(type);
            return bean != null;
        } catch (Exception e) {
            return false; // 找不到或报错都算 false
        }
    }
}