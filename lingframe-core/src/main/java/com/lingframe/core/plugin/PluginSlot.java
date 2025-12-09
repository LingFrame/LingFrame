package com.lingframe.core.plugin;

import com.lingframe.api.context.PluginContext;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.proxy.SmartServiceProxy;
import com.lingframe.core.spi.PluginContainer;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 插件槽位：管理蓝绿发布与自然消亡
 */
@Slf4j
public class PluginSlot {

    private static final int MAX_HISTORY_SNAPSHOTS = 3; // OOM 防御：最多保留3个历史快照

    private final String pluginId;

    // 指向当前最新版本的原子引用
    private final AtomicReference<PluginInstance> activeInstance = new AtomicReference<>();

    // 死亡队列：存放待销毁的旧版本
    private final ConcurrentLinkedQueue<PluginInstance> dyingInstances = new ConcurrentLinkedQueue<>();

    // 代理缓存：Map<InterfaceClass, ProxyObject>
    private final Map<Class<?>, Object> proxyCache = new ConcurrentHashMap<>();

    private final PermissionService permissionService;

    public PluginSlot(String pluginId, ScheduledExecutorService sharedScheduler, PermissionService permissionService) {
        this.pluginId = pluginId;
        this.permissionService = permissionService;
        // 清理任务调度器：共享的全局线程池
        // 每 5 秒检查一次是否有可以回收的旧实例
        sharedScheduler.scheduleAtFixedRate(this::checkAndKill, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * 蓝绿部署：切换到新版本
     */
    public synchronized void upgrade(PluginInstance newInstance, PluginContext pluginContext) {
        // 1. 背压保护：如果历史版本积压过多，拒绝发布
        if (dyingInstances.size() >= MAX_HISTORY_SNAPSHOTS) {
            log.error("[{}] Too many dying instances. System busy.", pluginId);
            return;
        }
        PluginInstance oldInstance = activeInstance.get();

        // 2. 启动新版本容器
        log.info("[{}] Starting new version: {}", pluginId, newInstance.getVersion());
        PluginContainer container = newInstance.getContainer();
        if (container == null) {
            log.error("[{}] PluginContainer is null", pluginId);
            return;
        }
        container.start(pluginContext);

        // 3. 原子切换流量
        activeInstance.set(newInstance);
        log.info("[{}] Traffic switched to version: {}", pluginId, newInstance.getVersion());

        // 4. 将旧版本放入死亡队列
        if (oldInstance != null) {
            oldInstance.markDying();
            dyingInstances.add(oldInstance);
            log.info("[{}] Version {} marked for dying", pluginId, oldInstance.getVersion());
        }
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
                        new SmartServiceProxy(callerPluginId, activeInstance, interfaceClass, permissionService)
                )
        );
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
        PluginInstance instance = activeInstance.get();
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
        PluginInstance current = activeInstance.getAndSet(null); // 原子置空
        if (current != null) {
            current.markDying();
            dyingInstances.add(current);
            log.info("[{}] Plugin uninstalled. Version {} moved to dying queue.", pluginId, current.getVersion());
        }
        // 尝试立即清理一次 (如果正好引用计数为0，直接销毁)
        checkAndKill();
    }
}